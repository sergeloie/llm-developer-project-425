package ru.anseranser.ydb;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import yandex.cloud.sdk.functions.Context;
import yandex.cloud.sdk.functions.YcFunction;

public class YdbTicketsHandler implements YcFunction<String, String> {

    private volatile YdbClient ydbClient;
    private EventDispatcher dispatcher;

    /** Public no-arg constructor — required by Yandex Cloud Function (YcFunction). */
    public YdbTicketsHandler() {
        this.dispatcher = new EventDispatcher();
    }

    /** Package-private static factory for tests — injects mocks directly. */
    static YdbTicketsHandler createForTest(YdbClient ydbClient, EventDispatcher dispatcher) {
        YdbTicketsHandler handler = new YdbTicketsHandler();
        handler.ydbClient = ydbClient;
        handler.dispatcher = dispatcher;
        return handler;
    }

    private YdbClient getOrCreateYdbClient() {
        if (ydbClient == null) {
            synchronized (this) {
                if (ydbClient == null) {
                    String endpoint = System.getenv("YDB_ENDPOINT");
                    String database = System.getenv("YDB_DATABASE");
                    String token = System.getenv("YDB_TOKEN");

                    if (endpoint == null || database == null || token == null) {
                        throw new IllegalStateException(
                                "YDB not configured: YDB_ENDPOINT, YDB_DATABASE, YDB_TOKEN must be set"
                        );
                    }

                    ydbClient = new YdbClient(endpoint, database, token);
                }
            }
        }
        return ydbClient;
    }

    @Override
    public String handle(String s, Context context) {
        YdbClient client = getOrCreateYdbClient();
        DispatchResult result = dispatcher.dispatchAndExtract(s);
        JsonObject json = JsonParser.parseString(result.body()).getAsJsonObject();

        return switch (result.action()) {
            case CREATE_TICKET -> client.createTicket(
                    json.get("user_id").getAsString(),
                    json.get("category").getAsString(),
                    json.get("text").getAsString()
            );
            case LIST_MY_TICKETS -> client.listMyTickets(
                    json.get("user_id").getAsString()
            );
            case APPEND_MESSAGE -> client.appendMessage(
                    json.get("ticket_id").getAsString(),
                    json.get("role").getAsString(),
                    json.get("text").getAsString(),
                    json.has("model") ? json.get("model").getAsString() : "",
                    json.has("tokens_in") ? json.get("tokens_in").getAsLong() : 0L,
                    json.has("tokens_out") ? json.get("tokens_out").getAsLong() : 0L,
                    json.has("latency_ms") ? json.get("latency_ms").getAsInt() : 0
            );
        };
    }
}
