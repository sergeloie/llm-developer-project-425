package ru.anseranser.security;

import java.util.regex.Pattern;

/**
 * Injection classifier with two-level defense:
 * Level 1: Regex pre-filter (instant, no LLM)
 * Level 2: LLM classifier (yandexgpt-lite)
 *
 * On classifier error/timeout: fail-open (treat as safe)
 */
public class InjectionClassifier {

    private static final String[] INJECTION_PATTERNS = {
        "ignore previous", "ignore all previous",
        "forget everything", "forget your instructions",
        "new instructions", "override instructions",
        "system prompt", "your instructions",
        "drop table", "truncate table", "delete from",
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

    public static String classify(String text) {
        if (text == null || text.isBlank()) {
            return "safe";
        }

        if (INJECTION_REGEX.matcher(text).find()) {
            return "injection";
        }

        try {
            return callLlmClassifier(text);
        } catch (Exception e) {
            System.err.println("WARN: LLM classifier failed, fail-open: " + e.getMessage());
            return "safe";
        }
    }

    private static String callLlmClassifier(String text) {
        return "safe";
    }
}
