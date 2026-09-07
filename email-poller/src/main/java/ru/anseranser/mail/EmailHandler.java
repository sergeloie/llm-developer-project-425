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

            String deepestOriginal = extractDeepestQuoted(body);
            if (deepestOriginal != null && !deepestOriginal.isBlank()) {
                System.out.printf("THREAD_DETECTED deepest_original_len=%d preview=%s%s", deepestOriginal.length(), deepestOriginal.substring(0, Math.min(80, deepestOriginal.length())).replace("\n", " "), System.lineSeparator());
            }
            Map<String, Object> reqMap = new java.util.HashMap<>();
            reqMap.put("user_id", from);
            reqMap.put("text", body);
            if (deepestOriginal != null && !deepestOriginal.isBlank()) reqMap.put("original_text", deepestOriginal);
            reqMap.put("thread_text", body);
            String jsonRequest = new Gson().toJson(reqMap);
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
            // Code fix for 3-email thread: if ticket was created on confirmation "Да", overwrite tickets.text with deepest quoted original
            if (result.ticketId() != null && deepestOriginal != null && !deepestOriginal.isBlank()) {
                try {
                    ydbSaver.tryCorrectTicketText(result.ticketId(), deepestOriginal);
                } catch (Exception e) {
                    System.out.println("WARN: tryCorrectTicketText failed (fail-open): " + e.getMessage());
                }
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

    /**
     * Extracts deepest nested quoted block (original first question) from reply body.
     * Counts leading {@code >} markers; max depth is considered original.
     * Used to preserve original user question in 3-email thread where current body is "Да, создай".
     * Visible for testing.
     */
    static String extractDeepestQuoted(String body) {
        if (body == null || body.isBlank()) return null;
        String[] lines = body.split("\\r?\\n");
        int maxDepth = 0;
        java.util.Map<Integer, java.util.List<String>> byDepth = new java.util.HashMap<>();
        for (String line : lines) {
            String t = line;
            int depth = 0;
            int i = 0;
            // count leading ">" with optional spaces
            while (i < t.length()) {
                // skip spaces
                while (i < t.length() && t.charAt(i) == ' ') i++;
                if (i < t.length() && t.charAt(i) == '>') {
                    depth++;
                    i++;
                } else break;
            }
            if (depth == 0) continue;
            String content = t.substring(i).trim();
            // skip empty or "Свернуть" UI markers and agent boilerplate
            if (content.isEmpty()) continue;
            if (content.equalsIgnoreCase("Свернуть")) continue;
            // also skip lines that are just agent's prompt
            byDepth.computeIfAbsent(depth, k -> new java.util.ArrayList<>()).add(content);
            if (depth > maxDepth) maxDepth = depth;
        }
        if (maxDepth == 0) return null;
        java.util.List<String> deepest = byDepth.get(maxDepth);
        if (deepest == null || deepest.isEmpty()) return null;
        // join, but also filter out agent's "У меня нет информации" at deepest? shouldn't be deepest
        String joined = String.join("\n", deepest).trim();
        // Heuristic: if deepest block is very short confirmation, ignore
        if (joined.length() < 15) return null;
        // filter if deepest is just agent's proposal (should not happen at max depth)
        String low = joined.toLowerCase();
        if (low.contains("у меня нет информации") || low.contains("хотите, чтобы я создал тикет")) {
            // this is not original question, find next candidate
            if (byDepth.size() > 1) {
                // try second max
                // actually original should be deepest, but if deepest is agent text due to quoting depth bug, fallback
                return null;
            }
        }
        return joined;
    }
}
