package ru.anseranser.mail;

import org.junit.jupiter.api.Test;

public class AgentTest {

    @Test
    void test01() {
        AgentClient agentClient = new AgentClient(System.getenv("YANDEX_API_KEY"),
                System.getenv("AGENT_ID"),
                System.getenv("ORGANIZATION_ID"),
                System.getenv("MCP_SERVER_URL"));

        agentClient.getResponse("{\"user_id\": \"bo122t@assistant.ai\",\"text\": \"Всё сломалось, слышишь. Прод упал, ничего не работает\"}");
    }
}
