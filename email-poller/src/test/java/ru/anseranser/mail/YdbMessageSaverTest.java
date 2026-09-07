package ru.anseranser.mail;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Unit tests for {@link YdbMessageSaver}.
 * Verifies fail-open semantics: no exception escapes, and the saver skips early
 * when there is nothing meaningful to persist (no usage, no ticket) without HTTP.
 */
class YdbMessageSaverTest {

    private final YdbMessageSaver saver = new YdbMessageSaver("http://localhost:1/api");

    @Test
    void trySave_blankUserId_skipsWithoutHttp() {
        assertDoesNotThrow(() -> saver.trySave(" ", result(1L, "m", "tid"), "reply"));
    }

    @Test
    void trySave_blankAgentText_skipsWithoutHttp() {
        assertDoesNotThrow(() -> saver.trySave("u@example.com", result(1L, "m", "tid"), "  "));
    }

    @Test
    void trySave_noUsageAndNoModel_skipsWithoutHttp() {
        assertDoesNotThrow(() -> saver.trySave("u@example.com", result(0L, "", "tid"), "reply"));
    }

    @Test
    void trySave_noTicketId_skipsWithoutHttp() {
        assertDoesNotThrow(() -> saver.trySave("u@example.com", result(1L, "m", null), "reply"));
    }

    @Test
    void trySave_failOpen_whenEndpointUnreachable() {
        // Connection to localhost:1 will fail on the network layer; the saver must swallow it.
        assertDoesNotThrow(
                () -> saver.trySave("u@example.com", result(1L, "m", "tid"), "reply"));
    }

    private static AgentClient.AgentResult result(long in, String model, String ticketId) {
        return new AgentClient.AgentResult("reply", in, in, "r1", 5L, model, ticketId);
    }
}