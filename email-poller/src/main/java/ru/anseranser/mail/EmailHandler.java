package ru.anseranser.mail;

import com.google.gson.Gson;
import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import ru.anseranser.pii.PiiMasker;
import ru.anseranser.security.InjectionClassifier;
import yandex.cloud.sdk.functions.Context;
import yandex.cloud.sdk.functions.YcFunction;

import java.io.IOException;
import java.util.Map;

/**
 * Orchestrator that coordinates email receiving, text extraction,
 * LLM agent communication, and email sending.
 * Delegates work to {@link EmailReceiver}, {@link EmailTextExtractor},
 * {@link AgentClient}, and {@link SmtpEmailSender} (from common).
 *
 * <p>On the poller boundary the incoming email body is first PII-masked
 * ({@link PiiMasker}) and then checked for prompt injection
 * ({@link InjectionClassifier}). If an injection is detected, the agent is
 * never called and no ticket is created — the client receives a neutral
 * "information unknown" reply. This is a first line of defense on the ingress
 * edge; {@code ydb-tickets} still re-checks on {@code create-ticket} as a
 * second line.
 */
public class EmailHandler implements YcFunction<String, String> {

    /**
     * Neutral reply sent when a prompt injection is detected on the poller ingress.
     * Deliberately identical to the RAG fallback phrasing so an attacker learns nothing.
     */
    private static final String INJECTION_MASK_REPLY =
            "У меня нет информации по этому вопросу в базе знаний. Могу создать обращение, "
            + "и специалист свяжется с вами для консультации.";

    private final EmailReceiver receiver;
    private final SmtpEmailSender sender;
    private final AgentClient agent;
    private final EmailTextExtractor extractor;
    private final YdbMessageSaver ydbSaver;

    public EmailHandler() {
        this.receiver = new EmailReceiver(
                System.getenv("IMAP_HOST"),
                993,
                System.getenv("IMAP_USER"),
                System.getenv("IMAP_PASSWORD")
        );
        this.sender = new SmtpEmailSender(
                System.getenv("SMTP_HOST"),
                System.getenv("SMTP_PORT"),
                System.getenv("SMTP_USER"),
                System.getenv("SMTP_PASSWORD"),
                System.getenv("HELPDESK_MAILBOX")
        );
        this.agent = new AgentClient(
                System.getenv("YANDEX_API_KEY"),
                System.getenv("AGENT_ID"),
                System.getenv("ORGANIZATION_ID"),
                System.getenv("MCP_SERVER_URL"),
                System.getenv("VECTOR_STORE_ID")
        );
        this.extractor = new EmailTextExtractor();
        this.ydbSaver = new YdbMessageSaver();
    }

    /**
     * Package-private constructor for unit testing with mocked dependencies.
     */
    EmailHandler(EmailReceiver receiver, SmtpEmailSender sender, AgentClient agent, EmailTextExtractor extractor) {
        this(receiver, sender, agent, extractor, new YdbMessageSaver());
    }

    /**
     * Package-private constructor for unit testing with a mocked {@link YdbMessageSaver}.
     */
    EmailHandler(EmailReceiver receiver, SmtpEmailSender sender, AgentClient agent, EmailTextExtractor extractor, YdbMessageSaver ydbSaver) {
        this.receiver = receiver;
        this.sender = sender;
        this.agent = agent;
        this.extractor = extractor;
        this.ydbSaver = ydbSaver;
    }

    @Override
    public String handle(String s, Context context) {
        int mailCount = 0;
        try {
            receiver.connect();
            Message[] messages = receiver.fetchUnreadMessages();

            if (messages != null) {
                System.out.printf("Got %d unseen messages.%s", messages.length, System.lineSeparator());
                for (Message message : messages) {
                    mailCount += processMessage(message, mailCount);
                }
            }
        } catch (MessagingException e) {
            System.out.println("Failed to connect or fetch emails from IMAP server: " + e.getMessage());
        } finally {
            try {
                receiver.disconnect();
            } catch (Exception ignored) {
            }
        }
        return mailCount + " mail(s) done";
    }

