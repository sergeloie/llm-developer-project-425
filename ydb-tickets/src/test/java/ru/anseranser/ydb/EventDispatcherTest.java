package ru.anseranser.ydb;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EventDispatcherTest {

    private EventDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new EventDispatcher();
    }

    // direct invoke
    @Test
    void dispatch_directCreateTicket() {
        String event = "{\"action\":\"create-ticket\",\"user_id\":\"u1\",\"category\":\"bug\",\"text\":\"hello\"}";
        assertEquals("create-ticket", dispatcher.dispatch(event));
    }

    @Test
    void dispatch_directListMyTickets() {
        String event = "{\"action\":\"list-my-tickets\",\"user_id\":\"u1\"}";
        assertEquals("list-my-tickets", dispatcher.dispatch(event));
    }

    @Test
    void dispatch_directAppendMessage() {
        String event = "{\"action\":\"append-message\",\"ticket_id\":\"t1\",\"role\":\"user\",\"text\":\"hi\"}";
        assertEquals("append-message", dispatcher.dispatch(event));
    }

    @Test
    void dispatch_directUnknownThrows() {
        String event = "{\"action\":\"delete-everything\"}";
        assertThrows(IllegalArgumentException.class, () -> dispatcher.dispatch(event));
    }

    // API Gateway with escaped body
    @Test
    void dispatch_apiGatewayCreateTicket() {
        String body = "{\"user_id\":\"u1\",\"category\":\"bug\",\"text\":\"hello\"}";
        String event = "{\"httpMethod\":\"POST\",\"body\":\"" + body.replace("\"", "\\\"") + "\"}";
        assertEquals("create-ticket", dispatcher.dispatch(event));
    }

    @Test
    void dispatch_apiGatewayListMyTickets() {
        String body = "{\"user_id\":\"u1\"}";
        String event = "{\"httpMethod\":\"POST\",\"body\":\"" + body.replace("\"", "\\\"") + "\"}";
        assertEquals("list-my-tickets", dispatcher.dispatch(event));
    }

    @Test
    void dispatch_apiGatewayAppendMessage() {
        String body = "{\"ticket_id\":\"t1\",\"role\":\"agent\",\"text\":\"hi\"}";
        String event = "{\"httpMethod\":\"POST\",\"body\":\"" + body.replace("\"", "\\\"") + "\"}";
        assertEquals("append-message", dispatcher.dispatch(event));
    }

    @Test
    void dispatch_apiGatewayBodyAsObject() {
        String event = "{\"httpMethod\":\"POST\",\"body\":{\"user_id\":\"u1\",\"category\":\"feature\",\"text\":\"new feature\"}}";
        assertEquals("create-ticket", dispatcher.dispatch(event));
    }

    // MCP Hub (direct keys)
    @Test
    void dispatch_mcpCreateTicket() {
        String event = "{\"user_id\":\"u1\",\"category\":\"bug\",\"text\":\"hello\"}";
        assertEquals("create-ticket", dispatcher.dispatch(event));
    }

    @Test
    void dispatch_mcpListMyTickets() {
        String event = "{\"user_id\":\"u1\"}";
        assertEquals("list-my-tickets", dispatcher.dispatch(event));
    }

    @Test
    void dispatch_mcpAppendMessage() {
        String event = "{\"ticket_id\":\"t1\",\"role\":\"user\",\"text\":\"hi\"}";
        assertEquals("append-message", dispatcher.dispatch(event));
    }

    @Test
    void dispatch_mcpUnknownThrows() {
        String event = "{\"foo\":\"bar\",\"baz\":123}";
        assertThrows(IllegalArgumentException.class, () -> dispatcher.dispatch(event));
    }

    @Test
    void dispatch_invalidJsonThrows() {
        assertThrows(IllegalArgumentException.class, () -> dispatcher.dispatch("not json"));
    }

    @Test
    void dispatch_emptyObjectThrows() {
        assertThrows(IllegalArgumentException.class, () -> dispatcher.dispatch("{}"));
    }

    // parse + detectAction via JsonEventParser delegation
    @Test
    void parse_apiGatewayReturnsInnerNode() {
        String body = "{\"user_id\":\"u2\",\"category\":\"access\",\"text\":\"need access\"}";
        String event = "{\"httpMethod\":\"POST\",\"body\":\"" + body.replace("\"", "\\\"") + "\"}";
        JsonNode node = dispatcher.parse(event);
        assertNotNull(node);
        assertEquals("u2", node.get("user_id").asText());
        assertEquals("create-ticket", dispatcher.detectAction(node));
    }

    @Test
    void detectAction_explicitPriority() {
        JsonNode node = dispatcher.parse("{\"action\":\"list-my-tickets\",\"user_id\":\"u1\",\"category\":\"bug\",\"text\":\"hi\"}");
        assertEquals("list-my-tickets", dispatcher.detectAction(node));
    }
}
