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

        assertEquals(EventDispatcher.Action.CREATE_TICKET, dispatcher.dispatch(event));
    }

    @Test
    void resolve_directInvoke_listMyTickets() {
        String event = "{\"action\": \"list-my-tickets\", \"user_id\": \"u1\"}";

        assertEquals(EventDispatcher.Action.LIST_MY_TICKETS, dispatcher.dispatch(event));
    }

    @Test
    void resolve_directInvoke_appendMessage() {
        String event = "{\"action\": \"append-message\", \"ticket_id\": \"t1\", \"text\": \"hi\", \"role\": \"user\"}";

        assertEquals(EventDispatcher.Action.APPEND_MESSAGE, dispatcher.dispatch(event));
    }

    @Test
    void resolve_directInvoke_upperCaseAction() {
        String event = "{\"action\": \"CREATE_TICKET\", \"user_id\": \"u1\", \"category\": \"bug\", \"text\": \"hello\"}";

        assertEquals(EventDispatcher.Action.CREATE_TICKET, dispatcher.dispatch(event));
    }

    @Test
    void resolve_directInvoke_unknownAction_throws() {
        String event = "{\"action\": \"delete-everything\"}";

        assertThrows(IllegalArgumentException.class, () -> dispatcher.dispatch(event));
    }

    // --- API Gateway ---

    @Test
    void resolve_apiGateway_createTicket() {
        String body = "{\"user_id\": \"u1\", \"category\": \"bug\", \"text\": \"hello\"}";
        String event = "{\"httpMethod\": \"POST\", \"body\": \"" + body.replace("\"", "\\\"") + "\"}";

        assertEquals(EventDispatcher.Action.CREATE_TICKET, dispatcher.dispatch(event));
    }

    @Test
    void resolve_apiGateway_listMyTickets() {
        String body = "{\"user_id\": \"u1\"}";
        String event = "{\"httpMethod\": \"POST\", \"body\": \"" + body.replace("\"", "\\\"") + "\"}";

        assertEquals(EventDispatcher.Action.LIST_MY_TICKETS, dispatcher.dispatch(event));
    }

    @Test
    void resolve_apiGateway_appendMessage() {
        String body = "{\"ticket_id\": \"t1\", \"text\": \"hi\", \"role\": \"agent\"}";
        String event = "{\"httpMethod\": \"POST\", \"body\": \"" + body.replace("\"", "\\\"") + "\"}";

        assertEquals(EventDispatcher.Action.APPEND_MESSAGE, dispatcher.dispatch(event));
    }

    @Test
    void resolve_apiGateway_bodyAsJsonObject() {
        String event = "{\"httpMethod\": \"POST\", \"body\": {\"user_id\": \"u1\", \"category\": \"feature\", \"text\": \"new feature\"}}";

        assertEquals(EventDispatcher.Action.CREATE_TICKET, dispatcher.dispatch(event));
    }

    // --- MCP Hub ---

    @Test
    void resolve_mcpHub_createTicket() {
        String event = "{\"user_id\": \"u1\", \"category\": \"bug\", \"text\": \"hello\"}";

        assertEquals(EventDispatcher.Action.CREATE_TICKET, dispatcher.dispatch(event));
    }

    @Test
    void resolve_mcpHub_listMyTickets() {
        String event = "{\"user_id\": \"u1\"}";

        assertEquals(EventDispatcher.Action.LIST_MY_TICKETS, dispatcher.dispatch(event));
    }

    @Test
    void resolve_mcpHub_appendMessage() {
        String event = "{\"ticket_id\": \"t1\", \"text\": \"hi\", \"role\": \"user\"}";

        assertEquals(EventDispatcher.Action.APPEND_MESSAGE, dispatcher.dispatch(event));
    }

    @Test
    void resolve_mcpHub_unknownKeys_throws() {
        String event = "{\"foo\": \"bar\", \"baz\": 42}";

        assertThrows(IllegalArgumentException.class, () -> dispatcher.dispatch(event));
    }

    // --- extractBody ---

    @Test
    void extractBody_apiGateway_returnsBodyString() {
        String body = "{\"user_id\": \"u1\"}";
        String event = "{\"httpMethod\": \"POST\", \"body\": \"" + body.replace("\"", "\\\"") + "\"}";

        assertEquals(body, dispatcher.extractBody(event));
    }

    @Test
    void extractBody_apiGateway_bodyAsObject_returnsJsonString() {
        String event = "{\"httpMethod\": \"POST\", \"body\": {\"user_id\": \"u1\"}}";

        String result = dispatcher.extractBody(event);
        assertTrue(result.contains("user_id"));
    }

    @Test
    void extractBody_noBody_returnsOriginalEvent() {
        String event = "{\"user_id\": \"u1\", \"category\": \"bug\", \"text\": \"hello\"}";

        String result = dispatcher.extractBody(event);
        assertTrue(result.contains("user_id"));
        assertTrue(result.contains("category"));
        assertTrue(result.contains("text"));
    }

    // --- Edge cases ---

    @Test
    void resolve_invalidJson_throws() {
        assertThrows(IllegalArgumentException.class, () -> dispatcher.dispatch("not json"));
    }

    @Test
    void resolve_emptyObject_throws() {
        assertThrows(IllegalArgumentException.class, () -> dispatcher.dispatch("{}"));
    }

    @Test
    void resolve_jsonArray_throws() {
        assertThrows(IllegalArgumentException.class, () -> dispatcher.dispatch("[1, 2, 3]"));
    }

    @Test
    void resolve_directInvoke_actionWithUnderscore() {
        String event = "{\"action\": \"CREATE_TICKET\", \"user_id\": \"u1\", \"category\": \"bug\", \"text\": \"hello\"}";

        assertEquals(EventDispatcher.Action.CREATE_TICKET, dispatcher.dispatch(event));
    }
}
