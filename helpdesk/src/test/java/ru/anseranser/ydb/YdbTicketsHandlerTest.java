package ru.anseranser.ydb;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class YdbTicketsHandlerTest {

    @Mock
    private YdbClient ydbClient;

    private EventDispatcher dispatcher;

    private YdbTicketsHandler handler;

    @BeforeEach
    void setUp() {
        dispatcher = new EventDispatcher();
        handler = YdbTicketsHandler.createForTest(ydbClient, dispatcher);
    }

    @Test
    void handle_createTicket_returnsResponse() {
        String event = "{\"user_id\": \"u1\", \"category\": \"bug\", \"text\": \"hello\"}";
        String expectedResponse = "{\"ticket_id\": \"t1\", \"created_at\": \"2025-01-01T00:00:00Z\"}";

        when(ydbClient.createTicket("u1", "bug", "hello")).thenReturn(expectedResponse);

        String response = handler.handle(event, null);

        assertEquals(expectedResponse, response);
        verify(ydbClient).createTicket("u1", "bug", "hello");
    }

    @Test
    void handle_listMyTickets_returnsResponse() {
        String event = "{\"user_id\": \"u1\"}";
        String expectedResponse = "[{\"id\": \"t1\", \"status\": \"open\"}]";

        when(ydbClient.listMyTickets("u1")).thenReturn(expectedResponse);

        String response = handler.handle(event, null);

        assertEquals(expectedResponse, response);
        verify(ydbClient).listMyTickets("u1");
    }

    @Test
    void handle_appendMessage_returnsResponse() {
        String event = "{\"ticket_id\": \"t1\", \"text\": \"hi\", \"role\": \"user\"}";
        String expectedResponse = "{\"message_id\": \"m1\", \"ok\": true}";

        when(ydbClient.appendMessage("t1", "user", "hi", "", 0L, 0L, 0)).thenReturn(expectedResponse);

        String response = handler.handle(event, null);

        assertEquals(expectedResponse, response);
        verify(ydbClient).appendMessage("t1", "user", "hi", "", 0L, 0L, 0);
    }

    @Test
    void handle_appendMessageWithMetadata_returnsResponse() {
        String event = "{\"ticket_id\": \"t1\", \"text\": \"hi\", \"role\": \"agent\", \"model\": \"gpt-4\", \"tokens_in\": 100, \"tokens_out\": 200, \"latency_ms\": 1500}";
        String expectedResponse = "{\"message_id\": \"m1\", \"ok\": true}";

        when(ydbClient.appendMessage("t1", "agent", "hi", "gpt-4", 100L, 200L, 1500)).thenReturn(expectedResponse);

        String response = handler.handle(event, null);

        assertEquals(expectedResponse, response);
        verify(ydbClient).appendMessage("t1", "agent", "hi", "gpt-4", 100L, 200L, 1500);
    }

    @Test
    void handle_apiGateway_createTicket_returnsResponse() {
        String body = "{\"user_id\": \"u1\", \"category\": \"bug\", \"text\": \"hello\"}";
        String event = "{\"httpMethod\": \"POST\", \"body\": \"" + body.replace("\"", "\\\"") + "\"}";
        String expectedResponse = "{\"ticket_id\": \"t1\", \"created_at\": \"2025-01-01T00:00:00Z\"}";

        when(ydbClient.createTicket("u1", "bug", "hello")).thenReturn(expectedResponse);

        String response = handler.handle(event, null);

        assertEquals(expectedResponse, response);
        verify(ydbClient).createTicket("u1", "bug", "hello");
    }

    @Test
    void handle_directInvoke_createTicket_returnsResponse() {
        String event = "{\"action\": \"create-ticket\", \"user_id\": \"u1\", \"category\": \"bug\", \"text\": \"hello\"}";
        String expectedResponse = "{\"ticket_id\": \"t1\", \"created_at\": \"2025-01-01T00:00:00Z\"}";

        when(ydbClient.createTicket("u1", "bug", "hello")).thenReturn(expectedResponse);

        String response = handler.handle(event, null);

        assertEquals(expectedResponse, response);
        verify(ydbClient).createTicket("u1", "bug", "hello");
    }

    @Test
    void handle_directInvoke_listMyTickets_returnsResponse() {
        String event = "{\"action\": \"list-my-tickets\", \"user_id\": \"u1\"}";
        String expectedResponse = "[]";

        when(ydbClient.listMyTickets("u1")).thenReturn(expectedResponse);

        String response = handler.handle(event, null);

        assertEquals(expectedResponse, response);
        verify(ydbClient).listMyTickets("u1");
    }

    @Test
    void handle_directInvoke_appendMessage_returnsResponse() {
        String event = "{\"action\": \"append-message\", \"ticket_id\": \"t1\", \"text\": \"hi\", \"role\": \"user\"}";
        String expectedResponse = "{\"message_id\": \"m1\", \"ok\": true}";

        when(ydbClient.appendMessage("t1", "user", "hi", "", 0L, 0L, 0)).thenReturn(expectedResponse);

        String response = handler.handle(event, null);

        assertEquals(expectedResponse, response);
        verify(ydbClient).appendMessage("t1", "user", "hi", "", 0L, 0L, 0);
    }

    @Test
    void handle_invalidAction_throws() {
        String event = "{\"action\": \"delete-everything\"}";

        assertThrows(IllegalArgumentException.class, () -> handler.handle(event, null));
    }
}
