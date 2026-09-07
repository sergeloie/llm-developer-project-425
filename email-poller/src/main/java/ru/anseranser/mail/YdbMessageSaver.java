package ru.anseranser.mail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Persists the agent reply row (role=agent) with model/tokens/latency via ydb-tickets `append-message`.
 * <p>
 * Per step 9 of the ТЗ ("доставайте их отдельно") the poller is the authoritative source of `usage` —
 * the LLM agent cannot see its own token counters, so the poller appends the role=agent row directly
 * via HTTP to ydb-tickets. This guarantees the ticket has exactly 2 messages: role=user (written by
 * `create-ticket` with the client's verbatim words) and role=agent (written here, with real usage).
 * <p>
 * Fail-open: any failure only logs, never aborts email processing.
 */
public class YdbMessageSaver {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private final String ydbTicketsUrl;

    public YdbMessageSaver() {
        this(System.getenv("YDB_TICKETS_URL"));
    }

    YdbMessageSaver(String ydbTicketsUrl) {
        this.ydbTicketsUrl = ydbTicketsUrl;
    }

    public void trySave(String userId, AgentClient.AgentResult result, String agentText) {
        if (userId == null || userId.isBlank() || result == null || agentText == null || agentText.isBlank()) {
            System.out.println("SKIP_PERSIST missing user_id/result/agentText");
            return;
        }
        if (result.inputTokens() == 0 && result.outputTokens() == 0 && (result.model() == null || result.model().isBlank())) {
            System.out.println("SKIP_PERSIST No usage/model to persist, rely on agent");
            return;
        }
        // TicketId is extracted by AgentClient from the create-ticket MCP call output within the same agent response.
        String ticketId = result.ticketId();
        if (ticketId == null || ticketId.isBlank()) {
            // Agent answered without creating a ticket (e.g. RAG answer) — nothing to append to.
            System.out.printf("SKIP_PERSIST No ticketId in MCP output, assume RAG answer without ticket. user_id=%s model=%s input=%d output=%d%s",
                    userId, result.model(), result.inputTokens(), result.outputTokens(), System.lineSeparator());
            return;
        }
        String invokeUrl = ydbTicketsUrl;
        if (invokeUrl == null || invokeUrl.isBlank()) {
            String fid = System.getenv("YDB_TICKETS_FUNCTION_ID");
            if (fid != null && !fid.isBlank()) {
                invokeUrl = "https://functions.yandexcloud.net/" + fid;
            }
        }
        if (invokeUrl == null || invokeUrl.isBlank()) {
            System.out.printf("SKIP_PERSIST No YDB_TICKETS_URL, will rely on MCP. user_id=%s model=%s input=%d output=%d latency=%d ticketId=%s%s",
                    userId, result.model(), result.inputTokens(), result.outputTokens(), result.latencyMs(), ticketId, System.lineSeparator());
            return;
        }
        try {
            Map<String, Object> append = new java.util.HashMap<>();
            append.put("action", "append-message");
            append.put("ticket_id", ticketId);
            append.put("role", "agent");
            append.put("text", agentText);
            append.put("model", result.model() == null ? "" : result.model());
            append.put("tokens_in", result.inputTokens());
            append.put("tokens_out", result.outputTokens());
            append.put("latency_ms", (int) Math.min(result.latencyMs(), Integer.MAX_VALUE));
            String appendPayload = GSON.toJson(append);
            String appendResp = invokeFunction(invokeUrl, appendPayload);
            System.out.printf("PERSISTED_AGENT_MESSAGE ticket_id=%s model=%s input=%d output=%d latency=%d resp=%s%s",
                    ticketId, result.model(), result.inputTokens(), result.outputTokens(), result.latencyMs(), appendResp, System.lineSeparator());
        } catch (Exception e) {
            System.out.println("FAILED_PERSIST_AGENT_MESSAGE (fail-open): " + e.getMessage());
        }
    }

    private String invokeFunction(String url, String json) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        // IAM token from metadata takes precedence (function runs inside YC); fall back to API key for local runs.
        String iam = fetchIamToken();
        if (iam != null && !iam.isBlank()) {
            b.header("Authorization", "Bearer " + iam);
        } else {
            String apiKey = System.getenv("YANDEX_API_KEY");
            if (apiKey != null && !apiKey.isBlank()) {
                b.header("Authorization", "Api-Key " + apiKey);
            }
        }
        HttpRequest req = b.build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            throw new RuntimeException("HTTP " + resp.statusCode() + ": " + resp.body());
        }
        return resp.body();
    }

    private String fetchIamToken() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://169.254.169.254/computeMetadata/v1/instance/service-accounts/default/token"))
                    .header("Metadata-Flavor", "Google")
                    .timeout(Duration.ofSeconds(2))
                    .GET().build();
            HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 200) {
                JsonNode n = MAPPER.readTree(r.body());
                return n.has("access_token") ? n.get("access_token").asText() : null;
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}