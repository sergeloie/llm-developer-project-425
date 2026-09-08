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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void innocent_request_reaches_llm_and_uses_model_verdict() throws IOException {
        // Невинный запрос проходит regex → уходит в облачный LLM с правильными
        // заголовками и промптом; ответ модели определяет вердикт классификатора.
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> authHeader = new AtomicReference<>();
        AtomicReference<String> folderHeader = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        server.createContext("/completion", ex -> {
            calls.incrementAndGet();
            authHeader.set(ex.getRequestHeaders().getFirst("Authorization"));
            folderHeader.set(ex.getRequestHeaders().getFirst("x-folder-id"));
            requestBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(ex, 200, """
                    {"result":{"alternatives":[{"message":{"text":"safe"}}]}}
                    """);
        });

        assertEquals("safe", InjectionClassifier.classify("не работает принтер")); // regex miss → LLM

        // 1) Запрос реально ушёл на эндпоинт — ровно один сетевой вызов
        assertEquals(1, calls.get(), "innocent request must be sent to LLM");
        // 2) Авторизация: Api-Key + folder, как в реальном облаке через SA Lockbox
        assertEquals("Api-Key test-key", authHeader.get());
        assertEquals("test-folder", folderHeader.get());
        // 3) Тело запроса: правильная modelUri и наш текст ушёл в промпт
        assertTrue(requestBody.get().contains("\"modelUri\":\"gpt://test-folder/yandexgpt-lite\""));
        assertTrue(requestBody.get().contains("\"maxTokens\":10"));
        assertTrue(requestBody.get().contains("не работает принтер"));
    }

    @Test
    void llm_not_called_when_credentials_missing() {
        // Нет ни YANDEX_API_KEY, ни FOLDER_ID → классификатор остаётся regex-only
        // (fail-open: "safe"), сетевой вызов не производится вообще.
        // Это ровно то состояние, в котором живут облачные функции, если
        // deploy-скрипты не передают креды в --environment (пункт 2 ревью).
        InjectionClassifier.setTestCredentials("", "", ""); // пустые = отсутствуют (без env-fallback)
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/completion", ex -> {
            calls.incrementAndGet();
            respond(ex, 200, """
                    {"result":{"alternatives":[{"message":{"text":"injection"}}]}}
                    """);
        });

        assertEquals("safe", InjectionClassifier.classify("не работает принтер"));
        assertEquals(0, calls.get(), "LLM must not be called without credentials");
    }

    private void respond(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
        ex.close();
    }
}
