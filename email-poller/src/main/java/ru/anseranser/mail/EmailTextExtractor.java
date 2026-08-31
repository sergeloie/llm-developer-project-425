package ru.anseranser.mail;

import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import org.jsoup.Jsoup;

import java.io.IOException;

/**
 * Stateless utility that extracts plain-text content from email messages.
 * <p>
 * Handles simple (text/html) and multipart messages.
 * For multipart messages, prefers {@code text/plain} over {@code text/html}.
 */
public class EmailTextExtractor {

    /**
     * Extracts the plain text content from an email message.
     * Handles simple (text/html) and multipart messages.
     * For multipart messages, prefers {@code text/plain} over {@code text/html}.
     *
     * @param message the email message
     * @return extracted plain text, or {@code null} if no readable text content was found
     */
    public String extractPlainText(Message message) throws MessagingException, IOException {
        Object content = message.getContent();
        if (content instanceof Multipart multipart) {
            return extractTextFromMultipart(multipart);
        }
        // Simple (non-multipart) message — text or HTML
        return Jsoup.parse(content.toString()).text();
    }

    /**
     * Recursively traverses a {@link Multipart} body and extracts the first
     * {@code text/plain} part found. Falls back to {@code text/html} if no
     * plain-text part exists.
     *
     * @param multipart the multipart content to scan
     * @return extracted plain text, or {@code null} if no readable text part was found
     */
    private String extractTextFromMultipart(Multipart multipart) throws MessagingException, IOException {
        String plainText = null;

        for (int i = 0; i < multipart.getCount(); i++) {
            var bodyPart = multipart.getBodyPart(i);
            String contentType = bodyPart.getContentType();

            if (bodyPart.getContent() instanceof Multipart nestedMultipart) {
                // Recursively handle nested multipart (e.g. multipart/alternative inside multipart/mixed)
                if (plainText == null) {
                    plainText = extractTextFromMultipart(nestedMultipart);
                }
            } else if (contentType != null && contentType.toLowerCase().startsWith("text/plain")) {
                plainText = bodyPart.getContent().toString();
                break; // text/plain is always preferred, no need to continue
            } else if (contentType != null && contentType.toLowerCase().startsWith("text/html") && plainText == null) {
                plainText = Jsoup.parse(bodyPart.getContent().toString()).text();
                // Don't break — keep looking for text/plain which is preferred
            }
        }

        return plainText;
    }
}
