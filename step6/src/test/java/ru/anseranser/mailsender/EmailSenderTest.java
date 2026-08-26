package ru.anseranser.mailsender;

import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Transport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link EmailSender}.
 * <p>
 * Verifies SMTP configuration and message construction.
 * Transport.send is mocked to avoid real SMTP connections.
 */
@ExtendWith(MockitoExtension.class)
class EmailSenderTest {

    @Test
    void send_setsCorrectRecipients() throws Exception {
        // Mock Transport.send to avoid real SMTP
        try (var mockedStatic = mockStatic(Transport.class)) {
            EmailSender emailSender = new EmailSender(
                    "smtp.test.ru", "465", "user@test.ru", "pass", "from@test.ru"
            );

            emailSender.send("recipient@example.com", "Test Subject", "Hello Body");

            ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
            mockedStatic.verify(() -> Transport.send(captor.capture()));

            Message sent = captor.getValue();
            assertEquals("recipient@example.com", sent.getAllRecipients()[0].toString());
            assertEquals("Test Subject", sent.getSubject());
        }
    }

    @Test
    void send_setsCorrectFrom() throws Exception {
        try (var mockedStatic = mockStatic(Transport.class)) {
            EmailSender emailSender = new EmailSender(
                    "smtp.test.ru", "465", "user@test.ru", "pass", "custom-from@test.ru"
            );

            emailSender.send("to@test.ru", "Subject", "Body");

            ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
            mockedStatic.verify(() -> Transport.send(captor.capture()));

            Message sent = captor.getValue();
            assertEquals("custom-from@test.ru", sent.getFrom()[0].toString());
        }
    }

    @Test
    void send_throwsMessagingException_onSmtpFailure() throws Exception {
        try (var mockedStatic = mockStatic(Transport.class)) {
            mockedStatic.when(() -> Transport.send(any(Message.class)))
                    .thenThrow(new MessagingException("SMTP connection failed"));

            EmailSender emailSender = new EmailSender(
                    "smtp.test.ru", "465", "user@test.ru", "pass", "from@test.ru"
            );

            assertThrows(MessagingException.class,
                    () -> emailSender.send("to@test.ru", "Subject", "Body"));
        }
    }
}
