package ru.anseranser.mail;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.responses.FileSearchTool;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponsePrompt;
import com.openai.models.responses.Tool;

import java.util.ArrayList;
import java.util.List;

/**
 * Client wrapper for communicating with a Yandex Cloud LLM agent via the OpenAI-compatible API.
 * Supports MCP tool integration and RAG file_search.
 */
public class AgentClient {

    private final String apiKey;
    private final String agentId;
    private final String organizationId;
    private final String mcpServerUrl;
    private final String vectorStoreId;
    private final OpenAIClient clientOverride;

    public AgentClient(String apiKey, String agentId, String organizationId, String mcpServerUrl) {
        this(apiKey, agentId, organizationId, mcpServerUrl, System.getenv("VECTOR_STORE_ID"), null);
    }

    public AgentClient(String apiKey, String agentId, String organizationId, String mcpServerUrl, String vectorStoreId) {
        this(apiKey, agentId, organizationId, mcpServerUrl, vectorStoreId, null);
    }

    AgentClient(String apiKey, String agentId, String organizationId, String mcpServerUrl, String vectorStoreId, OpenAIClient clientOverride) {
        this.apiKey = apiKey;
        this.agentId = agentId;
        this.organizationId = organizationId;
        this.mcpServerUrl = mcpServerUrl;
        this.vectorStoreId = vectorStoreId;
        this.clientOverride = clientOverride;
    }

    public AgentClient() {
        this(System.getenv("YANDEX_API_KEY"),
                System.getenv("AGENT_ID"),
                System.getenv("ORGANIZATION_ID"),
                System.getenv("MCP_SERVER_URL"),
                System.getenv("VECTOR_STORE_ID"),
                null);
    }

    /** P1 fix: DTO with usage — required for step 9 token verification (usage ≈ messages.tokens_in/out). */
    public record AgentResult(String text, long inputTokens, long outputTokens, String responseId, long latencyMs, String model, String ticketId) {}

    /**
     * Sends a request to the LLM agent and returns the text response.
     * Builds tools list with optional file_search and mandatory mcp.
     *
     * @param request the JSON input to send to the agent (user_id + text)
     * @return the agent's text response
     */
    public String getResponse(String request) {
        return getResponseWithUsage(request).text();
    }

    /**
     * P1 fix: variant that exposes usage + latency for explicit token accounting.
     * Measures wall-clock latency, extracts usage.inputTokens/outputTokens from Responses API.
     * Fail-open: if usage missing, returns 0/0.
     */
    public AgentResult getResponseWithUsage(String request) {
        long start = System.currentTimeMillis();
        OpenAIClient client = clientOverride != null ? clientOverride : OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl("https://ai.api.cloud.yandex.net/v1")
                .organization(organizationId)
                .build();

        Tool mcpTool = Tool.ofMcp(
                Tool.Mcp.builder()
                        .serverLabel("ydb-tickets")
                        .serverUrl(mcpServerUrl)
                        .requireApproval(Tool.Mcp.RequireApproval.McpToolApprovalSetting.NEVER)
                        .allowedToolsOfMcp(List.of("append-message", "create-ticket", "list-my-tickets"))
                        .build()
        );

        List<Tool> tools = new ArrayList<>();
        if (vectorStoreId != null && !vectorStoreId.isBlank()) {
            FileSearchTool fileSearch = FileSearchTool.builder()
                    .vectorStoreIds(List.of(vectorStoreId))
                    .build();
            tools.add(Tool.ofFileSearch(fileSearch));
        }
        tools.add(mcpTool);

        ResponseCreateParams params = ResponseCreateParams.builder()
                .prompt(ResponsePrompt.builder()
                        .id(agentId)
                        .build())
                .input(request)
                .tools(tools)
                .build();

        Response response = client.responses().create(params);
        long latency = System.currentTimeMillis() - start;
        String modelResponse = extractOutputText(response);
        long in = response.usage().map(u -> u.inputTokens()).orElse(0L);
        long out = response.usage().map(u -> u.outputTokens()).orElse(0L);
        String modelName = extractModelName(response);
        String ticketId = extractTicketId(response);

        System.out.printf("Response id: %s. Request: %s. Response: %s. Input Tokens: %d. Output Tokens: %d. Latency: %dms Model: %s TicketId: %s%s",
                response.id(),
                request,
                modelResponse,
                in, out, latency, modelName, ticketId,
                System.lineSeparator());
        // Explicit token log for step 9 verification: usage ≈ messages.tokens_in/out (≤10%)
        System.out.printf("TOKENS_USAGE input=%d output=%d latency=%d responseId=%s model=%s ticketId=%s%s", in, out, latency, response.id(), modelName, ticketId, System.lineSeparator());
        if (ticketId != null) {
            System.out.printf("MCP_TICKET_ID ticket_id=%s%s", ticketId, System.lineSeparator());
        }

        return new AgentResult(modelResponse, in, out, response.id(), latency, modelName, ticketId);
    }

