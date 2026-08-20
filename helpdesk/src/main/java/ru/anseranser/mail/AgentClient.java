package ru.anseranser.mail;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponsePrompt;
import com.openai.models.responses.Tool;

import java.util.List;

/**
 * Client wrapper for communicating with a Yandex Cloud LLM agent via the OpenAI-compatible API.
 * Supports MCP tool integration for calling external tools (e.g. ticket system).
 */
public class AgentClient {

    private final String apiKey;
    private final String agentId;
    private final String organizationId;
    private final String mcpServerUrl;

    public AgentClient(String apiKey, String agentId, String organizationId, String mcpServerUrl) {
        this.apiKey = apiKey;
        this.agentId = agentId;
        this.organizationId = organizationId;
        this.mcpServerUrl = mcpServerUrl;
    }

    /**
     * Sends a request to the LLM agent and returns the text response.
     * The agent has access to MCP tools specified by {@code mcpServerUrl}.
     *
     * @param request the input text to send to the agent
     * @return the agent's text response
     */
    public String getResponse(String request) {
        OpenAIClient client = OpenAIOkHttpClient.builder()
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

        ResponseCreateParams params = ResponseCreateParams.builder()
                .prompt(ResponsePrompt.builder()
                        .id(agentId)
                        .build())
                .input(request)
                .tools(List.of(mcpTool))
                .build();

        Response response = client.responses().create(params);
        String modelResponse = response.output().getFirst().message().get().content().getFirst().asOutputText().text();

        System.out.printf("Response id: %s. Request: %s. Response: %s. Input Tokens: %d. Output Tokens: %d.%s",
                response.id(),
                request,
                modelResponse,
                response.usage().get().inputTokens(),
                response.usage().get().outputTokens(),
                System.lineSeparator());

        return modelResponse;
    }
}
