package ru.anseranser.prod;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponsePrompt;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for {@link EmailHandler}.
 * <p>
 * Environment variables are set manually via reflection so you don't need to
 * configure them in the OS or IDE run-configuration.
 * Fill in the values below and remove {@code @Disabled} to run the test
 * against a real mailbox.
 */
@Disabled("Fill in credentials and remove @Disabled to run")
public class EmailHandlerTest {

    // ======== FILL IN YOUR CREDENTIALS HERE ========
    private static final String AGENT_ID = System.getenv("AGENT_ID");
    private static final String ORGANIZATION_ID = System.getenv("ORGANIZATION_ID");
    private static final String YANDEX_API_KEY = System.getenv("YANDEX_API_KEY");
    // ================================================

    /**
     * Creates an {@link EmailHandler} instance and injects the credentials
     * via reflection so we don't rely on OS-level environment variables.
     */
    private EmailHandler createConfiguredHandler() throws Exception {
        EmailHandler handler = new EmailHandler();

        setField(handler, "IMAP_HOST", System.getenv("IMAP_HOST"));
        setField(handler, "IMAP_PORT", System.getenv("IMAP_PORT"));
        setField(handler, "IMAP_USER", System.getenv("IMAP_USER"));
        setField(handler, "IMAP_PASSWORD", System.getenv("IMAP_PASSWORD"));
        setField(handler, "SMTP_HOST", System.getenv("SMTP_HOST"));
        setField(handler, "SMTP_PORT", System.getenv("SMTP_PORT"));
        setField(handler, "SMTP_USER", System.getenv("SMTP_USER"));
        setField(handler, "SMTP_PASSWORD", System.getenv("SMTP_PASSWORD"));
        setField(handler, "HELPDESK_MAILBOX", System.getenv("HELPDESK_MAILBOX"));

        return handler;
    }

    @Test
    void handle_shouldProcessUnreadEmailsAndReturnCount() throws Exception {
        EmailHandler handler = createConfiguredHandler();

        try {
            String result = handler.handle("Text", null);

            assertNotNull(result, "Result should not be null");
            assertTrue(result.endsWith(" mail(s) done"),
                    "Result should match pattern '<N> mail(s) done', got: " + result);

            System.out.println("=== Handle result: " + result + " ===");
        } finally {
            handler.disconnect();
        }
    }

    @Test
    void disconnect_shouldNotThrow() throws Exception {
        EmailHandler handler = createConfiguredHandler();
        assertDoesNotThrow(handler::disconnect,
                "disconnect() should not throw even when called on a fresh instance");
    }

    // --------------- helpers ---------------

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    void agentTest() {
        String request = "моя роутера паламалася";

        OpenAIClient client = OpenAIOkHttpClient.builder()
                .apiKey(YANDEX_API_KEY)
                .baseUrl("https://ai.api.cloud.yandex.net/v1")
                .organization(ORGANIZATION_ID)
                .build();

        ResponseCreateParams params = ResponseCreateParams.builder()
                .prompt(ResponsePrompt.builder()
                        .id(AGENT_ID)
                        .build())
                .input(request)
                .build();

        Response response = client.responses().create(params);
        String modelResponse = response.output().getFirst().message().get().content().getFirst().asOutputText().text();
        System.out.printf("Response id: %s. Request: %s. Response: %s. Input Tokens: %d. Output Tokens: %d.%s",
                response.id(),
                request,
                modelResponse,
                response.usage().get().inputTokens(),
                response.usage().get().outputTokens(),
                System.lineSeparator());
    }
}
