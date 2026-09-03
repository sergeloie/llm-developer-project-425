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
    public record AgentResult(String text, long inputTokens, long outputTokens, String responseId, long latencyMs) {}

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
        String modelResponse = response.output().getLast().message().get().content().getFirst().asOutputText().text();
        long in = response.usage().map(u -> u.inputTokens()).orElse(0L);
        long out = response.usage().map(u -> u.outputTokens()).orElse(0L);

        System.out.printf("Response id: %s. Request: %s. Response: %s. Input Tokens: %d. Output Tokens: %d. Latency: %dms%s",
                response.id(),
                request,
                modelResponse,
                in, out, latency,
                System.lineSeparator());
        // Explicit token log for step 9 verification: usage ≈ messages.tokens_in/out (≤10%)
        System.out.printf("TOKENS_USAGE input=%d output=%d latency=%d responseId=%s%s", in, out, latency, response.id(), System.lineSeparator());

        return new AgentResult(modelResponse, in, out, response.id(), latency);
    }
}
