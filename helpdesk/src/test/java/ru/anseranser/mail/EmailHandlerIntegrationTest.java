package ru.anseranser.mail;

import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.search.FlagTerm;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Full-stack integration test for the {@code ru.anseranser.mail} package.
 * <p>
 * This test performs real interactions:
 * <ol>
 *   <li>Sends a test email via SMTP to the helpdesk mailbox</li>
 *   <li>Runs {@link EmailHandler} which reads unread emails via IMAP,
 *       extracts text, calls the LLM agent, and sends a reply via SMTP</li>
 *   <li>Verifies the reply email was delivered</li>
 *   <li>Verifies the original message was marked as seen</li>
 * </ol>
 * <p>
 * <b>All credentials are read directly from environment variables</b> (see {@code .env}):
 * <ul>
 *   <li>{@code IMAP_HOST} – IMAP server hostname</li>
 *   <li>{@code IMAP_USER} – IMAP login</li>
 *   <li>{@code IMAP_PASSWORD} – IMAP password</li>
 *   <li>{@code SMTP_HOST} – SMTP server hostname</li>
 *   <li>{@code SMTP_PORT} – SMTP port (e.g. 465)</li>
 *   <li>{@code SMTP_USER} – SMTP login</li>
 *   <li>{@code SMTP_PASSWORD} – SMTP password</li>
 *   <li>{@code HELPDESK_MAILBOX} – helpdesk "from" address</li>
 *   <li>{@code YANDEX_API_KEY} – Yandex API key for the LLM agent</li>
 *   <li>{@code AGENT_ID} – LLM agent ID</li>
 *   <li>{@code ORGANIZATION_ID} – Yandex Cloud organization ID</li>
 *   <li>{@code MCP_SERVER_URL} – MCP server URL for the agent tools</li>
 * </ul>
 * <p>
 * Run with:<br>
 * {@code mvn test -pl helpdesk -Dtest=EmailHandlerIntegrationTest -DfailIfNoTests=false}
 * <p>
 * Or from IntelliJ: right-click the class → Run.
 * <p>
 * This test is {@code @Disabled} by default because it requires live external services.
 * Remove the annotation or use {@code -Dsurefire.failIfNoSpecifiedTests=false} override to run.
 */
@DisplayName("EmailHandler Integration Test – real IMAP/SMTP/LLM")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfEnvironmentVariable(named = "IMAP_HOST", matches = ".+")
class EmailHandlerIntegrationTest {

    // ── Environment variables ──────────────────────────────────────────

    private static String env(String key) {
        String val = System.getenv(key);
        assertNotNull(val, "Environment variable " + key + " is not set");
        return val;
    }

    private static String imapHost()     { return env("IMAP_HOST"); }
    private static String imapUser()     { return env("IMAP_USER"); }
    private static String imapPassword() { return env("IMAP_PASSWORD"); }

    private static String smtpHost()     { return env("SMTP_HOST"); }
    private static String smtpPort()     { return env("SMTP_PORT"); }
    private static String smtpUser()     { return env("SMTP_USER"); }
    private static String smtpPassword() { return env("SMTP_PASSWORD"); }
    private static String helpdeskMailbox() { return env("HELPDESK_MAILBOX"); }

    private static String apiKey()          { return env("YANDEX_API_KEY"); }
    private static String agentId()         { return env("AGENT_ID"); }
    private static String organizationId()  { return env("ORGANIZATION_ID"); }
    private static String mcpServerUrl()    { return env("MCP_SERVER_URL"); }

    // ── Constants ──────────────────────────────────────────────────────

    private static final String TEST_MARKER = "INTEGRATION-TEST-" + UUID.randomUUID();
    private static final String SUBJECT     = "Helpdesk Integration Test – " + TEST_MARKER;
    private static final String BODY        = "Hello! This is an automated integration test message. "
            + "Please respond with a short confirmation that you received this message. "
            + "Marker: " + TEST_MARKER;

    // ── Shared state ───────────────────────────────────────────────────

    private static String replySubject;
    private static String replyBody;

    // ── Test: send test email → process via handler → verify reply ─────

