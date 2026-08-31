package ru.anseranser.mailsender;

import jakarta.mail.MessagingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.anseranser.mail.SmtpEmailSender;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link EmailSenderFunction}.
 * <p>
 * Verifies JSON parsing, validation, and error handling.
 * The recipient address is taken from the {@code HELPDESK_MAILBOX} environment variable
 * (injected via the package-private test constructor).
 */
@ExtendWith(MockitoExtension.class)
class EmailSenderFunctionTest {

    private static final String TEST_MAILBOX = "operator@example.com";

    @Mock
    private SmtpEmailSender sender;

    private EmailSenderFunction function;

    @BeforeEach
    void setUp() {
        function = new EmailSenderFunction(sender, TEST_MAILBOX);
    }

    @Test
    void handle_validRequest_sendsEmail() throws Exception {
        String input = """
                {
                    "subject": "[Escalation] 3 tickets",
                    "body": "Digest content"
                }
                """;

        String result = function.handle(input, null);

        verify(sender).send(TEST_MAILBOX, "[Escalation] 3 tickets", "Digest content");
        assertTrue(result.contains("\"statusCode\":200"));
        assertTrue(result.contains("\"status\":\"sent\""));
        assertTrue(result.contains("\"to\":\"operator@example.com\""));
    }

    @Test
    void handle_missingHelpdeskMailbox_returns500() {
        EmailSenderFunction noMailboxFunction = new EmailSenderFunction(sender, null);
        String input = """
                {
                    "subject": "Test",
                    "body": "Hello"
                }
                """;

        String result = noMailboxFunction.handle(input, null);

        assertTrue(result.contains("\"statusCode\":500"));
        assertTrue(result.contains("HELPDESK_MAILBOX"));
        verifyNoInteractions(sender);
    }

    @Test
    void handle_blankHelpdeskMailbox_returns500() {
        EmailSenderFunction blankMailboxFunction = new EmailSenderFunction(sender, "   ");
        String input = """
                {
                    "subject": "Test",
                    "body": "Hello"
                }
                """;

        String result = blankMailboxFunction.handle(input, null);

        assertTrue(result.contains("\"statusCode\":500"));
        assertTrue(result.contains("HELPDESK_MAILBOX"));
        verifyNoInteractions(sender);
    }

    @Test
    void handle_missingSubject_usesDefault() throws Exception {
        String input = """
                {
                    "body": "Hello"
                }
                """;

        function.handle(input, null);

        verify(sender).send(TEST_MAILBOX, "No Subject", "Hello");
    }

    @Test
    void handle_missingBody_usesEmpty() throws Exception {
        String input = """
                {
                    "subject": "Test"
                }
                """;

        function.handle(input, null);

        verify(sender).send(TEST_MAILBOX, "Test", "");
    }

    @Test
    void handle_smtpError_returns502() throws Exception {
        String input = """
                {
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
        EmailSenderFunction noMailboxFunction = new EmailSenderFunction(sender, null);
        String result = noMailboxFunction.handle("{}", null);

        assertTrue(result.contains("\"statusCode\":500"));
    }

    @Test
    void handle_jsonArray_returns400() {
        String result = function.handle("[]", null);

        assertTrue(result.contains("\"statusCode\":400"));
    }

    @Test
    void handle_arrayBody_sendsSerializedJson() throws Exception {
        String input = """
                {
                    "subject": "Summary of overdue tickets",
                    "body": [
                        {
                            "category": "bug",
                            "summary": "OAuth broken",
                            "recommended_action": "Check OAuth"
                        },
                        {
                            "category": "access",
                            "summary": "Need prod access",
                            "recommended_action": "Check permissions"
                        }
                    ]
                }
                """;

        String result = function.handle(input, null);

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(sender).send(eq(TEST_MAILBOX), eq("Summary of overdue tickets"), bodyCaptor.capture());

        String capturedBody = bodyCaptor.getValue();
        assertTrue(capturedBody.contains("\"category\""), "Body should contain JSON array content");
        assertTrue(capturedBody.contains("\"bug\""), "Body should contain first element");
        assertTrue(capturedBody.contains("\"access\""), "Body should contain second element");

        assertTrue(result.contains("\"statusCode\":200"));
        assertTrue(result.contains("\"status\":\"sent\""));
    }

    @Test
    void handle_objectBody_sendsSerializedJson() throws Exception {
        String input = """
                {
                    "subject": "Ticket details",
                    "body": {
                        "category": "bug",
                        "summary": "Login fails",
                        "recommended_action": "Check logs"
                    }
                }
                """;

        String result = function.handle(input, null);

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(sender).send(eq(TEST_MAILBOX), eq("Ticket details"), bodyCaptor.capture());

        String capturedBody = bodyCaptor.getValue();
        assertTrue(capturedBody.contains("\"category\""), "Body should contain JSON object content");
        assertTrue(capturedBody.contains("\"bug\""), "Body should contain the category value");

        assertTrue(result.contains("\"statusCode\":200"));
        assertTrue(result.contains("\"status\":\"sent\""));
    }

}
