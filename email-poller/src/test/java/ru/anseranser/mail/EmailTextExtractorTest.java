package ru.anseranser.mail;

import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link EmailTextExtractor#extractPlainText(Message)}.
 * <p>
 * Uses real {@link MimeMessage} objects to verify correct text extraction
 * from plain-text, HTML, and multipart email messages.
 * <p>
 * Messages are serialized to a byte stream and re-read so that
 * {@link MimeMessage#getContent()} returns the expected types
 * (e.g. {@link Multipart} for multipart messages).
 */
class EmailTextExtractorTest {

    private final EmailTextExtractor extractor = new EmailTextExtractor();

    /**
     * Creates a bare-bones {@link Session} for building test messages.
     */
    private Session testSession() {
        return Session.getInstance(new Properties());
    }

    /**
     * Serializes a {@link MimeMessage} to bytes and re-creates it so that
     * {@link MimeMessage#getContent()} works as it would for messages
     * received from an IMAP server.
     */
    private MimeMessage roundTrip(MimeMessage msg) throws MessagingException, IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        msg.writeTo(bos);
        ByteArrayInputStream bis = new ByteArrayInputStream(bos.toByteArray());
        return new MimeMessage(testSession(), bis);
    }

    // ---------------------------------------------------------------
    // Plain-text tests
    // ---------------------------------------------------------------

    @Test
    void extractPlainText_simpleText() throws Exception {
        MimeMessage msg = new MimeMessage(testSession());
        msg.setFrom(new InternetAddress("sender@example.com"));
        msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse("recv@example.com"));
        msg.setSubject("Plain text email");
        msg.setText("Hello, this is plain text.");

        String result = extractor.extractPlainText(roundTrip(msg));

        assertEquals("Hello, this is plain text.", result);
    }

    // ---------------------------------------------------------------
    // HTML tests
    // ---------------------------------------------------------------

    @Test
    void extractPlainText_htmlContent() throws Exception {
        MimeMessage msg = new MimeMessage(testSession());
        msg.setFrom(new InternetAddress("sender@example.com"));
        msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse("recv@example.com"));
        msg.setSubject("HTML email");
        msg.setContent("<html><body><h1>Hello</h1><p>This is <b>HTML</b>.</p></body></html>", "text/html; charset=utf-8");

        String result = extractor.extractPlainText(roundTrip(msg));

        assertTrue(result.contains("Hello"), "Should contain stripped text from HTML");
        assertTrue(result.contains("HTML"), "Should contain stripped text from HTML");
        assertTrue(result.contains("This is"), "Tags should be stripped");
    }

    // ---------------------------------------------------------------
    // Multipart/alternative (text/plain + text/html)
    // ---------------------------------------------------------------

    @Test
    void extractPlainText_multipartAlternative_prefersPlainText() throws Exception {
        MimeMessage msg = new MimeMessage(testSession());
        msg.setFrom(new InternetAddress("sender@example.com"));
        msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse("recv@example.com"));
        msg.setSubject("Multipart alternative");

        MimeMultipart multipart = new MimeMultipart("alternative");

        MimeBodyPart htmlPart = new MimeBodyPart();
        htmlPart.setContent("<html><body><p>HTML version</p></body></html>", "text/html; charset=utf-8");

        MimeBodyPart textPart = new MimeBodyPart();
        textPart.setText("Plain text version", "utf-8");

        multipart.addBodyPart(htmlPart);
        multipart.addBodyPart(textPart);

        msg.setContent(multipart);

        String result = extractor.extractPlainText(roundTrip(msg));

        assertEquals("Plain text version", result,
                "Should prefer text/plain over text/html in multipart/alternative");
    }

    @Test
    void extractPlainText_multipartAlternative_onlyHtml() throws Exception {
        MimeMessage msg = new MimeMessage(testSession());
        msg.setFrom(new InternetAddress("sender@example.com"));
        msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse("recv@example.com"));
        msg.setSubject("Multipart alternative HTML only");

        MimeMultipart multipart = new MimeMultipart("alternative");

        MimeBodyPart htmlPart = new MimeBodyPart();
        htmlPart.setContent("<html><body><p>Only HTML here</p></body></html>", "text/html; charset=utf-8");

        multipart.addBodyPart(htmlPart);

        msg.setContent(multipart);

        String result = extractor.extractPlainText(roundTrip(msg));

        assertTrue(result.contains("Only HTML here"),
                "Should fall back to HTML text when no text/plain is available");
    }

    // ---------------------------------------------------------------
    // Multipart/mixed with nested multipart/alternative
    // ---------------------------------------------------------------

    @Test
    void extractPlainText_nestedMultipart() throws Exception {
        MimeMessage msg = new MimeMessage(testSession());
        msg.setFrom(new InternetAddress("sender@example.com"));
        msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse("recv@example.com"));
        msg.setSubject("Nested multipart");

        // Outer: multipart/mixed
        MimeMultipart mixed = new MimeMultipart("mixed");

        // Inner: multipart/alternative with text/plain + text/html
        MimeMultipart alternative = new MimeMultipart("alternative");

        MimeBodyPart htmlPart = new MimeBodyPart();
        htmlPart.setContent("<html><body><p>HTML body</p></body></html>", "text/html; charset=utf-8");

        MimeBodyPart textPart = new MimeBodyPart();
        textPart.setText("Nested plain text body", "utf-8");

        alternative.addBodyPart(htmlPart);
        alternative.addBodyPart(textPart);

        MimeBodyPart alternativePart = new MimeBodyPart();
        alternativePart.setContent(alternative);
        mixed.addBodyPart(alternativePart);

        // Add an attachment part (non-text)
        MimeBodyPart attachmentPart = new MimeBodyPart();
        attachmentPart.setFileName("photo.png");
        attachmentPart.setContent("fake-image-bytes", "image/png");
        mixed.addBodyPart(attachmentPart);

        msg.setContent(mixed);

        String result = extractor.extractPlainText(roundTrip(msg));

        assertEquals("Nested plain text body", result,
                "Should recursively find text/plain in nested multipart");
    }

    // ---------------------------------------------------------------
    // Multipart with non-text parts only (image only → null)
    // ---------------------------------------------------------------

    @Test
    void extractPlainText_multipartOnlyNonTextParts() throws Exception {
        MimeMessage msg = new MimeMessage(testSession());
        msg.setFrom(new InternetAddress("sender@example.com"));
        msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse("recv@example.com"));
        msg.setSubject("Multipart with image only");

        MimeMultipart multipart = new MimeMultipart("mixed");

        MimeBodyPart imagePart = new MimeBodyPart();
        imagePart.setFileName("photo.png");
        imagePart.setContent("fake-image-bytes", "image/png");
        multipart.addBodyPart(imagePart);

        msg.setContent(multipart);

        String result = extractor.extractPlainText(roundTrip(msg));

        assertNull(result, "Should return null when no text parts exist");
    }
}
