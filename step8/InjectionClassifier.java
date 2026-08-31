package ru.anseranser;

import java.util.regex.Pattern;

/**
 * Injection classifier with two-level defense:
 * Level 1: Regex pre-filter (instant, no LLM)
 * Level 2: LLM classifier (yandexgpt-lite)
 * 
 * On classifier error/timeout: fail-open (treat as safe)
 */
public class InjectionClassifier {

    // Regex patterns for known injection attempts
    private static final String[] INJECTION_PATTERNS = {
        // English
        "ignore previous", "ignore all previous",
        "forget everything", "forget your instructions",
        "new instructions", "override instructions",
        "system prompt", "your instructions",
        "drop table", "truncate table", "delete from",
        
        // Russian
        "проигнорируй предыдущие", "проигнорируй все предыдущие",
        "забудь всё", "забудь свои инструкции",
        "новые инструкции", "переопредели инструкции",
        "системный промпт", "твои инструкции",
        "удали все тикеты", "удали все записи",
        "очисти таблицу"
    };

    private static final Pattern INJECTION_REGEX = Pattern.compile(
        String.join("|", INJECTION_PATTERNS),
        Pattern.CASE_INSENSITIVE
    );

    /**
     * Classifies text into: safe, injection, or off-topic.
     * 
     * @param text input text to classify
     * @return classification result
     */
    public static String classify(String text) {
        if (text == null || text.isBlank()) {
            return "safe";
        }

        // Level 1: Regex pre-filter (instant)
        if (INJECTION_REGEX.matcher(text).find()) {
            return "injection";
        }

        // Level 2: LLM classifier (yandexgpt-lite)
        // This would be called via API in production
        // For now, return safe (fail-open on missing LLM)
        try {
            return callLlmClassifier(text);
        } catch (Exception e) {
            System.err.println("WARN: LLM classifier failed, fail-open: " + e.getMessage());
            return "safe"; // Fail-open
        }
    }

    /**
     * Calls yandexgpt-lite for classification.
     * In production, this would call the AI Studio API.
     * 
     * @param text text to classify
     * @return classification result
     */
    private static String callLlmClassifier(String text) {
        // TODO: Implement actual LLM call via AI Studio API
        // Example prompt:
        // "Ты — классификатор текста. Определи категорию: safe, injection, off-topic.
        //  Верни ТОЛЬКО одно слово."
        
        // Placeholder - in production, call yandexgpt-lite
        // For now, assume safe (fail-open)
        return "safe";
    }
}
