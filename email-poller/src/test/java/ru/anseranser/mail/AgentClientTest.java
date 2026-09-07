package ru.anseranser.mail;

import com.openai.client.OpenAIClient;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseOutputText;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseUsage;
import com.openai.services.blocking.ResponseService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AgentClientTest {

    @Mock
    private OpenAIClient openAIClient;

    @Mock
    private ResponseService responseService;

    private Response mockResponse(String text) {
        Response response = mock(Response.class);
        lenient().when(response.id()).thenReturn("resp_123");

        ResponseOutputItem outputItem = mock(ResponseOutputItem.class);
        ResponseOutputMessage message = mock(ResponseOutputMessage.class);
        ResponseOutputMessage.Content content = mock(ResponseOutputMessage.Content.class);
        ResponseOutputText outputText = mock(ResponseOutputText.class);
        when(outputText.text()).thenReturn(text);
        when(content.asOutputText()).thenReturn(outputText);
        when(message.content()).thenReturn(List.of(content));
        when(outputItem.message()).thenReturn(Optional.of(message));
        lenient().when(response.output()).thenReturn(List.of(outputItem));

        ResponseUsage usage = mock(ResponseUsage.class);
        lenient().when(usage.inputTokens()).thenReturn(10L);
        lenient().when(usage.outputTokens()).thenReturn(20L);
        lenient().when(response.usage()).thenReturn(Optional.of(usage));

        // model mock for token test
        try {
            com.openai.models.ResponsesModel rm = com.openai.models.ResponsesModel.ofString("yandexgpt");
            lenient().when(response.model()).thenReturn(rm);
            lenient().when(response._model()).thenReturn(com.openai.core.JsonField.of(rm));
        } catch (Exception ignored) {}

        return response;
    }

    @Test
    void getResponse_withVectorStoreId_includesFileSearchAndMcp() {
        Response mockResp = mockResponse("Agent reply");
        when(openAIClient.responses()).thenReturn(responseService);
        when(responseService.create(any(ResponseCreateParams.class))).thenReturn(mockResp);

        String vectorStoreId = "vs_123";
        String mcpUrl = "https://mcp.example.com";
        String agentId = "agent-1";
        String orgId = "org-1";

        AgentClient client = new AgentClient("key", agentId, orgId, mcpUrl, vectorStoreId, openAIClient);

        String result = client.getResponse("{\"user_id\":\"u\",\"text\":\"hello\"}");

        assertEquals("Agent reply", result);

        ArgumentCaptor<ResponseCreateParams> captor = ArgumentCaptor.forClass(ResponseCreateParams.class);
        verify(responseService).create(captor.capture());
        ResponseCreateParams params = captor.getValue();

        assertTrue(params.prompt().isPresent());
        assertEquals(agentId, params.prompt().get().id());

        assertTrue(params.tools().isPresent(), "tools should be present");
        List<com.openai.models.responses.Tool> tools = params.tools().get();
        assertEquals(2, tools.size(), "should have file_search + mcp");

        long fileSearchCount = tools.stream().filter(com.openai.models.responses.Tool::isFileSearch).count();
        long mcpCount = tools.stream().filter(com.openai.models.responses.Tool::isMcp).count();
        assertEquals(1, fileSearchCount, "should contain file_search");
        assertEquals(1, mcpCount, "should contain mcp");

        var fileSearchTool = tools.stream().filter(com.openai.models.responses.Tool::isFileSearch).findFirst().get().asFileSearch();
        assertEquals(List.of(vectorStoreId), fileSearchTool.vectorStoreIds());

        var mcpTool = tools.stream().filter(com.openai.models.responses.Tool::isMcp).findFirst().get().asMcp();
        assertEquals("ydb-tickets", mcpTool.serverLabel());
        assertEquals(mcpUrl, mcpTool.serverUrl().orElse(null));
        assertTrue(mcpTool.allowedTools().isPresent());
    }

    @Test
    void getResponse_withoutVectorStoreId_includesOnlyMcp() {
        Response mockResp = mockResponse("Reply");
        when(openAIClient.responses()).thenReturn(responseService);
        when(responseService.create(any(ResponseCreateParams.class))).thenReturn(mockResp);

        String mcpUrl = "https://mcp.example.com";
        String agentId = "agent-1";

        AgentClient client = new AgentClient("key", agentId, "org", mcpUrl, null, openAIClient);

        client.getResponse("{\"user_id\":\"u\",\"text\":\"hi\"}");

        ArgumentCaptor<ResponseCreateParams> captor = ArgumentCaptor.forClass(ResponseCreateParams.class);
        verify(responseService).create(captor.capture());
        ResponseCreateParams params = captor.getValue();

        assertTrue(params.tools().isPresent());
        List<com.openai.models.responses.Tool> tools = params.tools().get();
        assertEquals(1, tools.size());
        assertTrue(tools.get(0).isMcp());
        assertEquals(0, tools.stream().filter(com.openai.models.responses.Tool::isFileSearch).count());
    }

    @Test
    void getResponse_withBlankVectorStoreId_includesOnlyMcp() {
        Response mockResp = mockResponse("Reply");
        when(openAIClient.responses()).thenReturn(responseService);
        when(responseService.create(any(ResponseCreateParams.class))).thenReturn(mockResp);

        AgentClient client = new AgentClient("key", "agent-1", "org", "https://mcp.example.com", "   ", openAIClient);

        client.getResponse("test");

        ArgumentCaptor<ResponseCreateParams> captor = ArgumentCaptor.forClass(ResponseCreateParams.class);
        verify(responseService).create(captor.capture());
        ResponseCreateParams params = captor.getValue();

        assertTrue(params.tools().isPresent());
        List<com.openai.models.responses.Tool> tools = params.tools().get();
        assertEquals(1, tools.size());
        assertTrue(tools.get(0).isMcp());
    }

    @Test
    void getResponseWithUsage_returnsTokensAndLatency() {
        Response mockResp = mockResponse("Agent reply");
        when(openAIClient.responses()).thenReturn(responseService);
        when(responseService.create(any(ResponseCreateParams.class))).thenReturn(mockResp);

        AgentClient client = new AgentClient("key", "agent-1", "org", "https://mcp.example.com", "vs_123", openAIClient);
        AgentClient.AgentResult result = client.getResponseWithUsage("test");

        assertEquals("Agent reply", result.text());
        assertEquals(10L, result.inputTokens());
        assertEquals(20L, result.outputTokens());
        assertEquals("resp_123", result.responseId());
        assertTrue(result.latencyMs() >= 0);
        assertEquals("yandexgpt", result.model());
        assertNull(result.ticketId());
    }

    @Test
    void getResponseWithUsage_missingUsage_returnsZero() {
        Response mockResp = mock(Response.class);
        lenient().when(mockResp.id()).thenReturn("resp_456");
        ResponseOutputItem outputItem = mock(ResponseOutputItem.class);
        ResponseOutputMessage message = mock(ResponseOutputMessage.class);
        ResponseOutputMessage.Content content = mock(ResponseOutputMessage.Content.class);
        ResponseOutputText outputText = mock(ResponseOutputText.class);
        when(outputText.text()).thenReturn("hi");
        when(content.asOutputText()).thenReturn(outputText);
        when(message.content()).thenReturn(List.of(content));
        when(outputItem.message()).thenReturn(Optional.of(message));
        lenient().when(mockResp.output()).thenReturn(List.of(outputItem));
        lenient().when(mockResp.usage()).thenReturn(Optional.empty());
        // model empty fallback
        lenient().when(mockResp.model()).thenReturn(com.openai.models.ResponsesModel.ofString(""));
        lenient().when(mockResp._model()).thenReturn(com.openai.core.JsonField.of(com.openai.models.ResponsesModel.ofString("")));

        when(openAIClient.responses()).thenReturn(responseService);
        when(responseService.create(any(ResponseCreateParams.class))).thenReturn(mockResp);

        AgentClient client = new AgentClient("key", "a", "o", "https://mcp", null, openAIClient);
        AgentClient.AgentResult result = client.getResponseWithUsage("test");
        assertEquals(0L, result.inputTokens());
        assertEquals(0L, result.outputTokens());
        assertEquals("", result.model());
        assertNull(result.ticketId());
    }
}
