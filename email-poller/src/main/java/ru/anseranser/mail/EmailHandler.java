package ru.anseranser.mail;

import com.google.gson.Gson;
import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import yandex.cloud.sdk.functions.Context;
import yandex.cloud.sdk.functions.YcFunction;

import java.io.IOException;
import java.util.Map;

/**
 * Orchestrator that coordinates email receiving, text extraction,
 * LLM agent communication, and email sending.
 * Delegates work to {@link EmailReceiver}, {@link EmailTextExtractor},
 * {@link AgentClient}, and {@link SmtpEmailSender} (from common).
 */
public class EmailHandler implements YcFunction<String, String> {

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
        this(receiver, sender, agent, extractor, new YdbMessageSaver(null, null));
    }

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

            String jsonRequest = new Gson().toJson(Map.of("user_id", from, "text", body));
            // P1 fix: use getResponseWithUsage to capture tokens/latency for observability (step 9)
            AgentClient.AgentResult result = agent.getResponseWithUsage(jsonRequest);
            String response = result.text();
            // Variant 1: threading — preserve user text unchanged via quoted reply, PII stays masked in YDB (ydb-tickets PiiMasker)
            // Email quote is user-owned data, not a leak; YDB and logs keep masked version
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
            sender.sendWithThreading(from, replySubject, response, originalMessageId, body);
            // Log token/latency explicitly for comparison with messages.tokens_in/out (≤10% rule)
            System.out.printf("EMAIL_TOKENS user_id=%s input=%d output=%d latency=%d responseId=%s model=%s%s",
                    from, result.inputTokens(), result.outputTokens(), result.latencyMs(), result.responseId(), result.model(), System.lineSeparator());
            // Persist agent reply with tokens/model directly to YDB (fallback if MCP append-message missed tokens)
            // per step-9 hint: "доставайте их отдельно" — poller side is authoritative source of usage
            try {
                ydbSaver.trySave(from, result, response);
            } catch (Exception e) {
                System.out.println("WARN: ydbSaver failed (fail-open): " + e.getMessage());
            }
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
