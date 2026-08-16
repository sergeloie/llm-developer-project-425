package ru.anseranser.ydb;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import yandex.cloud.sdk.functions.Context;
import yandex.cloud.sdk.functions.YcFunction;

public class YdbTicketsHandler implements YcFunction<String, String> {

    private final YdbClient ydbClient;
    private final EventDispatcher dispatcher;

    public YdbTicketsHandler() {
        this.ydbClient = new YdbClient(
                System.getenv("YDB_ENDPOINT"),
                System.getenv("YDB_DATABASE"),
                System.getenv("YDB_TOKEN")
        );
        this.dispatcher = new EventDispatcher();
    }

    YdbTicketsHandler(YdbClient ydbClient, EventDispatcher dispatcher) {
        this.ydbClient = ydbClient;
        this.dispatcher = dispatcher;
    }

    @Override
    public String handle(String s, Context context) {
        EventDispatcher.Action action = dispatcher.dispatch(s);
        String body = dispatcher.extractBody(s);
        JsonObject json = JsonParser.parseString(body).getAsJsonObject();

        return switch (action) {
            case CREATE_TICKET -> ydbClient.createTicket(
                    json.get("user_id").getAsString(),
                    json.get("category").getAsString(),
                    json.get("text").getAsString()
            );
            case LIST_MY_TICKETS -> ydbClient.listMyTickets(
                    json.get("user_id").getAsString()
            );
            case APPEND_MESSAGE -> ydbClient.appendMessage(
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
