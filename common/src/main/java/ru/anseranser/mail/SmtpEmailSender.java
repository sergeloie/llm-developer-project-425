package ru.anseranser.mail;

import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.util.Properties;

/**
 * Sends emails via SMTP with SSL.
 * Deduplicated from helpdesk/mail/EmailSender.java and step6/mailsender/EmailSender.java.
 * Creates a new {@link Session} per send call.
 */
public class SmtpEmailSender {

    private final String smtpHost;
    private final String smtpPort;
    private final String smtpUser;
    private final String smtpPassword;
    private final String fromAddress;

    public SmtpEmailSender(String smtpHost, String smtpPort, String smtpUser, String smtpPassword, String fromAddress) {
        this.smtpHost = smtpHost;
        this.smtpPort = smtpPort;
        this.smtpUser = smtpUser;
        this.smtpPassword = smtpPassword;
        this.fromAddress = fromAddress;
    }

    /**
     * Sends a plain-text email.
     *
     * @param to      recipient email address
     * @param subject email subject
     * @param text    email body (plain text)
     */
    public void send(String to, String subject, String text) throws MessagingException {
        sendWithThreading(to, subject, text, null, null);
    }

    /**
     * Sends a plain-text email with threading headers and quoted original.
     * Variant 1: preserves user text unchanged (quoted with "> "), but PII masking
     * stays in YDB layer (ydb-tickets PiiMasker) — email quote is user-owned data,
     * not a leak. Headers In-Reply-To/References keep mail client threading.
     *
     * @param to         recipient
     * @param subject    reply subject (already "Re: ...")
     * @param text       agent answer (plain text, not masked)
     * @param inReplyTo  original Message-ID (may be null)
     * @param quotedOriginal original user body to quote (may be null) — quoted as "> " lines
     */
    public void sendWithThreading(String to, String subject, String text, String inReplyTo, String quotedOriginal) throws MessagingException {
        Properties props = new Properties();
        props.put("mail.smtp.host", smtpHost);
        props.put("mail.smtp.port", smtpPort);
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.ssl.enable", "true");
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "10000");
        props.put("mail.smtp.writetimeout", "10000");

        Session session = Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(smtpUser, smtpPassword);
            }
        });
        // S6 fix: SMTP debug только по флагу SMTP_DEBUG=true (иначе спам в логах)
        String debugFlag = System.getenv("SMTP_DEBUG");
        boolean debug = "true".equalsIgnoreCase(debugFlag) || "1".equals(debugFlag);
        if (debug) {
            session.setDebug(true);
            session.setDebugOut(System.out);
        }

        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress(fromAddress));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
        message.setSubject(subject);
        // Variant 1 threading: keep user text unchanged, PII stays in YDB (masked there), email quote is user-owned
        if (inReplyTo != null && !inReplyTo.isBlank()) {
            message.setHeader("In-Reply-To", inReplyTo);
            message.setHeader("References", inReplyTo);
        }
        String fullBody = text;
        if (quotedOriginal != null && !quotedOriginal.isBlank()) {
            // Quote original unchanged (preserve user text), but do not log raw PII here
            String quoted = quotedOriginal.replace("\r\n", "\n").replace("\r", "\n");
            quoted = quoted.replace("\n", "\n> ");
            fullBody = text + "\n\n> " + quoted;
        }
        message.setText(fullBody);

        Transport.send(message);

        System.out.printf("Successfully sent to: %s%s", to, System.lineSeparator());
    }
}
