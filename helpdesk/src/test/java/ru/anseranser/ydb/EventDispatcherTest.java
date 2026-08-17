package ru.anseranser.ydb;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class EventDispatcherTest {

    private EventDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new EventDispatcher();
    }

    // --- Direct invoke ---

    @Test
    void resolve_directInvoke_createTicket() {
        String event = "{\"action\": \"create-ticket\", \"user_id\": \"u1\", \"category\": \"bug\", \"text\": \"hello\"}";

        DispatchResult result = dispatcher.dispatchAndExtract(event);
        assertEquals(Action.CREATE_TICKET, result.action());
        assertTrue(result.body().contains("user_id"));
    }

    @Test
    void resolve_directInvoke_listMyTickets() {
        String event = "{\"action\": \"list-my-tickets\", \"user_id\": \"u1\"}";

        DispatchResult result = dispatcher.dispatchAndExtract(event);
        assertEquals(Action.LIST_MY_TICKETS, result.action());
    }

    @Test
    void resolve_directInvoke_appendMessage() {
        String event = "{\"action\": \"append-message\", \"ticket_id\": \"t1\", \"text\": \"hi\", \"role\": \"user\"}";

        DispatchResult result = dispatcher.dispatchAndExtract(event);
        assertEquals(Action.APPEND_MESSAGE, result.action());
    }

    @Test
    void resolve_directInvoke_upperCaseAction() {
        String event = "{\"action\": \"CREATE_TICKET\", \"user_id\": \"u1\", \"category\": \"bug\", \"text\": \"hello\"}";

        DispatchResult result = dispatcher.dispatchAndExtract(event);
        assertEquals(Action.CREATE_TICKET, result.action());
    }

    @Test
    void resolve_directInvoke_unknownAction_throws() {
        String event = "{\"action\": \"delete-everything\"}";

        assertThrows(IllegalArgumentException.class, () -> dispatcher.dispatchAndExtract(event));
    }

    // --- API Gateway ---

    @Test
    void resolve_apiGateway_createTicket() {
        String body = "{\"user_id\": \"u1\", \"category\": \"bug\", \"text\": \"hello\"}";
        String event = "{\"httpMethod\": \"POST\", \"body\": \"" + body.replace("\"", "\\\"") + "\"}";

        DispatchResult result = dispatcher.dispatchAndExtract(event);
        assertEquals(Action.CREATE_TICKET, result.action());
        assertEquals(body, result.body());
    }

    @Test
    void resolve_apiGateway_listMyTickets() {
        String body = "{\"user_id\": \"u1\"}";
        String event = "{\"httpMethod\": \"POST\", \"body\": \"" + body.replace("\"", "\\\"") + "\"}";

        DispatchResult result = dispatcher.dispatchAndExtract(event);
        assertEquals(Action.LIST_MY_TICKETS, result.action());
        assertEquals(body, result.body());
    }

    @Test
    void resolve_apiGateway_appendMessage() {
        String body = "{\"ticket_id\": \"t1\", \"text\": \"hi\", \"role\": \"agent\"}";
        String event = "{\"httpMethod\": \"POST\", \"body\": \"" + body.replace("\"", "\\\"") + "\"}";

        DispatchResult result = dispatcher.dispatchAndExtract(event);
        assertEquals(Action.APPEND_MESSAGE, result.action());
        assertEquals(body, result.body());
    }

    @Test
    void resolve_apiGateway_bodyAsJsonObject() {
        String event = "{\"httpMethod\": \"POST\", \"body\": {\"user_id\": \"u1\", \"category\": \"feature\", \"text\": \"new feature\"}}";

        DispatchResult result = dispatcher.dispatchAndExtract(event);
        assertEquals(Action.CREATE_TICKET, result.action());
        assertTrue(result.body().contains("user_id"));
    }

    // --- MCP Hub ---

    @Test
    void resolve_mcpHub_createTicket() {
        String event = "{\"user_id\": \"u1\", \"category\": \"bug\", \"text\": \"hello\"}";

        DispatchResult result = dispatcher.dispatchAndExtract(event);
        assertEquals(Action.CREATE_TICKET, result.action());
    }

    @Test
    void resolve_mcpHub_listMyTickets() {
        String event = "{\"user_id\": \"u1\"}";

        DispatchResult result = dispatcher.dispatchAndExtract(event);
        assertEquals(Action.LIST_MY_TICKETS, result.action());
    }

    @Test
    void resolve_mcpHub_appendMessage() {
        String event = "{\"ticket_id\": \"t1\", \"text\": \"hi\", \"role\": \"user\"}";

        DispatchResult result = dispatcher.dispatchAndExtract(event);
        assertEquals(Action.APPEND_MESSAGE, result.action());
    }

    @Test
    void resolve_mcpHub_unknownKeys_throws() {
        String event = "{\"foo\": \"bar\", \"baz\": 42}";

        assertThrows(IllegalArgumentException.class, () -> dispatcher.dispatchAndExtract(event));
    }

    // --- extractBody equivalent (via body in DispatchResult) ---

    @Test
    void body_apiGateway_returnsBodyString() {
        String body = "{\"user_id\": \"u1\"}";
        String event = "{\"httpMethod\": \"POST\", \"body\": \"" + body.replace("\"", "\\\"") + "\"}";

        DispatchResult result = dispatcher.dispatchAndExtract(event);
        assertEquals(body, result.body());
    }

    @Test
    void body_apiGateway_bodyAsObject_returnsJsonString() {
        String event = "{\"httpMethod\": \"POST\", \"body\": {\"user_id\": \"u1\"}}";

        DispatchResult result = dispatcher.dispatchAndExtract(event);
        assertTrue(result.body().contains("user_id"));
    }

    @Test
    void body_noBody_returnsOriginalEvent() {
        String event = "{\"user_id\": \"u1\", \"category\": \"bug\", \"text\": \"hello\"}";

        DispatchResult result = dispatcher.dispatchAndExtract(event);
        assertTrue(result.body().contains("user_id"));
        assertTrue(result.body().contains("category"));
        assertTrue(result.body().contains("text"));
    }

    // --- Edge cases ---

    @Test
    void resolve_invalidJson_throws() {
        assertThrows(IllegalArgumentException.class, () -> dispatcher.dispatchAndExtract("not json"));
    }

    @Test
    void resolve_emptyObject_throws() {
        assertThrows(IllegalArgumentException.class, () -> dispatcher.dispatchAndExtract("{}"));
    }

    @Test
    void resolve_jsonArray_throws() {
        assertThrows(IllegalArgumentException.class, () -> dispatcher.dispatchAndExtract("[1, 2, 3]"));
    }

    @Test
    void resolve_directInvoke_actionWithUnderscore() {
        String event = "{\"action\": \"CREATE_TICKET\", \"user_id\": \"u1\", \"category\": \"bug\", \"text\": \"hello\"}";

        DispatchResult result = dispatcher.dispatchAndExtract(event);
        assertEquals(Action.CREATE_TICKET, result.action());
    }
}
