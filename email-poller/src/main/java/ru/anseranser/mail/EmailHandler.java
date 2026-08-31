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
    }

    /**
     * Package-private constructor for unit testing with mocked dependencies.
     */
    EmailHandler(EmailReceiver receiver, SmtpEmailSender sender, AgentClient agent, EmailTextExtractor extractor) {
        this.receiver = receiver;
        this.sender = sender;
        this.agent = agent;
        this.extractor = extractor;
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
        }
        return mailCount + " mail(s) done";
    }

    /**
     * Processes a single email message: extracts text, calls the agent,
     * sends the reply, and marks the message as seen.
     *
     * @return 1 if the message was processed successfully, 0 otherwise
     */
    private int processMessage(Message message, int index) {
        try {
            String from = getAddress(message.getFrom()[0]);
            String subject = message.getSubject();
            System.out.printf("Message #%d, from: %s, subject: %s%s", index + 1, from, subject, System.lineSeparator());

            String body = extractor.extractPlainText(message);
            if (body == null || body.isBlank()) {
                System.out.println("Skipping email with empty or unreadable content from: " + from);
                return 0;
            }

            String jsonRequest = new Gson().toJson(Map.of("user_id", from, "text", body));
            String response = agent.getResponse(jsonRequest);
            sender.send(from, "Agent answer", response);
            receiver.markAsSeen(message);
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
}
