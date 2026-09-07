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
 * Best-effort persister for agent reply with tokens/model via ydb-tickets function.
 * Calls ydb-tickets directly via HTTP (IAM token from metadata or YANDEX_API_KEY).
 * Fail-open.
 */
public class YdbMessageSaver {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private final String ydbTicketsUrl;
    private final String ydbEndpoint;
    private final String ydbDatabase;

    public YdbMessageSaver() {
        this(System.getenv("YDB_TICKETS_URL"), System.getenv("YDB_ENDPOINT"), System.getenv("YDB_DATABASE"));
    }

    YdbMessageSaver(String ydbTicketsUrl, String ydbEndpoint, String ydbDatabase) {
        this.ydbTicketsUrl = ydbTicketsUrl;
        this.ydbEndpoint = ydbEndpoint;
        this.ydbDatabase = ydbDatabase;
    }

    // test constructor
    YdbMessageSaver(String ydbEndpoint, String ydbDatabase) {
        this(null, ydbEndpoint, ydbDatabase);
    }

    public void trySave(String userId, AgentClient.AgentResult result, String agentText) {
        if (userId == null || userId.isBlank() || result == null || agentText == null || agentText.isBlank()) return;
        if ((result.inputTokens() == 0 && result.outputTokens() == 0 && (result.model() == null || result.model().isBlank()))) {
            System.out.println("No usage/model to persist, skip");
            return;
        }
        // Prefer ticketId already extracted from MCP output (avoids list call that returns array -> 502 via gateway)
        String ticketId = result.ticketId();
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
            if (ticketId == null || ticketId.isBlank()) {
                // No ticketId from MCP — agent answered without creating ticket (RAG). Try to find via list only if needed,
                // but list via gateway returns array -> 502, so skip and rely on MCP
                System.out.printf("SKIP_PERSIST No ticketId in MCP output, assume RAG answer without ticket. user_id=%s model=%s input=%d output=%d%s",
                        userId, result.model(), result.inputTokens(), result.outputTokens(), System.lineSeparator());
                return;
            }
            // Append agent message with tokens using known ticketId — avoids list call
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
            System.out.println("Failed to persist agent message (fail-open): " + e.getMessage());
        }
    }

    private String invokeFunction(String url, String json) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        // Try IAM token from metadata, else YANDEX_API_KEY
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
        } catch (Exception ignored) {}
        return null;
    }

    private String extractLatestTicketId(String listJson) {
        try {
            JsonNode arr = MAPPER.readTree(listJson);
            JsonNode tickets = arr;
            if (arr.isObject() && arr.has("tickets")) tickets = arr.get("tickets");
            if (!tickets.isArray() || tickets.size() == 0) return null;
            String latestId = null;
            String latestAt = "";
            for (JsonNode t : tickets) {
                String id = t.has("id") ? t.get("id").asText() : null;
                String at = t.has("created_at") ? t.get("created_at").asText() : "";
                if (id != null && at.compareTo(latestAt) >= 0) {
                    latestAt = at;
                    latestId = id;
                }
            }
            if (latestId == null && tickets.size() > 0) {
                latestId = tickets.get(tickets.size() - 1).has("id") ? tickets.get(tickets.size() - 1).get("id").asText() : null;
            }
            return latestId;
        } catch (Exception e) {
            System.out.println("Failed to parse listMyTickets: " + e.getMessage());
            return null;
        }
    }
}
