package ru.anseranser.ydb;

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
        System.out.println("[EventDispatcher] dispatchAndExtract: START event=" + event.substring(0, Math.min(event.length(), 100)));
        JsonObject json = parseEvent(event);

        if (json.has("httpMethod")) {
            System.out.println("[EventDispatcher] dispatchAndExtract: API Gateway detected, unwrapping body");
            String body = extractBodyFromJson(json);
            Action action = dispatchFromMcpHub(parseEvent(body));
            System.out.println("[EventDispatcher] dispatchAndExtract: FINISHED action=" + action);
            return new DispatchResult(action, body);
        }

        if (json.has("action")) {
            System.out.println("[EventDispatcher] dispatchAndExtract: Direct invoke detected, action=" + json.get("action").getAsString());
            Action action = dispatchFromDirectInvoke(json);
            System.out.println("[EventDispatcher] dispatchAndExtract: FINISHED action=" + action);
            return new DispatchResult(action, json.toString());
        }

        System.out.println("[EventDispatcher] dispatchAndExtract: MCP Hub detected, resolving by keys");
        Action action = dispatchFromMcpHub(json);
        System.out.println("[EventDispatcher] dispatchAndExtract: FINISHED action=" + action);
        return new DispatchResult(action, json.toString());
    }

    private JsonObject parseEvent(String event) {
        System.out.println("[EventDispatcher] parseEvent: START");
        try {
            JsonElement element = JsonParser.parseString(event);
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("Event must be a JSON object");
            }
            System.out.println("[EventDispatcher] parseEvent: FINISHED");
            return element.getAsJsonObject();
        } catch (JsonSyntaxException e) {
            String causeMessage = e.getCause() != null ? " | caused by: " + e.getCause().getMessage() : "";
            System.out.println("[EventDispatcher] parseEvent: ERROR Invalid JSON: " + e.getMessage() + causeMessage);
            throw new IllegalArgumentException("Invalid JSON: " + e.getMessage(), e);
        }
    }

    private String extractBodyFromJson(JsonObject json) {
        System.out.println("[EventDispatcher] extractBodyFromJson: START");
        String result;
        if (json.has("body")) {
            JsonElement body = json.get("body");
            if (body.isJsonPrimitive()) {
                result = body.getAsString();
            } else {
                result = body.toString();
            }
        } else {
            result = json.toString();
        }
        System.out.println("[EventDispatcher] extractBodyFromJson: FINISHED bodyLength=" + result.length());
        return result;
    }

    private Action dispatchFromDirectInvoke(JsonObject json) {
        String actionName = json.get("action").getAsString();
        System.out.println("[EventDispatcher] dispatchFromDirectInvoke: START actionName=" + actionName);
        try {
            Action action = Action.valueOf(actionName.toUpperCase().replace("-", "_"));
            System.out.println("[EventDispatcher] dispatchFromDirectInvoke: FINISHED action=" + action);
            return action;
        } catch (IllegalArgumentException e) {
            System.out.println("[EventDispatcher] dispatchFromDirectInvoke: ERROR Unknown action: " + actionName);
            throw new IllegalArgumentException("Unknown action: " + actionName);
        }
    }

    private Action dispatchFromMcpHub(JsonObject json) {
        Set<String> keys = json.keySet();
        System.out.println("[EventDispatcher] dispatchFromMcpHub: START keys=" + keys);

        if (keys.containsAll(CREATE_TICKET_KEYS)) {
            System.out.println("[EventDispatcher] dispatchFromMcpHub: FINISHED action=CREATE_TICKET");
            return Action.CREATE_TICKET;
        }

        if (keys.containsAll(APPEND_MESSAGE_KEYS)) {
            System.out.println("[EventDispatcher] dispatchFromMcpHub: FINISHED action=APPEND_MESSAGE");
            return Action.APPEND_MESSAGE;
        }

        if (keys.containsAll(LIST_MY_TICKETS_KEYS)) {
            System.out.println("[EventDispatcher] dispatchFromMcpHub: FINISHED action=LIST_MY_TICKETS");
            return Action.LIST_MY_TICKETS;
        }

        System.out.println("[EventDispatcher] dispatchFromMcpHub: ERROR Cannot determine action from keys: " + keys);
        throw new IllegalArgumentException("Cannot determine action from keys: " + keys);
    }
}
