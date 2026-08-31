package ru.anseranser.json;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JsonEventParserTest {

    @Test
    void parse_nullReturnsNull() {
        assertNull(JsonEventParser.parse(null));
    }

    @Test
    void parse_blankReturnsNull() {
        assertNull(JsonEventParser.parse("   "));
    }

    @Test
    void parse_invalidJsonReturnsNull() {
        assertNull(JsonEventParser.parse("not json"));
    }

    @Test
    void parse_directJson() {
        JsonNode node = JsonEventParser.parse("{\"action\":\"create-ticket\",\"user_id\":\"u1\"}");
        assertNotNull(node);
        assertEquals("create-ticket", node.get("action").asText());
    }

    @Test
    void parse_apiGatewayWrapping() {
        String body = "{\"action\":\"list-my-tickets\",\"user_id\":\"u2\"}";
        String escaped = body.replace("\"", "\\\"");
        String gateway = "{\"httpMethod\":\"POST\",\"body\":\"" + escaped + "\"}";
        JsonNode node = JsonEventParser.parse(gateway);
        assertNotNull(node);
        assertEquals("list-my-tickets", node.get("action").asText());
        assertEquals("u2", node.get("user_id").asText());
    }

    @Test
    void parse_apiGatewayEmptyBodyReturnsRoot() {
        String gateway = "{\"httpMethod\":\"POST\",\"body\":\"\"}";
        JsonNode node = JsonEventParser.parse(gateway);
        assertNotNull(node);
        assertTrue(node.has("httpMethod"));
    }

    @Test
    void detectAction_explicitAction() {
        JsonNode node = JsonEventParser.parse("{\"action\":\"append-message\"}");
        assertEquals("append-message", JsonEventParser.detectAction(node));
    }

    @Test
    void detectAction_createTicketByKeys() {
        JsonNode node = JsonEventParser.parse("{\"user_id\":\"u1\",\"category\":\"bug\",\"text\":\"hello\"}");
        assertEquals("create-ticket", JsonEventParser.detectAction(node));
    }

    @Test
    void detectAction_listMyTicketsByKeys() {
        JsonNode node = JsonEventParser.parse("{\"user_id\":\"u1\"}");
        assertEquals("list-my-tickets", JsonEventParser.detectAction(node));
    }

    @Test
    void detectAction_appendMessageByKeys() {
        JsonNode node = JsonEventParser.parse("{\"ticket_id\":\"t1\",\"role\":\"user\",\"text\":\"hi\"}");
        assertEquals("append-message", JsonEventParser.detectAction(node));
    }

    @Test
    void detectAction_unknownReturnsNull() {
        JsonNode node = JsonEventParser.parse("{\"foo\":\"bar\"}");
        assertNull(JsonEventParser.detectAction(node));
    }

    @Test
    void detectAction_nullReturnsNull() {
        assertNull(JsonEventParser.detectAction(null));
    }

    @Test
    void detectAction_apiGatewayCreateTicket() {
        String body = "{\"user_id\":\"u1\",\"category\":\"access\",\"text\":\"need access\"}";
        String escaped = body.replace("\"", "\\\"");
        String gateway = "{\"httpMethod\":\"POST\",\"body\":\"" + escaped + "\"}";
        JsonNode node = JsonEventParser.parse(gateway);
        assertEquals("create-ticket", JsonEventParser.detectAction(node));
    }

    @Test
    void detectAction_preferExplicitActionOverKeys() {
        JsonNode node = JsonEventParser.parse("{\"action\":\"list-my-tickets\",\"user_id\":\"u1\",\"category\":\"bug\",\"text\":\"hi\"}");
        assertEquals("list-my-tickets", JsonEventParser.detectAction(node));
    }
}
