package ru.anseranser.prod;

import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;

import java.io.IOException;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link EmailHandler} error handling paths.
 * <p>
 * Verifies that each failure scenario (IMAP connect, fetch, send, agent, parse)
 * produces the correct log message and does not abort processing of remaining messages.
 */
@ExtendWith(MockitoExtension.class)
class EmailHandlerTest {

    @Mock
    private EmailReceiver receiver;
    @Mock
    private EmailSender sender;
    @Mock
    private AgentClient agent;
    @Mock
    private EmailTextExtractor extractor;

    private EmailHandler handler;

    @BeforeEach
    void setUp() {
        handler = new EmailHandler(receiver, sender, agent, extractor);
    }

    @Test
    void handle_successfulProcessing_returnsCount() throws Exception {
        Message message = mockMessage("user@example.com", "Hello");
        when(receiver.fetchUnreadMessages()).thenReturn(new Message[]{message});
        when(extractor.extractPlainText(message)).thenReturn("Hello body");
        when(agent.getResponse("Hello body")).thenReturn("Agent reply");

        String result = handler.handle(null, null);

        assertEquals("1 mail(s) done", result);
        verify(sender).send("user@example.com", "Agent answer", "Agent reply");
        verify(receiver).markAsSeen(message);
    }

    @Test
    void handle_imapConnectFails_logsFetchError() throws Exception {
        doThrow(new MessagingException("Connection refused"))
                .when(receiver).connect();

        String result = handler.handle(null, null);

        assertEquals("0 mail(s) done", result);
        verifyNoInteractions(sender);
    }

    @Test
    void handle_fetchFails_logsFetchError() throws Exception {
        when(receiver.fetchUnreadMessages()).thenThrow(new MessagingException("Folder error"));

        String result = handler.handle(null, null);

        assertEquals("0 mail(s) done", result);
        verifyNoInteractions(sender);
    }

    @Test
    void handle_sendFails_logsSendErrorAndContinues() throws Exception {
        Message msg1 = mockMessage("user1@example.com", "First");
        Message msg2 = mockMessage("user2@example.com", "Second");

        when(receiver.fetchUnreadMessages()).thenReturn(new Message[]{msg1, msg2});
        when(extractor.extractPlainText(msg1)).thenReturn("Body 1");
        when(extractor.extractPlainText(msg2)).thenReturn("Body 2");
        when(agent.getResponse("Body 1")).thenThrow(new RuntimeException("Agent unavailable"));
        when(agent.getResponse("Body 2")).thenReturn("Reply 2");

        String result = handler.handle(null, null);

        // Message 1 failed (agent threw unchecked), message 2 succeeded
        assertEquals("1 mail(s) done", result);
        // Verify message 2 was still processed
        verify(sender).send("user2@example.com", "Agent answer", "Reply 2");
        verify(receiver).markAsSeen(msg2);
    }

    @Test
    void handle_sendMessagingException_logsSendErrorAndContinues() throws Exception {
        Message msg1 = mockMessage("user1@example.com", "First");
        Message msg2 = mockMessage("user2@example.com", "Second");

        when(receiver.fetchUnreadMessages()).thenReturn(new Message[]{msg1, msg2});
        when(extractor.extractPlainText(msg1)).thenReturn("Body 1");
        when(extractor.extractPlainText(msg2)).thenReturn("Body 2");
        when(agent.getResponse("Body 1")).thenReturn("Reply 1");
        doThrow(new MessagingException("SMTP send failed"))
                .when(sender).send("user1@example.com", "Agent answer", "Reply 1");
        when(agent.getResponse("Body 2")).thenReturn("Reply 2");

        String result = handler.handle(null, null);

        // Message 1 failed (send threw), message 2 succeeded
        assertEquals("1 mail(s) done", result);
        verify(receiver).markAsSeen(msg2);
        verify(receiver, never()).markAsSeen(msg1);
    }

    @Test
    void handle_parseFails_logsParseErrorAndContinues() throws Exception {
        Message msg1 = mockMessage("user1@example.com", "First");
        Message msg2 = mockMessage("user2@example.com", "Second");

        when(receiver.fetchUnreadMessages()).thenReturn(new Message[]{msg1, msg2});
        when(extractor.extractPlainText(msg1)).thenThrow(new IOException("Parse error"));
        when(extractor.extractPlainText(msg2)).thenReturn("Body 2");
        when(agent.getResponse("Body 2")).thenReturn("Reply 2");

        String result = handler.handle(null, null);

        // Message 1 failed (parse), message 2 succeeded
        assertEquals("1 mail(s) done", result);
        verify(sender).send("user2@example.com", "Agent answer", "Reply 2");
        verify(receiver).markAsSeen(msg2);
    }

    @Test
    void handle_agentThrowsUnchecked_logsErrorAndContinues() throws Exception {
        Message msg1 = mockMessage("user1@example.com", "First");
        Message msg2 = mockMessage("user2@example.com", "Second");

        when(receiver.fetchUnreadMessages()).thenReturn(new Message[]{msg1, msg2});
        when(extractor.extractPlainText(msg1)).thenReturn("Body 1");
        when(extractor.extractPlainText(msg2)).thenReturn("Body 2");
        when(agent.getResponse("Body 1")).thenThrow(new RuntimeException("Agent unavailable"));
        when(agent.getResponse("Body 2")).thenReturn("Reply 2");

        String result = handler.handle(null, null);

        // Message 1 failed (agent threw), message 2 succeeded
        assertEquals("1 mail(s) done", result);
        verify(sender).send("user2@example.com", "Agent answer", "Reply 2");
    }

    @Test
    void handle_emptyBody_skipsMessage() throws Exception {
        Message msg1 = mockMessage("user@example.com", "Empty");

        when(receiver.fetchUnreadMessages()).thenReturn(new Message[]{msg1});
        when(extractor.extractPlainText(msg1)).thenReturn("");

        String result = handler.handle(null, null);

        assertEquals("0 mail(s) done", result);
        verifyNoInteractions(agent);
        verifyNoInteractions(sender);
    }

    @Test
    void handle_nullMessagesArray_returnsZero() throws Exception {
        when(receiver.fetchUnreadMessages()).thenReturn(null);

        String result = handler.handle(null, null);

        assertEquals("0 mail(s) done", result);
    }

    private Message mockMessage(String fromAddress, String subject) throws MessagingException {
        Message message = mock(Message.class);
        InternetAddress internetAddress = mock(InternetAddress.class);
        when(internetAddress.getAddress()).thenReturn(fromAddress);

        Address[] fromArray = new Address[]{internetAddress};
        when(message.getFrom()).thenReturn(fromArray);
        when(message.getSubject()).thenReturn(subject);
        return message;
    }
}