    /**
     * Processes a single email message: extracts text, calls the agent,
     * sends the reply, and marks the message as seen.
     * <p>
     * M1 fix: message is marked Seen in finally to avoid poller loop on poison messages
     * (see step 4 подсказка: при ошибке всё равно маркируйте \Seen).
     *
     * @return 1 if the message was processed successfully, 0 otherwise
     */
    private int processMessage(Message message, int index) {
        String from = "unknown";
        boolean success = false;
        try {
            from = getAddress(message.getFrom()[0]);
            String subject = message.getSubject();
            System.out.printf("Message #%d, from: %s, subject: %s%s", index + 1, from, subject, System.lineSeparator());

            String body = extractor.extractPlainText(message);
            if (body == null || body.isBlank()) {
                System.out.println("Skipping email with empty or unreadable content from: " + from);
                return 0;
            }

            // Threading headers are needed both for normal replies and the early injection reply.
            String originalMessageId = null;
            String originalSubject = null;
            try {
                String[] msgIdHeader = message.getHeader("Message-ID");
                if (msgIdHeader != null && msgIdHeader.length > 0) {
                    originalMessageId = msgIdHeader[0];
                }
                originalSubject = message.getSubject();
            } catch (Exception ignored) {
            }
            String replySubject = buildReplySubject(originalSubject);

            // First line of defense on the ingress: mask PII, then check for prompt injection.
            // The agent only ever sees the masked body, so no raw PII reaches the LLM and no
            // malicious payload survives to trigger a create-ticket. ydb-tickets re-checks
            // on create-ticket as a second line.
            String maskedBody = PiiMasker.maskPii(body);
            String classification = InjectionClassifier.classify(maskedBody);
            if ("injection".equals(classification)) {
                // Same greppable prefix as ydb-tickets ALERT_INJECTION_BLOCKED.
                // The attempted message is logged with PII already masked (maskedBody) —
                // raw PII never reaches the logs.
                System.err.printf("ALERT_INJECTION_BLOCKED: user_id=%s, text_length=%d, text=%s%s",
                        from, body.length(), maskedBody, System.lineSeparator());
                // Neutral "information unknown" reply — no agent call, no create-ticket, no YDB write.
                // The malicious payload is NOT echoed back in the quoted reply (pass null quote).
                sender.sendWithThreading(from, replySubject, INJECTION_MASK_REPLY, originalMessageId, null);
                success = true;
                return 1;
            }

            // Transport-message: pass the full email body (with all thread quotes) to the agent as `text`.
            // The agent (LLM) is responsible for extracting the original question from the quoted chain —
            // the poller does NOT parse "whose quote is this" (see CONTEXT.md: Транспорт-сообщение пользователя).
            // The body is already PII-masked above.
            Map<String, Object> reqMap = new java.util.HashMap<>();
            reqMap.put("user_id", from);
            reqMap.put("text", maskedBody);
            String jsonRequest = new Gson().toJson(reqMap);
            // P1 fix: use getResponseWithUsage to capture tokens/latency for observability (step 9)
            AgentClient.AgentResult result = agent.getResponseWithUsage(jsonRequest);
            String response = result.text();
            // Variant 1: threading — preserve user text unchanged via quoted reply, PII stays masked in YDB (ydb-tickets PiiMasker)
            // Email quote is user-owned data, not a leak; YDB and logs keep masked version
            sender.sendWithThreading(from, replySubject, response, originalMessageId, body);
            // Log token/latency explicitly for comparison with messages.tokens_in/out (≤10% rule).
            System.out.printf("EMAIL_TOKENS user_id=%s input=%d output=%d latency=%d responseId=%s model=%s%s",
                    from, result.inputTokens(), result.outputTokens(), result.latencyMs(), result.responseId(), result.model(), System.lineSeparator());
            // Step 9 ("доставайте их отдельно"): poller is the authoritative source of usage — the LLM agent
            // cannot see its own tokens/latency. Append the role=agent row with real usage via ydb-tickets
            // append-message. create-ticket (by agent) already wrote the role=user row with the client's
            // verbatim words. See ADR-0001 amendment.
            ydbSaver.trySave(from, result, response);
            success = true;
            return 1;
        } catch (MessagingException e) {
            System.out.println("Failed to send reply email: " + e.getMessage());
            return 0;
        } catch (IOException e) {
            System.out.println("Failed to parse email content: " + e.getMessage());
            return 0;
        } catch (Exception e) {
            System.out.println("Failed to process email: " + e.getMessage());
            return 0;
        } finally {
            try {
                receiver.markAsSeen(message);
                if (!success) {
                    System.out.println("Marked poison/failed message as Seen to avoid loop: from=" + from);
                }
            } catch (MessagingException me) {
                System.out.println("Failed to mark message as Seen: " + me.getMessage());
            } catch (Exception ex) {
                System.out.println("Unexpected error marking Seen: " + ex.getMessage());
            }
        }
    }

    /**
     * Delegates disconnect to the receiver.
     */
    public void disconnect() {
        receiver.disconnect();
    }

    private String getAddress(Address address) {
        return ((InternetAddress) address).getAddress();
    }

    private String buildReplySubject(String originalSubject) {
        if (originalSubject == null || originalSubject.isBlank()) {
            return "Re: (no subject)";
        }
        String trimmed = originalSubject.trim();
        if (trimmed.toLowerCase().startsWith("re:")) {
            return trimmed;
        }
        return "Re: " + trimmed;
    }
}
