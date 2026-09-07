package ru.anseranser.ydb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import tech.ydb.table.Session;
import tech.ydb.table.TableClient;
import tech.ydb.table.query.Params;
import tech.ydb.table.result.ResultSetReader;
import tech.ydb.table.transaction.TxControl;
import tech.ydb.table.values.PrimitiveValue;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * YDB client wrapper for tickets domain.
 * Uses TableClient.createSession(10s) + executeDataQuery with Params.of and TxControl.serializableRw.
 */
public class YdbClient implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TableClient tableClient;

    public YdbClient() {
        String endpoint = System.getenv("YDB_ENDPOINT");
        String database = System.getenv("YDB_DATABASE");
        this.tableClient = ru.anseranser.ydb.YdbTransportFactory.create(endpoint, database);
    }

    // Package-private for tests
    YdbClient(TableClient tableClient) {
        this.tableClient = tableClient;
    }

    public String createTicket(String userId, String category, String text) {
        System.out.println("[YdbClient] createTicket: START userId=" + userId + " category=" + category + " textPreview=" + preview(text));
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        if (category == null || category.isBlank()) {
            throw new IllegalArgumentException("category must not be blank");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text must not be blank");
        }
        System.out.println("[YdbClient] createTicket: Validation passed");

        String ticketId = UUID.randomUUID().toString();
        String messageId = UUID.randomUUID().toString();
        Instant now = Instant.now();

        try (Session session = tableClient.createSession(Duration.ofSeconds(10)).join().getValue()) {
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
            System.out.println("[YdbClient] createTicket: YDB transaction executed (ticket)");

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
            System.out.println("[YdbClient] createTicket: YDB transaction executed (message)");

            System.out.println("[YdbClient] createTicket: FINISHED ticketId=" + ticketId);
            ObjectNode result = MAPPER.createObjectNode();
            result.put("ticket_id", ticketId);
            result.put("created_at", now.toString());
            return MAPPER.writeValueAsString(result);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            System.out.println("[YdbClient] createTicket: ERROR " + e.getMessage() + " | caused by: " + e.getCause());
            throw new RuntimeException("Failed to create ticket: " + e.getMessage(), e);
        }
    }

    public String listMyTickets(String userId) {
        System.out.println("[YdbClient] listMyTickets: START userId=" + userId);
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        try (Session session = tableClient.createSession(Duration.ofSeconds(10)).join().getValue()) {
            String query =
                    "DECLARE $user_id AS Utf8; " +
                    "SELECT id, status, category, text, created_at FROM tickets WHERE user_id = $user_id;";

            Params params = Params.of("$user_id", PrimitiveValue.newText(userId));

            ResultSetReader reader = session.executeDataQuery(query, TxControl.serializableRw().setCommitTx(true), params)
                    .join().getValue().getResultSet(0);

            ArrayNode tickets = MAPPER.createArrayNode();
            int count = 0;
            while (reader.next()) {
                ObjectNode ticket = MAPPER.createObjectNode();
                ticket.put("id", reader.getColumn("id").getText());
                ticket.put("status", reader.getColumn("status").getText());
                ticket.put("category", reader.getColumn("category").getText());
                ticket.put("text", reader.getColumn("text").getText());
                ticket.put("created_at", reader.getColumn("created_at").getTimestamp().toString());
                tickets.add(ticket);
                count++;
            }
            System.out.println("[YdbClient] listMyTickets: YDB query executed, resultCount=" + count);
            System.out.println("[YdbClient] listMyTickets: FINISHED");
            return MAPPER.writeValueAsString(tickets);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            System.out.println("[YdbClient] listMyTickets: ERROR " + e.getMessage() + " | caused by: " + e.getCause());
            throw new RuntimeException("Failed to list tickets: " + e.getMessage(), e);
        }
    }

    public String appendMessage(String ticketId, String role, String text, String model, long tokensIn, long tokensOut, int latencyMs) {
        System.out.println("[YdbClient] appendMessage: START ticketId=" + ticketId + " role=" + role);
        if (ticketId == null || ticketId.isBlank()) {
            throw new IllegalArgumentException("ticketId must not be blank");
        }
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("role must not be blank");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text must not be blank");
        }
        System.out.println("[YdbClient] appendMessage: Validation passed");

        String messageId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        String safeModel = model == null ? "" : model;

        try (Session session = tableClient.createSession(Duration.ofSeconds(10)).join().getValue()) {
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
                    "$model", PrimitiveValue.newText(safeModel),
                    "$tokens_in", PrimitiveValue.newUint64(tokensIn),
                    "$tokens_out", PrimitiveValue.newUint64(tokensOut),
                    "$latency_ms", PrimitiveValue.newUint32(latencyMs),
                    "$created_at", PrimitiveValue.newTimestamp(now)
            );

            session.executeDataQuery(upsertMessageQuery, TxControl.serializableRw().setCommitTx(true), messageParams).join();
            System.out.println("[YdbClient] appendMessage: YDB transaction executed");

            String updateTicketQuery =
                    "DECLARE $id AS Utf8; " +
                    "DECLARE $updated_at AS Timestamp; " +
                    "UPDATE tickets SET updated_at = $updated_at WHERE id = $id;";

            Params updateParams = Params.of(
                    "$id", PrimitiveValue.newText(ticketId),
                    "$updated_at", PrimitiveValue.newTimestamp(now)
            );

            session.executeDataQuery(updateTicketQuery, TxControl.serializableRw().setCommitTx(true), updateParams).join();
            System.out.println("[YdbClient] appendMessage: FINISHED messageId=" + messageId);
            ObjectNode result = MAPPER.createObjectNode();
            result.put("message_id", messageId);
            result.put("ok", true);
            return MAPPER.writeValueAsString(result);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            System.out.println("[YdbClient] appendMessage: ERROR " + e.getMessage() + " | caused by: " + e.getCause());
            throw new RuntimeException("Failed to append message: " + e.getMessage(), e);
        }
    }

    public String updateTicketText(String ticketId, String maskedText) {
        System.out.println("[YdbClient] updateTicketText: START ticketId=" + ticketId);
        if (ticketId == null || ticketId.isBlank()) throw new IllegalArgumentException("ticketId must not be blank");
        if (maskedText == null || maskedText.isBlank()) throw new IllegalArgumentException("maskedText must not be blank");
        Instant now = Instant.now();
        try (Session session = tableClient.createSession(Duration.ofSeconds(10)).join().getValue()) {
            String q = "DECLARE $id AS Utf8; DECLARE $text AS Utf8; DECLARE $updated_at AS Timestamp; UPDATE tickets SET text=$text, updated_at=$updated_at WHERE id=$id;";
            Params p = Params.of("$id", PrimitiveValue.newText(ticketId), "$text", PrimitiveValue.newText(maskedText), "$updated_at", PrimitiveValue.newTimestamp(now));
            session.executeDataQuery(q, TxControl.serializableRw().setCommitTx(true), p).join();
            System.out.println("[YdbClient] updateTicketText: FINISHED ticketId=" + ticketId);
            ObjectNode r = MAPPER.createObjectNode();
            r.put("ok", true);
            r.put("ticket_id", ticketId);
            return MAPPER.writeValueAsString(r);
        } catch (IllegalArgumentException e) { throw e; } catch (Exception e) {
            System.out.println("[YdbClient] updateTicketText: ERROR " + e.getMessage());
            throw new RuntimeException("Failed to updateTicketText: " + e.getMessage(), e);
        }
    }

    private String preview(String text) {
        if (text == null) return "null";
        return text.length() > 100 ? text.substring(0, 100) : text;
    }

    @Override
    public void close() {
        if (tableClient != null) {
            try {
                tableClient.close();
            } catch (Exception ignored) {
            }
        }
    }
}
