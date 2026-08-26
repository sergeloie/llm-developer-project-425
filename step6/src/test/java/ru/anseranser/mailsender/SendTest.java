package ru.anseranser.mailsender;

import org.junit.jupiter.api.Test;
import yandex.cloud.sdk.functions.YcFunctionContext;

import java.net.URI;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;

public class SendTest {

    @Test
    void sendTest() {

        String input = """
                {
                  "subject": "Summary of overdue tickets",
                  "body": [
                    {
                      "category": "bug",
                      "created_at": "2026-08-20T09:01:54.834808Z",
                      "id": "eb2824ac-ad88-4667-8d89-8a592704d832",
                      "recommended_action": "Проверить настройки OAuth",
                      "summary": "Не работает авторизация через OAuth"
                    },
                    {
                      "category": "access",
                      "created_at": "2026-08-20T09:03:30.28318Z",
                      "id": "e388299f-f29f-48f5-8480-b37d98799920",
                      "recommended_action": "Проверить права доступа пользователя",
                      "summary": "Нужен доступ к продакшн базе"
                    }
                  ]
                }
                """;

        EmailSender emailSender = new EmailSender(System.getenv("SMTP_HOST"),
                System.getenv("SMTP_PORT"),
                System.getenv("SMTP_USER"),
                System.getenv("SMTP_PASSWORD"),
                System.getenv("SMTP_USER"));
        EmailSenderFunction emailSenderFunction = new EmailSenderFunction(emailSender, System.getenv("HELPDESK_MAILBOX"));

        HttpHeaders headers = HttpRequest.newBuilder()
                .uri(URI.create("https://unused.example.com"))
                .header("Lambda-Runtime-Aws-Request-Id", "test-request-id-001")
                .header("Lambda-Runtime-Function-Name", "email-handler")
                .header("Lambda-Runtime-Function-Version", "1")
                .header("Lambda-Runtime-Memory-Limit", "128")
                .header("Lambda-Runtime-Token-Json", "{}")
                .build()
                .headers();

        emailSenderFunction.handle(input, new YcFunctionContext(headers));
    }
}
