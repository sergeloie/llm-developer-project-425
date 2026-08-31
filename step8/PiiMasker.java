package ru.anseranser;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PII (Personally Identifiable Information) masking utility.
 * Masks phone numbers, emails, and credit card numbers before writing to YDB.
 * 
 * Mask formats:
 * - Phone: +7 (***) ***-**-NN (last 2 digits preserved)
 * - Email: [email]
 * - Card: ****-****-****-NNNN (last 4 digits preserved)
 */
public class PiiMasker {

    // Phone pattern: +7 XXX XXX-XX-XX or 8 XXX XXX-XX-XX
    private static final Pattern PHONE_PATTERN = Pattern.compile(
        "(?:\\+7|8)[\\s-]?\\(?\\d{3}\\)?[\\s-]?\\d{3}[\\s-]?\\d{2}[\\s-]?\\d{2}"
    );

    // Email pattern
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
        "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}"
    );

    // Card pattern: 16 digits with spaces or dashes
    private static final Pattern CARD_PATTERN = Pattern.compile(
        "\\b\\d{4}[\\s-]?\\d{4}[\\s-]?\\d{4}[\\s-]?\\d{4}\\b"
    );

    /**
     * Masks PII in text before writing to YDB.
     * 
     * @param text input text potentially containing PII
     * @return text with PII masked
     */
    public static String maskPii(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }

        String result = text;

        // Mask phone numbers
        Matcher phoneMatcher = PHONE_PATTERN.matcher(result);
        StringBuffer phoneBuffer = new StringBuffer();
        while (phoneMatcher.find()) {
            String phone = phoneMatcher.group();
            String masked = maskPhone(phone);
            phoneMatcher.appendReplacement(phoneBuffer, Matcher.quoteReplacement(masked));
        }
        phoneMatcher.appendTail(phoneBuffer);
        result = phoneBuffer.toString();

        // Mask emails
        result = EMAIL_PATTERN.matcher(result).replaceAll("[email]");

        // Mask card numbers
        Matcher cardMatcher = CARD_PATTERN.matcher(result);
        StringBuffer cardBuffer = new StringBuffer();
        while (cardMatcher.find()) {
            String card = cardMatcher.group();
            String masked = maskCard(card);
            cardMatcher.appendReplacement(cardBuffer, Matcher.quoteReplacement(masked));
        }
        cardMatcher.appendTail(cardBuffer);
        result = cardBuffer.toString();

        return result;
    }

    /**
     * Checks if text contains any PII.
     * 
     * @param text input text
     * @return true if PII detected
     */
    public static boolean containsPii(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return PHONE_PATTERN.matcher(text).find()
            || EMAIL_PATTERN.matcher(text).find()
            || CARD_PATTERN.matcher(text).find();
    }

    private static String maskPhone(String phone) {
        // Extract last 2 digits
        String digits = phone.replaceAll("[^0-9]", "");
        if (digits.length() < 2) {
            return "+7 (***) ***-**-**";
        }
        String lastTwo = digits.substring(digits.length() - 2);
        return "+7 (***) ***-**-" + lastTwo;
    }

    private static String maskCard(String card) {
        // Extract last 4 digits
        String digits = card.replaceAll("[^0-9]", "");
        if (digits.length() < 4) {
            return "****-****-****-****";
        }
        String lastFour = digits.substring(digits.length() - 4);
        return "****-****-****-" + lastFour;
    }
}
