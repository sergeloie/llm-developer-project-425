package ru.anseranser.mail;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SmtpEmailSenderTest {

    @Test
    void constructor_createsInstance() {
        SmtpEmailSender sender = new SmtpEmailSender("smtp.example.com", "465", "user", "pass", "from@example.com");
        assertNotNull(sender);
    }

    @Test
    void send_invalidHostThrowsMessagingException() {
        SmtpEmailSender sender = new SmtpEmailSender("invalid.host.invalid", "465", "user", "pass", "from@example.com");
        assertThrows(jakarta.mail.MessagingException.class,
                () -> sender.send("to@example.com", "subject", "body"));
    }

    @Test
    void send_hasCorrectMethodSignature() throws Exception {
        assertNotNull(SmtpEmailSender.class.getMethod("send", String.class, String.class, String.class));
    }

    @Test
    void classIsInCorrectPackage() {
        assertEquals("ru.anseranser.mail", SmtpEmailSender.class.getPackageName());
    }
}
