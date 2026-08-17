package ru.anseranser.ydb2;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.util.Set;

public class EventDispatcher {

    private static final Set<String> CREATE_TICKET_KEYS = Set.of("user_id", "category", "text");
    private static final Set<String> LIST_MY_TICKETS_KEYS = Set.of("user_id");
    private static final Set<String> APPEND_MESSAGE_KEYS = Set.of("ticket_id", "text", "role");

    /**
     * Parses the event JSON once and returns both the resolved action and the body
     * string that the handler should use to extract parameters.
     *
     * @param event raw event string from Cloud Function / API Gateway / MCP Hub
     * @return DispatchResult with action and body
     * @throws IllegalArgumentException if the event is invalid or action cannot be determined
     */
    public DispatchResult dispatchAndExtract(String event) {
        JsonObject json = parseEvent(event);

        if (json.has("httpMethod")) {
            // API Gateway: body is a nested string or object — unwrap it, then resolve action from inner JSON
            String body = extractBodyFromJson(json);
            Action action = dispatchFromMcpHub(parseEvent(body));
            return new DispatchResult(action, body);
        }

        if (json.has("action")) {
            // Direct invoke: action field present, body is the whole event
            Action action = dispatchFromDirectInvoke(json);
            return new DispatchResult(action, json.toString());
        }

        // MCP Hub: arguments come directly, resolve by key set
        Action action = dispatchFromMcpHub(json);
        return new DispatchResult(action, json.toString());
    }

    private JsonObject parseEvent(String event) {
        try {
            JsonElement element = JsonParser.parseString(event);
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("Event must be a JSON object");
            }
            return element.getAsJsonObject();
        } catch (JsonSyntaxException e) {
            throw new IllegalArgumentException("Invalid JSON: " + e.getMessage(), e);
        }
    }

    private String extractBodyFromJson(JsonObject json) {
        if (json.has("body")) {
            JsonElement body = json.get("body");
            if (body.isJsonPrimitive()) {
                return body.getAsString();
            }
            return body.toString();
        }
        return json.toString();
    }

    private Action dispatchFromDirectInvoke(JsonObject json) {
        String actionName = json.get("action").getAsString();
        try {
            return Action.valueOf(actionName.toUpperCase().replace("-", "_"));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown action: " + actionName);
        }
    }

    private Action dispatchFromMcpHub(JsonObject json) {
        Set<String> keys = json.keySet();

        if (keys.containsAll(CREATE_TICKET_KEYS)) {
            return Action.CREATE_TICKET;
        }

        if (keys.containsAll(APPEND_MESSAGE_KEYS)) {
            return Action.APPEND_MESSAGE;
        }

        if (keys.containsAll(LIST_MY_TICKETS_KEYS)) {
            return Action.LIST_MY_TICKETS;
        }

        throw new IllegalArgumentException("Cannot determine action from keys: " + keys);
    }
}
