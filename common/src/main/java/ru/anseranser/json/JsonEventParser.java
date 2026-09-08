package ru.anseranser.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Parses Cloud Function event JSON and detects action type.
 * Logic extracted from ydb2/Handler.java parseInput/detectAction.
 */
public final class JsonEventParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonEventParser() {
    }

    /**
     * Parses input JSON string, handling API Gateway wrapping.
     *
     * @param input raw event JSON
     * @return parsed JsonNode or null if empty/invalid
     */
    public static JsonNode parse(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(input);
            if (root.has("httpMethod") && root.has("body")) {
                String body = root.get("body").asText();
                if (body != null && !body.isBlank()) {
                    return MAPPER.readTree(body);
                }
            }
            return root;
        } catch (Exception e) {
            System.out.println("ERROR: Failed to parse input: " + e.getMessage());
            return null;
        }
    }

    /**
     * Detects action from parsed event.
     * Supports explicit {@code action} field and key-set heuristics.
     *
     * @param root parsed event node
     * @return action string (create-ticket, list-my-tickets, append-message) or null
     */
    public static String detectAction(JsonNode root) {
        if (root == null) {
            return null;
        }
        if (root.has("action")) {
            return root.get("action").asText();
        }
        if (root.has("user_id") && root.has("category") && root.has("text")) {
            return "create-ticket";
        }
        if (root.has("user_id") && !root.has("category") && !root.has("text") && !root.has("ticket_id")) {
            return "list-my-tickets";
        }
        if (root.has("ticket_id") && root.has("role") && root.has("text")) {
            return "append-message";
        }
        return null;
    }
}
