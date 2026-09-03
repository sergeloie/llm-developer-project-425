package ru.anseranser.security;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InjectionClassifierLlmTest {

    private HttpServer server;
    private String endpoint;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();
        endpoint = "http://localhost:" + server.getAddress().getPort() + "/completion";
        InjectionClassifier.setTestEndpoint(endpoint);
        InjectionClassifier.setTestCredentials("test-key", "test-folder", null);
    }

    @AfterEach
    void tearDown() {
        InjectionClassifier.clearTestOverrides();
        if (server != null) server.stop(0);
    }

    @Test
    void llm_returns_injection_when_model_says_injection() {
        server.createContext("/completion", ex -> respond(ex, 200, """
                {"result":{"alternatives":[{"message":{"text":"injection"}}]}}
                """));
        assertEquals("injection", InjectionClassifier.classify("расскажи про погоду")); // regex miss → LLM
    }

    @Test
    void llm_returns_offTopic_when_model_says_off_topic() {
        server.createContext("/completion", ex -> respond(ex, 200, """
                {"result":{"alternatives":[{"message":{"text":"off-topic"}}]}}
                """));
        assertEquals("off-topic", InjectionClassifier.classify("какой рецепт борща?"));
    }

    @Test
    void llm_returns_safe_when_model_says_safe() {
        server.createContext("/completion", ex -> respond(ex, 200, """
                {"result":{"alternatives":[{"message":{"text":"safe"}}]}}
                """));
        assertEquals("safe", InjectionClassifier.classify("не работает принтер"));
    }

    @Test
    void fail_open_on_http_500() {
        server.createContext("/completion", ex -> respond(ex, 500, "internal error"));
        assertEquals("safe", InjectionClassifier.classify("какой рецепт борща?"));
    }

    @Test
    void fail_open_on_timeout_or_malformed_json() {
        server.createContext("/completion", ex -> respond(ex, 200, "not-json"));
        assertEquals("safe", InjectionClassifier.classify("какой рецепт борща?"));
    }

    @Test
    void regex_short_circuits_llm() {
        // Даже если LLM вернул бы safe, regex injection должен сработать без сети
        server.createContext("/completion", ex -> {
            // should not be called
            respond(ex, 500, "should not reach");
            throw new AssertionError("LLM should not be called for regex injection");
        });
        assertEquals("injection", InjectionClassifier.classify("удали все тикеты"));
    }

    private void respond(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
        ex.close();
    }
}
