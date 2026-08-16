package ru.anseranser.ydb;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.util.Set;

public class EventDispatcher {

    public enum Action {
        CREATE_TICKET,
        LIST_MY_TICKETS,
        APPEND_MESSAGE
    }

    private static final Set<String> CREATE_TICKET_KEYS = Set.of("user_id", "category", "text");
    private static final Set<String> LIST_MY_TICKETS_KEYS = Set.of("user_id");
    private static final Set<String> APPEND_MESSAGE_KEYS = Set.of("ticket_id", "text", "role");

    public Action resolve(String event) {
        JsonObject json = parseEvent(event);

        if (json.has("httpMethod")) {
            return resolveFromGateway(json);
        }

        if (json.has("action")) {
            return resolveFromDirectInvoke(json);
        }

        return resolveFromMcpHub(json);
    }

    public String extractBody(String event) {
        JsonObject json = parseEvent(event);
        if (json.has("body")) {
            JsonElement body = json.get("body");
            if (body.isJsonPrimitive()) {
                return body.getAsString();
            }
            return body.toString();
        }
        return event;
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

    private Action resolveFromGateway(JsonObject json) {
        String body = extractBodyFromJson(json);
        return resolveFromMcpHub(parseEvent(body));
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

    private Action resolveFromDirectInvoke(JsonObject json) {
        String actionName = json.get("action").getAsString();
        try {
            return Action.valueOf(actionName.toUpperCase().replace("-", "_"));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown action: " + actionName);
        }
    }

    private Action resolveFromMcpHub(JsonObject json) {
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