    private static String extractModelName(Response response) {
        try {
            var m = response.model();
            if (m == null) return "";
            if (m.isString()) return m.asString();
            if (m.isChat()) return m.asChat().toString();
            if (m.isOnly()) return m.asOnly().toString();
            // fallback to raw json field
            var jf = response._model();
            if (jf != null) {
                var opt = jf.asString();
                if (opt.isPresent()) return opt.get();
                var known = jf.asKnown();
                if (known.isPresent() && known.get() != null) return known.get().toString().replace("\"", "");
            }
            return m.toString();
        } catch (Exception e) {
            try {
                var raw = response._model();
                if (raw != null) {
                    var s = raw.asString();
                    if (s.isPresent()) return s.get();
                    var k = raw.asKnown();
                    if (k.isPresent() && k.get() != null) return k.get().toString().replace("\"", "");
                }
            } catch (Exception ignored) {}
            return "";
        }
    }

    private static String extractOutputText(Response response) {
        try {
            var out = response.output();
            if (out == null || out.isEmpty()) return "";
            // Find last message item
            for (int i = out.size() - 1; i >= 0; i--) {
                var item = out.get(i);
                if (item.isMessage() && item.message().isPresent()) {
                    var msg = item.message().get();
                    var content = msg.content();
                    if (content != null && !content.isEmpty()) {
                        var c = content.get(0);
                        try {
                            return c.asOutputText().text();
                        } catch (Exception ignored) {}
                    }
                }
            }
            // fallback
            return out.get(out.size() - 1).message().get().content().get(0).asOutputText().text();
        } catch (Exception e) {
            System.out.println("WARN: extractOutputText failed: " + e.getMessage());
            return "";
        }
    }

    private static String extractTicketId(Response response) {
        try {
            var out = response.output();
            if (out == null) return null;
            for (var item : out) {
                if (item.isMcpCall() && item.mcpCall().isPresent()) {
                    var mcp = item.mcpCall().get();
                    // name check
                    String name = mcp.name();
                    if (!"create-ticket".equals(name) && !"create_ticket".equals(name)) continue;
                    var outputOpt = mcp.output();
                    if (outputOpt.isEmpty() || outputOpt.get() == null || outputOpt.get().isBlank()) continue;
                    String json = outputOpt.get();
                    // output is JSON string like {"ticket_id":"...","created_at":"..."}
                    // fast regex/json parse
                    int idx = json.indexOf("ticket_id");
                    if (idx < 0) continue;
                    // crude parse via Jackson
                    try {
                        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                        com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(json);
                        if (node.has("ticket_id")) return node.get("ticket_id").asText();
                        if (node.has("ticketId")) return node.get("ticketId").asText();
                    } catch (Exception ex) {
                        // fallback regex
                        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"ticket_id\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
                        if (m.find()) return m.group(1);
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("WARN: extractTicketId failed: " + e.getMessage());
        }
        return null;
    }
}