    @Test
    @Order(1)
    @DisplayName("Full pipeline: SMTP send → IMAP receive → LLM agent → SMTP reply → verify")
    void fullPipeline_sendEmail_receiveViaAgent_verifyReply() throws Exception {

        // ── Step 1: Send a test email to the helpdesk mailbox ──────────
        System.out.println("=== Step 1: Sending test email to " + helpdeskMailbox() + " ===");
        sendTestEmail(helpdeskMailbox(), SUBJECT, BODY);
        System.out.println("Test email sent. Waiting 5 seconds for delivery...");
        Thread.sleep(5000);

        // ── Step 2: Verify the test email is in the inbox as unread ────
        System.out.println("=== Step 2: Verifying test email arrived in inbox ===");
        Message testMessage = findUnreadBySubject(imapUser(), SUBJECT);
        assertNotNull(testMessage,
                "Test email with subject '" + SUBJECT + "' not found in inbox. "
                + "Check SMTP delivery or spam folder.");
        System.out.println("Test email found in inbox.");

        // ── Step 3: Run the EmailHandler (real IMAP → LLM → SMTP) ──────
        System.out.println("=== Step 3: Running EmailHandler ===");
        EmailHandler handler = new EmailHandler();
        try {
            String result = handler.handle(null, null);
            System.out.println("Handler returned: " + result);
            // At least 1 mail processed
            assertTrue(result.startsWith("1") || result.startsWith("2"),
                    "Expected at least 1 mail processed, got: " + result);
        } finally {
            handler.disconnect();
        }

        // ── Step 4: Wait for reply email to arrive ─────────────────────
        System.out.println("=== Step 4: Waiting for agent reply email (up to 60s) ===");
        Message replyMessage = waitForReply(imapUser(), "Agent answer", TEST_MARKER, 60);
        assertNotNull(replyMessage,
                "Reply email not found in inbox within timeout. "
                + "Check SMTP sending, agent response, or IMAP delivery.");
        replySubject = replyMessage.getSubject();
        replyBody = new EmailTextExtractor().extractPlainText(replyMessage);
        System.out.println("Reply received! Subject: " + replySubject);
        System.out.println("Reply body (first 500 chars): " +
                (replyBody != null ? replyBody.substring(0, Math.min(500, replyBody.length())) : "null"));

        // ── Step 5: Verify the reply contains meaningful content ───────
        System.out.println("=== Step 5: Verifying reply content ===");
        assertNotNull(replyBody, "Reply body is null");
        assertFalse(replyBody.isBlank(), "Reply body is blank");
        assertTrue(replyBody.length() > 10,
                "Reply body too short (likely an error): " + replyBody);

        // ── Step 6: Verify the original test email was marked as seen ──
        System.out.println("=== Step 6: Verifying original email was marked as seen ===");
        boolean isSeen = isMessageSeen(imapUser(), SUBJECT);
        assertTrue(isSeen, "Original test email was not marked as SEEN");
        System.out.println("Original email confirmed as SEEN.");

        System.out.println("=== Integration test PASSED ===");
    }

    // ── Helper: send an email via SMTP ──────────────────────────────────

    private static void sendTestEmail(String to, String subject, String body) throws Exception {
        Properties props = new Properties();
        props.put("mail.smtp.host", smtpHost());
        props.put("mail.smtp.port", smtpPort());
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.ssl.enable", "true");
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "10000");
        props.put("mail.smtp.writetimeout", "10000");

        Session session = Session.getInstance(props, new jakarta.mail.Authenticator() {
            @Override
            protected jakarta.mail.PasswordAuthentication getPasswordAuthentication() {
                return new jakarta.mail.PasswordAuthentication(smtpUser(), smtpPassword());
            }
        });
        session.setDebugOut(System.out);

        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress(smtpUser(), "Integration Test"));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
        message.setSubject(subject);
        message.setText(body);
        message.setHeader("X-Integration-Test", TEST_MARKER);

        Transport.send(message);
        System.out.println("SMTP: message sent to " + to);
    }

    // ── Helper: find an unread message by subject via IMAP ──────────────

    private static Message findUnreadBySubject(String user, String subject) throws Exception {
        Store store = connectImap();
        try {
            Folder inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);
            try {
                Message[] unread = inbox.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false));
                for (Message msg : unread) {
                    if (subject.equals(msg.getSubject())) {
                        return msg;
                    }
                }
                return null;
            } finally {
                inbox.close(false);
            }
        } finally {
            store.close();
        }
    }

    // ── Helper: wait for reply email with timeout ───────────────────────

    private static Message waitForReply(String user, String expectedSubject,
                                         String marker, int timeoutSeconds) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;

        while (System.currentTimeMillis() < deadline) {
            Message[] messages = fetchAllUnread(user);
            for (Message msg : messages) {
                String subj = msg.getSubject();
                // The reply subject is "Agent answer" and the body contains our marker
                if (expectedSubject.equals(subj)) {
                    String text = new EmailTextExtractor().extractPlainText(msg);
                    if (text != null && text.contains(marker)) {
                        return msg;
                    }
                }
            }
            System.out.println("Waiting for reply... (" +
                    ((deadline - System.currentTimeMillis()) / 1000) + "s remaining)");
            Thread.sleep(5000);
        }
        return null;
    }

    // ── Helper: fetch all unread messages ───────────────────────────────

    private static Message[] fetchAllUnread(String user) throws Exception {
        Store store = connectImap();
        try {
            Folder inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);
            try {
                return inbox.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false));
            } finally {
                inbox.close(false);
            }
        } finally {
            store.close();
        }
    }

    // ── Helper: check if a message with given subject was seen ──────────

    private static boolean isMessageSeen(String user, String subject) throws Exception {
        Store store = connectImap();
        try {
            Folder inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);
            try {
                // Search for SEEN messages
                Message[] seen = inbox.search(new FlagTerm(new Flags(Flags.Flag.SEEN), true));
                for (Message msg : seen) {
                    if (subject.equals(msg.getSubject())) {
                        return true;
                    }
                }
                return false;
            } finally {
                inbox.close(false);
            }
        } finally {
            store.close();
        }
    }

    // ── Helper: connect to IMAP ────────────────────────────────────────

    private static Store connectImap() throws MessagingException {
        Properties properties = new Properties();
        properties.setProperty("mail.store.protocol", "imaps");
        properties.setProperty("mail.imaps.host", imapHost());
        properties.setProperty("mail.imaps.port", "993");
        properties.setProperty("mail.imaps.ssl.enable", "true");

        Session session = Session.getInstance(properties);
        Store store = session.getStore("imaps");
        store.connect(imapHost(), 993, imapUser(), imapPassword());
        return store;
    }
}
