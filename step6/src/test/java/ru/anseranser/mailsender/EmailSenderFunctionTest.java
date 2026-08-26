package ru.anseranser.mailsender;

import jakarta.mail.MessagingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link EmailSenderFunction}.
 * <p>
 * Verifies JSON parsing, validation, and error handling.
 */
@ExtendWith(MockitoExtension.class)
class EmailSenderFunctionTest {

    @Mock
    private EmailSender sender;

    private EmailSenderFunction function;

    @BeforeEach
    void setUp() {
        function = new EmailSenderFunction(sender);
    }

    @Test
    void handle_validRequest_sendsEmail() throws Exception {
        String input = """
                {
                    "to": "operator@example.com",
                    "subject": "[Escalation] 3 tickets",
                    "body": "Digest content"
                }
                """;

        String result = function.handle(input, null);

        verify(sender).send("operator@example.com", "[Escalation] 3 tickets", "Digest content");
        assertTrue(result.contains("\"statusCode\":200"));
        assertTrue(result.contains("\"status\":\"sent\""));
        assertTrue(result.contains("\"to\":\"operator@example.com\""));
    }

    @Test
    void handle_missingTo_returns400() {
        String input = """
                {
                    "subject": "Test",
                    "body": "Hello"
                }
                """;

        String result = function.handle(input, null);

        assertTrue(result.contains("\"statusCode\":400"));
        assertTrue(result.contains("to"));
        verifyNoInteractions(sender);
    }

    @Test
    void handle_emptyTo_returns400() {
        String input = """
                {
                    "to": "",
                    "subject": "Test",
                    "body": "Hello"
                }
                """;

        String result = function.handle(input, null);

        assertTrue(result.contains("\"statusCode\":400"));
        verifyNoInteractions(sender);
    }

    @Test
    void handle_missingSubject_usesDefault() throws Exception {
        String input = """
                {
                    "to": "a@b.com",
                    "body": "Hello"
                }
                """;

        function.handle(input, null);

        verify(sender).send("a@b.com", "No Subject", "Hello");
    }

    @Test
    void handle_missingBody_usesEmpty() throws Exception {
        String input = """
                {
                    "to": "a@b.com",
                    "subject": "Test"
                }
                """;

        function.handle(input, null);

        verify(sender).send("a@b.com", "Test", "");
    }

    @Test
    void handle_smtpError_returns502() throws Exception {
        String input = """
                {
                    "to": "a@b.com",
                    "subject": "Test",
                    "body": "Hello"
                }
                """;
        doThrow(new MessagingException("Connection refused"))
                .when(sender).send(anyString(), anyString(), anyString());

        String result = function.handle(input, null);

        assertTrue(result.contains("\"statusCode\":502"));
        assertTrue(result.contains("SMTP error"));
    }

    @Test
    void handle_invalidJson_returns400() {
        String result = function.handle("not json", null);

        assertTrue(result.contains("\"statusCode\":400"));
        assertTrue(result.contains("Invalid request body"));
    }

    @Test
    void handle_nullInput_returns400() {
        String result = function.handle(null, null);

        assertTrue(result.contains("\"statusCode\":400"));
    }

    @Test
    void handle_emptyObject_returns400() {
        String result = function.handle("{}", null);

        assertTrue(result.contains("\"statusCode\":400"));
    }

    @Test
    void handle_jsonArray_returns400() {
        String result = function.handle("[]", null);

        assertTrue(result.contains("\"statusCode\":400"));
    }


}
