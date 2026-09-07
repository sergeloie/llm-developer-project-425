package ru.anseranser.ydb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.anseranser.json.JsonEventParser;
import ru.anseranser.pii.PiiMasker;
import ru.anseranser.security.InjectionClassifier;
import yandex.cloud.sdk.functions.Context;
import yandex.cloud.sdk.functions.YcFunction;

/**
 * Cloud Function handler for ydb-tickets.
 * Supports create-ticket, list-my-tickets, append-message via 3 event sources.
 */
public class YdbTicketsHandler implements YcFunction<String, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final EventDispatcher dispatcher;
    private volatile YdbClient ydbClient;

    public YdbTicketsHandler() {
        this.dispatcher = new EventDispatcher();
        this.ydbClient = null;
    }

    // Package-private for tests
    YdbTicketsHandler(YdbClient ydbClient, EventDispatcher dispatcher) {
        this.ydbClient = ydbClient;
        this.dispatcher = dispatcher;
    }

    // Package-private static factory for tests (alternative)
    static YdbTicketsHandler createForTest(YdbClient ydbClient, EventDispatcher dispatcher) {
        return new YdbTicketsHandler(ydbClient, dispatcher);
    }

    private YdbClient getOrCreateYdbClient() {
        if (ydbClient == null) {
            synchronized (this) {
                if (ydbClient == null) {
                    System.out.println("[YdbTicketsHandler] getOrCreateYdbClient: Initializing YDB client...");
                    try {
                        ydbClient = new YdbClient();
                        System.out.println("[YdbTicketsHandler] getOrCreateYdbClient: YDB client initialized successfully");
                    } catch (Exception e) {
                        System.out.println("[YdbTicketsHandler] getOrCreateYdbClient: ERROR YDB not configured: " + e.getMessage());
                        throw e;
                    }
                }
            }
        }
        return ydbClient;
    }

    @Override
    public String handle(String input, Context context) {
        System.out.println("[YdbTicketsHandler] handle: START event=" + preview(input));
        try {
            JsonNode root = JsonEventParser.parse(input);
            // Fallback: if JsonEventParser returned null due to gateway body being object, try dispatcher parse
            if (root == null) {
                root = dispatcher.parse(input);
            } else {
                // If original input was API Gateway, JsonEventParser already unwrapped textual body,
                // but for object body it returns root with httpMethod. So check and re-parse via dispatcher.
                try {
                    JsonNode outer = MAPPER.readTree(input);
                    if (outer.has("httpMethod") && outer.has("body") && outer.get("body").isObject()) {
                        root = outer.get("body");
                    }
                } catch (Exception ignored) {
                }
            }
            if (root == null) {
                return errorResponse("Empty or invalid input");
            }

            String action = JsonEventParser.detectAction(root);
            if (action == null) {
                // try dispatcher as fallback
                try {
                    action = dispatcher.dispatch(input);
                } catch (IllegalArgumentException e) {
                    return errorResponse("Cannot detect action from input");
                }
            }

            System.out.println("[YdbTicketsHandler] handle: Dispatch resolved action=" + action);

            String result = switch (action) {
                case "create-ticket" -> handleCreateTicket(root);
                case "list-my-tickets" -> handleListMyTickets(root);
                case "append-message" -> handleAppendMessage(root);
                case "update-ticket", "update-ticket-text" -> handleUpdateTicketText(root);
                default -> errorResponse("Unknown action: " + action);
            };

            System.out.println("[YdbTicketsHandler] handle: FINISHED action=" + action);
            return result;
        } catch (Exception e) {
            System.out.println("[YdbTicketsHandler] handle: ERROR " + e.getMessage() + " | caused by: " + e.getCause());
            return errorResponse(e.getMessage());
        }
    }

    private String handleCreateTicket(JsonNode root) {
        String userId = getText(root, "user_id");
        String category = root.has("category") ? root.get("category").asText() : "bug";
        String text = getText(root, "text");

        if (userId == null || text == null) {
            return errorResponse("Missing required fields for create-ticket");
        }

        // Injection classification before masking (use raw text)
        String classification = InjectionClassifier.classify(text);
        if ("injection".equals(classification)) {
            System.err.println("ALERT_INJECTION_BLOCKED: user_id=" + userId + ", text_length=" + text.length());
            return errorResponse("Запрос заблокирован модерацией");
        }
        if ("off-topic".equals(classification)) {
            System.out.println("INFO: off-topic ticket: user_id=" + userId + ", text_length=" + text.length());
        }

        String masked = PiiMasker.maskPii(text);
        boolean hasPii = PiiMasker.containsPii(text);
        System.out.println("INFO: create-ticket user_id=" + userId + ", text_length=" + text.length() + ", has_pii=" + hasPii);

        YdbClient client = getOrCreateYdbClient();
        String response = client.createTicket(userId, category, masked);
        // Extract ticket_id for safe log
        try {
            JsonNode resp = MAPPER.readTree(response);
            String ticketId = resp.has("ticket_id") ? resp.get("ticket_id").asText() : "unknown";
            System.out.println("INFO: Ticket created: ticket_id=" + ticketId + ", has_pii=" + hasPii);
        } catch (Exception ignored) {
        }
        return response;
    }

    private String handleListMyTickets(JsonNode root) {
        String userId = getText(root, "user_id");
        if (userId == null) {
            return errorResponse("Missing user_id for list-my-tickets");
        }
        System.out.println("INFO: list-my-tickets user_id=" + userId);
        YdbClient client = getOrCreateYdbClient();
        return client.listMyTickets(userId);
    }

    private String handleAppendMessage(JsonNode root) {
        String ticketId = getText(root, "ticket_id");
        String role = getText(root, "role");
        String text = getText(root, "text");
        if (ticketId == null || role == null || text == null) {
            return errorResponse("Missing required fields for append-message");
        }
        String model = root.has("model") ? root.get("model").asText() : "";
        long tokensIn = root.has("tokens_in") ? root.get("tokens_in").asLong() : 0L;
        long tokensOut = root.has("tokens_out") ? root.get("tokens_out").asLong() : 0L;
        int latencyMs = root.has("latency_ms") ? root.get("latency_ms").asInt() : 0;

        String masked = PiiMasker.maskPii(text);
        boolean hasPii = PiiMasker.containsPii(text);
        System.out.println("INFO: append-message ticket_id=" + ticketId + ", text_length=" + text.length() + ", has_pii=" + hasPii);

        YdbClient client = getOrCreateYdbClient();
        return client.appendMessage(ticketId, role, masked, model, tokensIn, tokensOut, latencyMs);
    }

    private String handleUpdateTicketText(JsonNode root) {
        String ticketId = getText(root, "ticket_id");
        if (ticketId == null) ticketId = getText(root, "id");
        String text = getText(root, "text");
        if (ticketId == null || text == null) return errorResponse("Missing ticket_id/text for update-ticket-text");
        String masked = PiiMasker.maskPii(text);
        boolean hasPii = PiiMasker.containsPii(text);
        System.out.println("INFO: update-ticket-text ticket_id=" + ticketId + ", text_length=" + text.length() + ", has_pii=" + hasPii);
        YdbClient client = getOrCreateYdbClient();
        return client.updateTicketText(ticketId, masked);
    }

    private String getText(JsonNode root, String field) {
        if (root.has(field) && !root.get(field).isNull()) {
            return root.get(field).asText();
        }
        return null;
    }

    private String errorResponse(String message) {
        try {
            ObjectNode result = MAPPER.createObjectNode();
            result.put("error", message);
            return MAPPER.writeValueAsString(result);
        } catch (Exception e) {
            return "{\"error\":\"" + message.replace("\"", "\\\"") + "\"}";
        }
    }

    private String preview(String s) {
        if (s == null) return "null";
        return s.length() > 100 ? s.substring(0, 100) + "..." : s;
    }
}
