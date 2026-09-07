package ru.anseranser.mail;

import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentMatcher;

import java.io.IOException;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link EmailHandler} error handling paths.
 * Verifies that each failure scenario (IMAP connect, fetch, send, agent, parse)
 * produces the correct log message and does not abort processing of remaining messages.
 * Uses {@link SmtpEmailSender} from common instead of local EmailSender.
 */
@ExtendWith(MockitoExtension.class)
class EmailHandlerTest {

    @Mock
    private EmailReceiver receiver;
    @Mock
    private SmtpEmailSender sender;
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
        when(agent.getResponseWithUsage(argThat(jsonContains("user@example.com", "Hello body"))))
                .thenReturn(new AgentClient.AgentResult("Agent reply", 10L, 20L, "resp_1", 123L, "yandexgpt", null));

        String result = handler.handle(null, null);

        assertEquals("1 mail(s) done", result);
        verify(sender).sendWithThreading("user@example.com", "Re: Hello", "Agent reply", null, "Hello body");
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
        when(agent.getResponseWithUsage(argThat(jsonContains("user1@example.com", "Body 1"))))
                .thenThrow(new RuntimeException("Agent unavailable"));
        when(agent.getResponseWithUsage(argThat(jsonContains("user2@example.com", "Body 2"))))
                .thenReturn(new AgentClient.AgentResult("Reply 2", 5L, 5L, "r2", 10L, "yandexgpt", null));

        String result = handler.handle(null, null);

        assertEquals("1 mail(s) done", result);
        verify(sender).sendWithThreading("user2@example.com", "Re: Second", "Reply 2", null, "Body 2");
        verify(receiver).markAsSeen(msg2);
    }

    @Test
    void handle_sendMessagingException_logsSendErrorAndContinues() throws Exception {
        Message msg1 = mockMessage("user1@example.com", "First");
        Message msg2 = mockMessage("user2@example.com", "Second");

        when(receiver.fetchUnreadMessages()).thenReturn(new Message[]{msg1, msg2});
        when(extractor.extractPlainText(msg1)).thenReturn("Body 1");
        when(extractor.extractPlainText(msg2)).thenReturn("Body 2");
        when(agent.getResponseWithUsage(argThat(jsonContains("user1@example.com", "Body 1"))))
                .thenReturn(new AgentClient.AgentResult("Reply 1", 10L, 10L, "r1", 5L, "yandexgpt", null));
        doThrow(new MessagingException("SMTP send failed"))
                .when(sender).sendWithThreading("user1@example.com", "Re: First", "Reply 1", null, "Body 1");
        when(agent.getResponseWithUsage(argThat(jsonContains("user2@example.com", "Body 2"))))
                .thenReturn(new AgentClient.AgentResult("Reply 2", 10L, 10L, "r2", 5L, "yandexgpt", null));

        String result = handler.handle(null, null);

        assertEquals("1 mail(s) done", result);
        verify(receiver).markAsSeen(msg2);
        // M1 fix: even failed message is marked Seen to avoid poller loop
        verify(receiver).markAsSeen(msg1);
    }

    @Test
    void handle_parseFails_logsParseErrorAndContinues() throws Exception {
        Message msg1 = mockMessage("user1@example.com", "First");
        Message msg2 = mockMessage("user2@example.com", "Second");

        when(receiver.fetchUnreadMessages()).thenReturn(new Message[]{msg1, msg2});
        when(extractor.extractPlainText(msg1)).thenThrow(new IOException("Parse error"));
        when(extractor.extractPlainText(msg2)).thenReturn("Body 2");
        when(agent.getResponseWithUsage(argThat(jsonContains("user2@example.com", "Body 2"))))
                .thenReturn(new AgentClient.AgentResult("Reply 2", 5L, 5L, "r2", 10L, "yandexgpt", null));

        String result = handler.handle(null, null);

        assertEquals("1 mail(s) done", result);
        verify(sender).sendWithThreading("user2@example.com", "Re: Second", "Reply 2", null, "Body 2");
        verify(receiver).markAsSeen(msg2);
        verify(receiver).markAsSeen(msg1);
    }

    @Test
    void handle_agentThrowsUnchecked_logsErrorAndContinues() throws Exception {
        Message msg1 = mockMessage("user1@example.com", "First");
        Message msg2 = mockMessage("user2@example.com", "Second");

        when(receiver.fetchUnreadMessages()).thenReturn(new Message[]{msg1, msg2});
        when(extractor.extractPlainText(msg1)).thenReturn("Body 1");
        when(extractor.extractPlainText(msg2)).thenReturn("Body 2");
        when(agent.getResponseWithUsage(argThat(jsonContains("user1@example.com", "Body 1"))))
                .thenThrow(new RuntimeException("Agent unavailable"));
        when(agent.getResponseWithUsage(argThat(jsonContains("user2@example.com", "Body 2"))))
                .thenReturn(new AgentClient.AgentResult("Reply 2", 5L, 5L, "r2", 10L, "yandexgpt", null));

        String result = handler.handle(null, null);

        assertEquals("1 mail(s) done", result);
        verify(sender).sendWithThreading("user2@example.com", "Re: Second", "Reply 2", null, "Body 2");
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
        // M1 fix: empty body also marked Seen
        verify(receiver).markAsSeen(msg1);
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

    private ArgumentMatcher<String> jsonContains(String expectedUserId, String expectedText) {
        return json -> json != null
                && json.contains("\"user_id\":\"" + expectedUserId + "\"")
                && json.contains("\"text\":\"" + expectedText + "\"");
    }

    @Test
    void extractDeepestQuoted_threeLevelThread_returnsOriginal() {
        String body = "да, создай тикет\n\n> У меня нет информации по этому вопросу.\n> Хотите, чтобы я создал тикет?\n>\n> > Я вчера платил с карты 1465-6518-6548-5318 по 500 рублей, пришло только одно";
        String deepest = EmailHandler.extractDeepestQuoted(body);
        // should return innermost original (depth 2)
        org.junit.jupiter.api.Assertions.assertNotNull(deepest);
        org.junit.jupiter.api.Assertions.assertTrue(deepest.contains("1465-6518"));
    }

    @Test
    void extractDeepestQuoted_noQuoted_returnsNull() {
        String body = "Просто вопрос без цитат";
        org.junit.jupiter.api.Assertions.assertNull(EmailHandler.extractDeepestQuoted(body));
    }

    @Test
    void extractDeepestQuoted_shortConfirmation_returnsNull() {
        String body = "Да\n\n> ok";
        org.junit.jupiter.api.Assertions.assertNull(EmailHandler.extractDeepestQuoted(body));
    }

    @Test
    void handle_withThreadedBody_sendsJsonWithOriginalText() throws Exception {
        Message message = mockMessage("user@example.com", "Hello");
        String threadedBody = "да, создай тикет\n\n> У меня нет\n> > Я вчера платил с карты 1465-6518-6548-5318";
        when(receiver.fetchUnreadMessages()).thenReturn(new Message[]{message});
        when(extractor.extractPlainText(message)).thenReturn(threadedBody);
        when(agent.getResponseWithUsage(argThat(json -> json != null && json.contains("original_text") && json.contains("1465-6518"))))
                .thenReturn(new AgentClient.AgentResult("ok", 10L, 10L, "r1", 5L, "yandexgpt", "tid123"));

        handler.handle(null, null);

        verify(agent).getResponseWithUsage(argThat(json -> json.contains("original_text")));
    }
}
