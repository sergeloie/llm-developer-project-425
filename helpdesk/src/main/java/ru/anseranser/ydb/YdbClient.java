package ru.anseranser.ydb;

import tech.ydb.auth.AuthProvider;
import tech.ydb.auth.AuthIdentity;
import tech.ydb.common.transaction.TxMode;
import tech.ydb.core.Result;
import tech.ydb.core.grpc.GrpcTransport;
import tech.ydb.table.Session;
import tech.ydb.table.TableClient;
import tech.ydb.table.query.DataQuery;
import tech.ydb.table.query.DataQueryResult;
import tech.ydb.table.query.Params;
import tech.ydb.table.result.ResultSetReader;
import tech.ydb.table.result.ValueReader;
import tech.ydb.table.settings.ExecuteDataQuerySettings;
import tech.ydb.table.transaction.TableTransaction;
import tech.ydb.table.transaction.TxControl;
import tech.ydb.table.values.PrimitiveValue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class YdbClient implements AutoCloseable {

    private final GrpcTransport transport;
    private final TableClient tableClient;

    public YdbClient(String endpoint, String database, String token) {
        this.transport = GrpcTransport.forEndpoint(endpoint, database)
                .withAuthProvider(new StaticTokenProvider(token))
                .build();
        this.tableClient = TableClient.newClient(transport).build();
    }

    /** Package-private constructor for tests — allows injecting mocks. */
    YdbClient(GrpcTransport transport, TableClient tableClient) {
        this.transport = transport;
        this.tableClient = tableClient;
    }

    public CompletableFuture<Result<DataQueryResult>> executeQuery(String yql, Map<String, Object> params) {
        String yqlPreview = yql.length() > 80 ? yql.substring(0, 80) : yql;
        System.out.println("[YdbClient] executeQuery: START yql=" + yqlPreview);
        Session session = tableClient.createSession(Duration.ofSeconds(10)).join().getValue();
        try {
            Params queryParams = convertParams(params);
            DataQuery prepared = session.prepareDataQuery(yql).join().getValue();
            return prepared.execute(
                    TxControl.serializableRw().setCommitTx(true),
                    queryParams,
                    new ExecuteDataQuerySettings()
            ).whenComplete((result, ex) -> {
                session.close();
                if (ex != null) {
                    System.out.println("[YdbClient] executeQuery: ERROR " + ex.getMessage() + " | caused by: " + ex.getCause());
                } else {
                    System.out.println("[YdbClient] executeQuery: FINISHED");
                }
            });
        } catch (Exception e) {
            System.out.println("[YdbClient] executeQuery: ERROR " + e.getMessage() + " | caused by: " + e.getCause());
            session.close();
            throw e;
        }
    }

    public CompletableFuture<List<Result<DataQueryResult>>> executeInTransaction(List<QueryParams> queries) {
        System.out.println("[YdbClient] executeInTransaction: START queryCount=" + queries.size());
        Session session = tableClient.createSession(Duration.ofSeconds(10)).join().getValue();
        try {
            TableTransaction tx = session.createNewTransaction(TxMode.SERIALIZABLE_RW);
            List<CompletableFuture<Result<DataQueryResult>>> futures = new ArrayList<>();

            for (QueryParams query : queries) {
                Params queryParams = convertParams(query.params());
                futures.add(tx.executeDataQuery(query.yql(), queryParams));
            }

            tx.commit().join();
            System.out.println("[YdbClient] executeInTransaction: Committed successfully");

            List<Result<DataQueryResult>> results = new ArrayList<>();
            for (CompletableFuture<Result<DataQueryResult>> future : futures) {
                results.add(future.join());
            }
            System.out.println("[YdbClient] executeInTransaction: FINISHED");
            return CompletableFuture.completedFuture(results);
        } catch (Exception e) {
            System.out.println("[YdbClient] executeInTransaction: ERROR " + e.getMessage() + " | caused by: " + e.getCause());
            throw e;
        } finally {
            session.close();
        }
    }

    public String createTicket(String userId, String category, String text) {
        System.out.println("[YdbClient] createTicket: START userId=" + userId + " category=" + category);
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        if (category == null || category.isBlank()) {
            throw new IllegalArgumentException("category must not be blank");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text must not be blank");
        }
        String textPreview = text.length() > 100 ? text.substring(0, 100) : text;
        System.out.println("[YdbClient] createTicket: Validation passed textPreview=" + textPreview);

        String ticketId = UUID.randomUUID().toString();
        String messageId = UUID.randomUUID().toString();
        String createdAt = Instant.now().toString();

        QueryParams ticketQuery = new QueryParams(
                "INSERT INTO tickets (id, user_id, category, status, created_at) VALUES ($id, $user_id, $category, $status, $created_at)",
                Map.of("id", ticketId, "user_id", userId, "category", category, "status", "open", "created_at", createdAt)
        );

        QueryParams messageQuery = new QueryParams(
                "INSERT INTO messages (id, ticket_id, text, role, created_at) VALUES ($id, $ticket_id, $text, $role, $created_at)",
                Map.of("id", messageId, "ticket_id", ticketId, "text", text, "role", "user", "created_at", createdAt)
        );

        try {
            List<Result<DataQueryResult>> results = executeInTransaction(List.of(ticketQuery, messageQuery)).join();
            for (Result<DataQueryResult> result : results) {
                if (!result.isSuccess()) {
                    throw new RuntimeException("YDB error: " + result.getStatus());
                }
            }
            System.out.println("[YdbClient] createTicket: YDB transaction executed");
        } catch (RuntimeException e) {
            System.out.println("[YdbClient] createTicket: ERROR " + e.getMessage() + " | caused by: " + e.getCause());
            throw e;
        } catch (Exception e) {
            System.out.println("[YdbClient] createTicket: ERROR " + e.getMessage() + " | caused by: " + e.getCause());
            throw new RuntimeException("Failed to create ticket: " + e.getMessage(), e);
        }

        System.out.println("[YdbClient] createTicket: FINISHED ticketId=" + ticketId);
        JsonObject response = new JsonObject();
        response.addProperty("ticket_id", ticketId);
        response.addProperty("created_at", createdAt);
        return response.toString();
    }

    public String listMyTickets(String userId) {
        System.out.println("[YdbClient] listMyTickets: START userId=" + userId);
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }

        String yql = "SELECT t.id, t.status, t.category, t.created_at, "
                + "(SELECT m.text FROM messages AS m WHERE m.ticket_id = t.id "
                + "ORDER BY m.created_at LIMIT 1) AS text "
                + "FROM tickets AS t VIEW tickets_by_user "
                + "WHERE t.user_id = $user_id";

        try {
            Result<DataQueryResult> result = executeQuery(yql, Map.of("user_id", userId)).join();
            if (!result.isSuccess()) {
                throw new RuntimeException("YDB error: " + result.getStatus());
            }

            DataQueryResult dataResult = result.getValue();
            ResultSetReader resultSet = dataResult.getResultSet(0);

            JsonArray tickets = new JsonArray();
            while (resultSet.next()) {
                JsonObject ticket = new JsonObject();
                ticket.addProperty("id", ((PrimitiveValue) resultSet.getColumn("id").getValue()).getText());
                ticket.addProperty("status", ((PrimitiveValue) resultSet.getColumn("status").getValue()).getText());
                ticket.addProperty("category", ((PrimitiveValue) resultSet.getColumn("category").getValue()).getText());
                ticket.addProperty("created_at", ((PrimitiveValue) resultSet.getColumn("created_at").getValue()).getText());

                ValueReader textReader = resultSet.getColumn("text");
                String text = "";
                if (textReader != null && textReader.getValue() instanceof PrimitiveValue pv) {
                    String t = pv.getText();
                    if (t != null) {
                        text = t;
                    }
                }
                ticket.addProperty("text", text);

                tickets.add(ticket);
            }

            System.out.println("[YdbClient] listMyTickets: YDB query executed, resultCount=" + tickets.size());
            System.out.println("[YdbClient] listMyTickets: FINISHED");
            return tickets.toString();
        } catch (RuntimeException e) {
            System.out.println("[YdbClient] listMyTickets: ERROR " + e.getMessage() + " | caused by: " + e.getCause());
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
        String createdAt = Instant.now().toString();

        QueryParams messageQuery = new QueryParams(
                "INSERT INTO messages (id, ticket_id, text, role, model, tokens_in, tokens_out, latency_ms, created_at) "
                        + "VALUES ($id, $ticket_id, $text, $role, $model, $tokens_in, $tokens_out, $latency_ms, $created_at)",
                Map.of(
                        "id", messageId,
                        "ticket_id", ticketId,
                        "text", text,
                        "role", role,
                        "model", model,
                        "tokens_in", tokensIn,
                        "tokens_out", tokensOut,
                        "latency_ms", latencyMs,
                        "created_at", createdAt
                )
        );

        try {
            List<Result<DataQueryResult>> results = executeInTransaction(List.of(messageQuery)).join();
            for (Result<DataQueryResult> result : results) {
                if (!result.isSuccess()) {
                    throw new RuntimeException("YDB error: " + result.getStatus());
                }
            }
            System.out.println("[YdbClient] appendMessage: YDB transaction executed");
        } catch (RuntimeException e) {
            System.out.println("[YdbClient] appendMessage: ERROR " + e.getMessage() + " | caused by: " + e.getCause());
            throw e;
        } catch (Exception e) {
            System.out.println("[YdbClient] appendMessage: ERROR " + e.getMessage() + " | caused by: " + e.getCause());
            throw new RuntimeException("Failed to append message: " + e.getMessage(), e);
        }

        System.out.println("[YdbClient] appendMessage: FINISHED messageId=" + messageId);
        JsonObject response = new JsonObject();
        response.addProperty("message_id", messageId);
        response.addProperty("ok", true);
        return response.toString();
    }

    private Params convertParams(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return Params.empty();
        }
        System.out.println("[YdbClient] convertParams: START paramCount=" + params.size());
        Params queryParams = Params.create(params.size());
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            String key = entry.getKey().startsWith("$") ? entry.getKey() : "$" + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof String) {
                queryParams.put(key, PrimitiveValue.newText((String) value));
            } else if (value instanceof Long) {
                queryParams.put(key, PrimitiveValue.newUint64((Long) value));
            } else if (value instanceof Integer) {
                queryParams.put(key, PrimitiveValue.newUint64((long) (Integer) value));
            } else if (value instanceof Double) {
                queryParams.put(key, PrimitiveValue.newDouble((Double) value));
            } else if (value instanceof Boolean) {
                queryParams.put(key, PrimitiveValue.newBool((Boolean) value));
            } else {
                queryParams.put(key, PrimitiveValue.newText(value.toString()));
            }
        }
        System.out.println("[YdbClient] convertParams: FINISHED");
        return queryParams;
    }

    @Override
    public void close() {
        tableClient.close();
        transport.close();
    }

}
