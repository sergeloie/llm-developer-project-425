package ru.anseranser.ydb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ru.anseranser.json.JsonEventParser;

/**
 * Dispatches Cloud Function events to actions.
 * Supports 3 sources:
 * - direct invoke: {"action":"create-ticket",...}
 * - API Gateway: {"httpMethod":"POST","body":"{...}"} or {"httpMethod":"POST","body":{...}}
 * - MCP Hub: direct keys (user_id+category+text -> create-ticket, etc.)
 */
public class EventDispatcher {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Parse raw event JSON, handling API Gateway wrapping.
     * Returns inner payload JsonNode or original root if not gateway.
     */
    public JsonNode parse(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(input);
            if (root.has("httpMethod") && root.has("body")) {
                JsonNode bodyNode = root.get("body");
                if (bodyNode.isTextual()) {
                    String body = bodyNode.asText();
                    if (body != null && !body.isBlank()) {
                        return MAPPER.readTree(body);
                    }
                } else if (bodyNode.isObject()) {
                    return bodyNode;
                }
            }
            // also delegate to common parser for other cases (it also handles textual body)
            // but we already handled object body above
            return JsonEventParser.parse(input);
        } catch (Exception e) {
            // try fallback to common parser error handling
            System.out.println("ERROR: Failed to parse input: " + e.getMessage());
            return null;
        }
    }

    /**
     * Detect action string from parsed node.
     * Delegates to common JsonEventParser.detectAction.
     */
    public String detectAction(JsonNode root) {
        return JsonEventParser.detectAction(root);
    }

    /**
     * Dispatch raw event string to action string.
     * Returns action name (create-ticket, list-my-tickets, append-message) or null.
     * Throws IllegalArgumentException for invalid JSON or undetectable action.
     */
    public String dispatch(String event) {
        // rev 01 п.4: не логируем содержимое события (сырой PII), только безопасную сводку — длину + тип источника (ниже).
        System.out.println("[EventDispatcher] dispatch: START eventLength=" + (event == null ? -1 : event.length()));
        if (event == null || event.isBlank()) {
            throw new IllegalArgumentException("Empty event");
        }
        JsonNode root;
        try {
            JsonNode outer = MAPPER.readTree(event);
            if (!outer.isObject()) {
                throw new IllegalArgumentException("Event must be a JSON object");
            }
            if (outer.has("httpMethod")) {
                System.out.println("[EventDispatcher] dispatch: API Gateway detected, unwrapping body");
                JsonNode bodyNode = outer.get("body");
                JsonNode inner;
                if (bodyNode == null || bodyNode.isNull()) {
                    inner = outer;
                } else if (bodyNode.isTextual()) {
                    String body = bodyNode.asText();
                    if (body == null || body.isBlank()) {
                        inner = outer;
                    } else {
                        inner = MAPPER.readTree(body);
                    }
                } else if (bodyNode.isObject()) {
                    inner = bodyNode;
                } else {
                    inner = MAPPER.readTree(bodyNode.asText());
                }
                String action;
                if (inner.has("action")) {
                    action = dispatchFromDirectInvoke(inner);
                } else {
                    action = dispatchFromMcpHub(inner);
                }
                System.out.println("[EventDispatcher] dispatch: FINISHED action=" + action);
                return action;
            }
            if (outer.has("action")) {
                String actionName = outer.get("action").asText();
                System.out.println("[EventDispatcher] dispatch: Direct invoke detected, action=" + actionName);
                String result = dispatchFromDirectInvoke(outer);
                System.out.println("[EventDispatcher] dispatch: FINISHED action=" + result);
                return result;
            }
            // MCP Hub
            System.out.println("[EventDispatcher] dispatch: MCP Hub detected, resolving by keys");
            String action = dispatchFromMcpHub(outer);
            System.out.println("[EventDispatcher] dispatch: FINISHED action=" + action);
            return action;
        } catch (IllegalArgumentException e) {
            System.out.println("[EventDispatcher] dispatch: ERROR " + e.getMessage());
            throw e;
        } catch (Exception e) {
            System.out.println("[EventDispatcher] dispatch: ERROR Invalid JSON: " + e.getMessage());
            throw new IllegalArgumentException("Invalid JSON: " + e.getMessage(), e);
        }
    }

    private String dispatchFromDirectInvoke(JsonNode json) {
        System.out.println("[EventDispatcher] dispatchFromDirectInvoke: START actionName=" + json.get("action").asText());
        String actionName = json.get("action").asText();
        String normalized = actionName.toLowerCase().replace("_", "-");
        if ("create-ticket".equals(normalized) || "list-my-tickets".equals(normalized) || "append-message".equals(normalized)) {
            System.out.println("[EventDispatcher] dispatchFromDirectInvoke: FINISHED action=" + normalized);
            return normalized;
        }
        System.out.println("[EventDispatcher] dispatchFromDirectInvoke: ERROR Unknown action: " + actionName);
        throw new IllegalArgumentException("Unknown action: " + actionName);
    }

    private String dispatchFromMcpHub(JsonNode json) {
        System.out.println("[EventDispatcher] dispatchFromMcpHub: START keys=" + keysOf(json));
        String detected = JsonEventParser.detectAction(json);
        if (detected != null) {
            System.out.println("[EventDispatcher] dispatchFromMcpHub: FINISHED action=" + detected);
            return detected;
        }
        System.out.println("[EventDispatcher] dispatchFromMcpHub: ERROR Cannot determine action from keys: " + keysOf(json));
        // ВАЖНО (п.4): исключение НЕ содержит json — иначе сырые данные (текст/PII) утекли бы в лог через e.getMessage()
        throw new IllegalArgumentException("Cannot determine action from keys");
    }

    private String keysOf(JsonNode json) {
        java.util.ArrayList<String> keys = new java.util.ArrayList<>();
        json.fieldNames().forEachRemaining(keys::add);
        return String.join(",", keys);
    }
}
