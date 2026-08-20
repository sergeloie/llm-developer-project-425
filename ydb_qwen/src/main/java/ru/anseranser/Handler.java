package ru.anseranser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import tech.ydb.auth.AuthProvider;
import tech.ydb.auth.iam.CloudAuthHelper;
import tech.ydb.core.grpc.GrpcTransport;
import tech.ydb.table.Session;
import tech.ydb.table.TableClient;
import tech.ydb.table.query.Params;
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
            throw new IllegalStateException("Переменная окружения YDB_ENDPOINT не задана. Пример: grpcs://ydb.serverless.yandexcloud.net:2135");
        }
        if (database == null || database.isBlank() || !database.startsWith("/")) {
            throw new IllegalStateException("Переменная окружения YDB_DATABASE не задана или неверна. Пример: /ru-central1/b1g.../etn...");
        }

        try {
            // CloudAuthHelper.getAuthProviderFromEnviron() автоматически:
            // 1. Проверяет YDB_TOKEN или YDB_SERVICE_ACCOUNT_KEY_FILE
            // 2. Если их нет, автоматически использует Metadata Service функции (самый надежный способ)
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
            JsonNode root = input != null && !input.isBlank() ? MAPPER.readTree(input) : null;

            String ticketId = root != null && root.has("ticket_id") ? root.get("ticket_id").asText() : UUID.randomUUID().toString();
            String userId = root != null && root.has("user_id") ? root.get("user_id").asText() : "user@example.com";
            String ticketText = root != null && root.has("text") ? root.get("text").asText() : "Текст обращения";

            // Создаем сессию с таймаутом
            try (Session session = TABLE_CLIENT.createSession(Duration.ofSeconds(10)).join().getValue()) {
                System.out.println("INFO: Session started for ticket: " + ticketId);

                // --- ВСТАВКА В ТАБЛИЦУ tickets ---
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
                        "$category", PrimitiveValue.newText("bug"),
                        "$status", PrimitiveValue.newText("open"),
                        "$text", PrimitiveValue.newText(ticketText),
                        "$created_at", PrimitiveValue.newTimestamp(Instant.now()),
                        "$updated_at", PrimitiveValue.newTimestamp(Instant.now())
                );

                // TxControl.serializableRw().setCommitTx(true) - правильный способ включить авто-коммит
                session.executeDataQuery(upsertTicketQuery, TxControl.serializableRw().setCommitTx(true), ticketParams).join();
                System.out.println("INFO: Ticket inserted: " + ticketId);

                // --- ВСТАВКА В ТАБЛИЦУ messages ---
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
                        "$id", PrimitiveValue.newText(UUID.randomUUID().toString()),
                        "$ticket_id", PrimitiveValue.newText(ticketId),
                        "$role", PrimitiveValue.newText("user"),
                        "$text", PrimitiveValue.newText(ticketText),
                        "$model", PrimitiveValue.newText("yandexgpt-lite"),
                        "$tokens_in", PrimitiveValue.newUint64(15),
                        "$tokens_out", PrimitiveValue.newUint64(0),
                        "$latency_ms", PrimitiveValue.newUint32(120),
                        "$created_at", PrimitiveValue.newTimestamp(Instant.now())
                );

                session.executeDataQuery(upsertMessageQuery, TxControl.serializableRw().setCommitTx(true), messageParams).join();
                System.out.println("INFO: Message inserted for ticket: " + ticketId);
            }

            return "{\"status\": \"success\", \"ticket_id\": \"" + ticketId + "\"}";

        } catch (Exception e) {
            System.err.println("ERROR: YDB insertion failed - " + e.getMessage());
            e.printStackTrace();
            return "{\"status\": \"error\", \"message\": \"" + e.getMessage() + "\"}";
        }
    }
}