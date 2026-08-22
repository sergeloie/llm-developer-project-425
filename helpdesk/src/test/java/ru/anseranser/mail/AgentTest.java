package ru.anseranser.mail;

import org.junit.jupiter.api.Test;
import yandex.cloud.sdk.functions.YcFunctionContext;

import java.net.URI;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;

public class AgentTest {

    @Test
    void test01() {
        AgentClient agentClient = new AgentClient(System.getenv("YANDEX_API_KEY"),
                System.getenv("AGENT_ID"),
                System.getenv("ORGANIZATION_ID"),
                System.getenv("MCP_SERVER_URL"));

        agentClient.getResponse("{\"user_id\": \"bo122t@assistant.ai\",\"text\": \"Test 22.08.2026 13.20\"}");
    }

    @Test
    void testMail() {
        HttpHeaders headers = HttpRequest.newBuilder()
                .uri(URI.create("https://unused.example.com"))
                .header("Lambda-Runtime-Aws-Request-Id", "test-request-id-001")
                .header("Lambda-Runtime-Function-Name", "email-handler")
                .header("Lambda-Runtime-Function-Version", "1")
                .header("Lambda-Runtime-Memory-Limit", "128")
                .header("Lambda-Runtime-Token-Json", "{}")
                .build()
                .headers();

        EmailHandler emailHandler = new EmailHandler();
        emailHandler.handle("test", new YcFunctionContext(headers));
    }
}
