package ru.anseranser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import tech.ydb.auth.AuthProvider;
import tech.ydb.auth.iam.CloudAuthHelper;
import tech.ydb.core.grpc.GrpcTransport;
import tech.ydb.table.Session;
import tech.ydb.table.TableClient;
import tech.ydb.table.query.Params;
import tech.ydb.table.result.ResultSetReader;
import tech.ydb.table.transaction.TxControl;
import tech.ydb.table.values.PrimitiveValue;
import yandex.cloud.sdk.functions.Context;
import yandex.cloud.sdk.functions.YcFunction;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public class Handler implements YcFunction<String, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TableClient TABLE_CLIENT;

    static {
        String endpoint = System.getenv("YDB_ENDPOINT");
        String database = System.getenv("YDB_DATABASE");

        System.out.println("INFO: Initializing YDB Client...");
        System.out.println("INFO: YDB_ENDPOINT = " + endpoint);
        System.out.println("INFO: YDB_DATABASE = " + database);

        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalStateException("YDB_ENDPOINT env var is not set");
        }
        if (database == null || database.isBlank() || !database.startsWith("/")) {
            throw new IllegalStateException("YDB_DATABASE env var is not set or invalid");
        }

        try {
            AuthProvider authProvider = CloudAuthHelper.getAuthProviderFromEnviron();
            GrpcTransport transport = GrpcTransport.forEndpoint(endpoint, database)
                    .withAuthProvider(authProvider)
                    .build();
            TABLE_CLIENT = TableClient.newClient(transport).build();
            System.out.println("INFO: YDB Client initialized successfully.");
        } catch (Exception e) {
            System.err.println("FATAL: Failed to initialize YDB Client.");
            e.printStackTrace();
            throw new IllegalStateException("YDB Client initialization failed: " + e.getMessage(), e);
        }
    }

    @Override
    public String handle(String input, Context context) {
        try {
            JsonNode root = parseInput(input);
            if (root == null) {
                return errorResponse("Empty or invalid input");
            }

            String action = detectAction(root);
            if (action == null) {
                return errorResponse("Cannot detect action from input");
            }

            System.out.println("INFO: Detected action: " + action);

            return switch (action) {
                case "create-ticket" -> handleCreateTicket(root);
                case "list-my-tickets" -> handleListMyTickets(root);
                case "append-message" -> handleAppendMessage(root);
                default -> errorResponse("Unknown action: " + action);
            };

        } catch (Exception e) {
            System.err.println("ERROR: " + e.getMessage());
            e.printStackTrace();
            return errorResponse(e.getMessage());
        }
    }

    private JsonNode parseInput(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }

        try {
            JsonNode root = MAPPER.readTree(input);

            // HTTP via API Gateway: {"httpMethod": "POST", "body": "<JSON-string>", ...}
            if (root.has("httpMethod") && root.has("body")) {
                String body = root.get("body").asText();
                if (body != null && !body.isBlank()) {
                    return MAPPER.readTree(body);
                }
            }

            return root;
        } catch (Exception e) {
            System.err.println("ERROR: Failed to parse input: " + e.getMessage());
            return null;
        }
    }

    private String detectAction(JsonNode root) {
        // Direct invoke or MCP Hub: action is specified explicitly
        if (root.has("action")) {
            return root.get("action").asText();
        }

        // MCP Hub: arguments come directly as event - detect by key set
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

    private String handleCreateTicket(JsonNode root) {
        String ticketId = root.has("ticket_id") ? root.get("ticket_id").asText() : UUID.randomUUID().toString();
        String userId = root.get("user_id").asText();
        String category = root.has("category") ? root.get("category").asText() : "bug";
        String text = root.get("text").asText();
        Instant now = Instant.now();

        try (Session session = TABLE_CLIENT.createSession(Duration.ofSeconds(10)).join().getValue()) {
            String upsertTicketQuery =
                    "DECLARE $id AS Utf8; " +
                    "DECLARE $user_id AS Utf8; " +
                    "DECLARE $category AS Utf8; " +
                    "DECLARE $status AS Utf8; " +
                    "DECLARE $text AS Utf8; " +
                    "DECLARE $created_at AS Timestamp; " +
                    "DECLARE $updated_at AS Timestamp; " +
                    "UPSERT INTO tickets (id, user_id, category, status, text, created_at, updated_at) " +
                    "VALUES ($id, $user_id, $category, $status, $text, $created_at, $updated_at);";

            Params ticketParams = Params.of(
                    "$id", PrimitiveValue.newText(ticketId),
                    "$user_id", PrimitiveValue.newText(userId),
                    "$category", PrimitiveValue.newText(category),
                    "$status", PrimitiveValue.newText("open"),
                    "$text", PrimitiveValue.newText(text),
                    "$created_at", PrimitiveValue.newTimestamp(now),
                    "$updated_at", PrimitiveValue.newTimestamp(now)
            );

            session.executeDataQuery(upsertTicketQuery, TxControl.serializableRw().setCommitTx(true), ticketParams).join();
            System.out.println("INFO: Ticket created: " + ticketId);

            String messageId = UUID.randomUUID().toString();
            String upsertMessageQuery =
                    "DECLARE $id AS Utf8; " +
                    "DECLARE $ticket_id AS Utf8; " +
                    "DECLARE $role AS Utf8; " +
                    "DECLARE $text AS Utf8; " +
                    "DECLARE $model AS Utf8; " +
                    "DECLARE $tokens_in AS Uint64; " +
                    "DECLARE $tokens_out AS Uint64; " +
                    "DECLARE $latency_ms AS Uint32; " +
                    "DECLARE $created_at AS Timestamp; " +
                    "UPSERT INTO messages (id, ticket_id, role, text, model, tokens_in, tokens_out, latency_ms, created_at) " +
                    "VALUES ($id, $ticket_id, $role, $text, $model, $tokens_in, $tokens_out, $latency_ms, $created_at);";

            Params messageParams = Params.of(
                    "$id", PrimitiveValue.newText(messageId),
                    "$ticket_id", PrimitiveValue.newText(ticketId),
                    "$role", PrimitiveValue.newText("user"),
                    "$text", PrimitiveValue.newText(text),
                    "$model", PrimitiveValue.newText(""),
                    "$tokens_in", PrimitiveValue.newUint64(0),
                    "$tokens_out", PrimitiveValue.newUint64(0),
                    "$latency_ms", PrimitiveValue.newUint32(0),
                    "$created_at", PrimitiveValue.newTimestamp(now)
            );

            session.executeDataQuery(upsertMessageQuery, TxControl.serializableRw().setCommitTx(true), messageParams).join();
            System.out.println("INFO: First message created for ticket: " + ticketId);

            ObjectNode result = MAPPER.createObjectNode();
            result.put("ticket_id", ticketId);
            result.put("created_at", now.toString());
            return MAPPER.writeValueAsString(result);

        } catch (Exception e) {
            System.err.println("ERROR: create-ticket failed: " + e.getMessage());
            return errorResponse(e.getMessage());
        }
    }

    private String handleListMyTickets(JsonNode root) {
        String userId = root.get("user_id").asText();

        try (Session session = TABLE_CLIENT.createSession(Duration.ofSeconds(10)).join().getValue()) {
            String query =
                    "DECLARE $user_id AS Utf8; " +
                    "SELECT id, status, category, text, created_at FROM tickets WHERE user_id = $user_id;";

            Params params = Params.of(
                    "$user_id", PrimitiveValue.newText(userId)
            );

            ResultSetReader reader = session.executeDataQuery(query, TxControl.serializableRw().setCommitTx(true), params)
                    .join().getValue().getResultSet(0);

            ArrayNode tickets = MAPPER.createArrayNode();

            while (reader.next()) {
                ObjectNode ticket = MAPPER.createObjectNode();
                ticket.put("id", reader.getColumn("id").getText());
                ticket.put("status", reader.getColumn("status").getText());
                ticket.put("category", reader.getColumn("category").getText());
                ticket.put("text", reader.getColumn("text").getText());
                ticket.put("created_at", reader.getColumn("created_at").getTimestamp().toString());
                tickets.add(ticket);
            }

            System.out.println("INFO: Listed " + tickets.size() + " tickets for user: " + userId);
            return MAPPER.writeValueAsString(tickets);

        } catch (Exception e) {
            System.err.println("ERROR: list-my-tickets failed: " + e.getMessage());
            return errorResponse(e.getMessage());
        }
    }

    private String handleAppendMessage(JsonNode root) {
        String ticketId = root.get("ticket_id").asText();
        String role = root.get("role").asText();
        String text = root.get("text").asText();
        String model = root.has("model") ? root.get("model").asText() : "";
        long tokensIn = root.has("tokens_in") ? root.get("tokens_in").asLong() : 0;
        long tokensOut = root.has("tokens_out") ? root.get("tokens_out").asLong() : 0;
        int latencyMs = root.has("latency_ms") ? root.get("latency_ms").asInt() : 0;
        Instant now = Instant.now();
        String messageId = UUID.randomUUID().toString();

        try (Session session = TABLE_CLIENT.createSession(Duration.ofSeconds(10)).join().getValue()) {
            String upsertMessageQuery =
                    "DECLARE $id AS Utf8; " +
                    "DECLARE $ticket_id AS Utf8; " +
                    "DECLARE $role AS Utf8; " +
                    "DECLARE $text AS Utf8; " +
                    "DECLARE $model AS Utf8; " +
                    "DECLARE $tokens_in AS Uint64; " +
                    "DECLARE $tokens_out AS Uint64; " +
                    "DECLARE $latency_ms AS Uint32; " +
                    "DECLARE $created_at AS Timestamp; " +
                    "UPSERT INTO messages (id, ticket_id, role, text, model, tokens_in, tokens_out, latency_ms, created_at) " +
                    "VALUES ($id, $ticket_id, $role, $text, $model, $tokens_in, $tokens_out, $latency_ms, $created_at);";

            Params messageParams = Params.of(
                    "$id", PrimitiveValue.newText(messageId),
                    "$ticket_id", PrimitiveValue.newText(ticketId),
                    "$role", PrimitiveValue.newText(role),
                    "$text", PrimitiveValue.newText(text),
                    "$model", PrimitiveValue.newText(model),
                    "$tokens_in", PrimitiveValue.newUint64(tokensIn),
                    "$tokens_out", PrimitiveValue.newUint64(tokensOut),
                    "$latency_ms", PrimitiveValue.newUint32(latencyMs),
                    "$created_at", PrimitiveValue.newTimestamp(now)
            );

            session.executeDataQuery(upsertMessageQuery, TxControl.serializableRw().setCommitTx(true), messageParams).join();
            System.out.println("INFO: Message appended to ticket: " + ticketId);

            String updateTicketQuery =
                    "DECLARE $id AS Utf8; " +
                    "DECLARE $updated_at AS Timestamp; " +
                    "UPDATE tickets SET updated_at = $updated_at WHERE id = $id;";

            Params updateParams = Params.of(
                    "$id", PrimitiveValue.newText(ticketId),
                    "$updated_at", PrimitiveValue.newTimestamp(now)
            );

            session.executeDataQuery(updateTicketQuery, TxControl.serializableRw().setCommitTx(true), updateParams).join();

            ObjectNode result = MAPPER.createObjectNode();
            result.put("message_id", messageId);
            result.put("ok", true);
            return MAPPER.writeValueAsString(result);

        } catch (Exception e) {
            System.err.println("ERROR: append-message failed: " + e.getMessage());
            return errorResponse(e.getMessage());
        }
    }

    private String errorResponse(String message) {
        try {
            ObjectNode result = MAPPER.createObjectNode();
            result.put("error", message);
            return MAPPER.writeValueAsString(result);
        } catch (Exception e) {
            return "{\"error\": \"" + message + "\"}";
        }
    }
}
