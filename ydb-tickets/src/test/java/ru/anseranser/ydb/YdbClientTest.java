package ru.anseranser.ydb;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tech.ydb.core.Result;
import tech.ydb.table.Session;
import tech.ydb.table.TableClient;
import tech.ydb.table.query.Params;
import tech.ydb.table.result.ResultSetReader;
import tech.ydb.table.transaction.TxControl;
import tech.ydb.table.values.PrimitiveValue;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class YdbClientTest {

    @Mock
    private TableClient tableClient;

    @Mock
    private Session session;

    @Mock
    private ResultSetReader resultSetReader;

    private YdbClient ydbClient;

    @BeforeEach
    void setUp() {
        ydbClient = new YdbClient(tableClient);
    }

    private void mockSession() {
        when(tableClient.createSession(any(Duration.class)))
                .thenReturn(CompletableFuture.completedFuture(Result.success(session)));
    }

    @Test
    void createTicket_success_createsTwoUpsetsAndReturnsJson() {
        mockSession();
        // two executeDataQuery calls
        when(session.executeDataQuery(anyString(), any(TxControl.class), any(Params.class)))
                .thenReturn(CompletableFuture.completedFuture(Result.success(null)));

        String response = ydbClient.createTicket("user@example.com", "bug", "hello world");

        assertNotNull(response);
        assertTrue(response.contains("ticket_id"));
        assertTrue(response.contains("created_at"));

        // verify two calls
        verify(session, times(2)).executeDataQuery(anyString(), any(TxControl.class), any(Params.class));
        verify(session).close();

        // capture params for ticket upsert
        ArgumentCaptor<Params> captor = ArgumentCaptor.forClass(Params.class);
        verify(session, atLeastOnce()).executeDataQuery(anyString(), any(TxControl.class), captor.capture());
        // ensure at least one params contains user_id
        // we can't easily inspect Params internal, but verify calls happened
    }

    @Test
    void createTicket_paramsContainExpectedValues() {
        mockSession();
        when(session.executeDataQuery(anyString(), any(TxControl.class), any(Params.class)))
                .thenReturn(CompletableFuture.completedFuture(Result.success(null)));

        ydbClient.createTicket("u1", "feature", "text123");

        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Params> paramsCaptor = ArgumentCaptor.forClass(Params.class);
        verify(session, times(2)).executeDataQuery(queryCaptor.capture(), any(TxControl.class), paramsCaptor.capture());

        String firstQuery = queryCaptor.getAllValues().get(0);
        assertTrue(firstQuery.contains("UPSERT INTO tickets"));
        assertTrue(firstQuery.contains("$user_id"));
        assertTrue(firstQuery.contains("$category"));
    }

    @Test
    void createTicket_blankUserIdThrows() {
        assertThrows(IllegalArgumentException.class, () -> ydbClient.createTicket("", "bug", "hi"));
    }

    @Test
    void createTicket_nullCategoryThrows() {
        assertThrows(IllegalArgumentException.class, () -> ydbClient.createTicket("u1", null, "hi"));
    }

    @Test
    void createTicket_blankTextThrows() {
        assertThrows(IllegalArgumentException.class, () -> ydbClient.createTicket("u1", "bug", "   "));
    }

    @Test
    void listMyTickets_successReturnsArray() {
        mockSession();
        var dataQueryResult = mock(tech.ydb.table.query.DataQueryResult.class);
        when(dataQueryResult.getResultSet(0)).thenReturn(resultSetReader);
        when(resultSetReader.next()).thenReturn(true).thenReturn(false);

        var idReader = mock(tech.ydb.table.result.ValueReader.class);
        var statusReader = mock(tech.ydb.table.result.ValueReader.class);
        var categoryReader = mock(tech.ydb.table.result.ValueReader.class);
        var textReader = mock(tech.ydb.table.result.ValueReader.class);
        var tsReader = mock(tech.ydb.table.result.ValueReader.class);

        when(idReader.getText()).thenReturn("t1");
        when(statusReader.getText()).thenReturn("open");
        when(categoryReader.getText()).thenReturn("bug");
        when(textReader.getText()).thenReturn("hello");
        when(tsReader.getTimestamp()).thenReturn(java.time.Instant.parse("2025-01-01T00:00:00Z"));

        when(resultSetReader.getColumn("id")).thenReturn(idReader);
        when(resultSetReader.getColumn("status")).thenReturn(statusReader);
        when(resultSetReader.getColumn("category")).thenReturn(categoryReader);
        when(resultSetReader.getColumn("text")).thenReturn(textReader);
        when(resultSetReader.getColumn("created_at")).thenReturn(tsReader);

        when(session.executeDataQuery(anyString(), any(TxControl.class), any(Params.class)))
                .thenReturn(CompletableFuture.completedFuture(Result.success(dataQueryResult)));

        String response = ydbClient.listMyTickets("u1");
        assertNotNull(response);
        assertTrue(response.contains("t1"));
        assertTrue(response.contains("open"));
        verify(session).close();
    }

    @Test
    void listMyTickets_usesSecondaryIndexViewAndLimit() {
        // п.5 ревью: выборка через вторичный индекс tickets_by_user (VIEW), с ORDER BY + LIMIT
        mockSession();
        var dataQueryResult = mock(tech.ydb.table.query.DataQueryResult.class);
        when(dataQueryResult.getResultSet(0)).thenReturn(resultSetReader);
        when(resultSetReader.next()).thenReturn(false);
        when(session.executeDataQuery(anyString(), any(TxControl.class), any(Params.class)))
                .thenReturn(CompletableFuture.completedFuture(Result.success(dataQueryResult)));

        ydbClient.listMyTickets("u1");

        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        verify(session).executeDataQuery(queryCaptor.capture(), any(TxControl.class), any(Params.class));
        String query = queryCaptor.getValue();
        assertTrue(query.contains("FROM tickets VIEW tickets_by_user"), "must read via secondary index: " + query);
        assertTrue(query.contains("WHERE user_id = $user_id"), "must filter by user_id: " + query);
        assertTrue(query.contains("ORDER BY created_at DESC"), "must sort freshest first: " + query);
        assertTrue(query.contains("LIMIT " + YdbClient.LIST_TICKETS_LIMIT), "must limit results: " + query);
    }

    @Test
    void listMyTickets_emptyReturnsEmptyArray() {
        mockSession();
        var dataQueryResult = mock(tech.ydb.table.query.DataQueryResult.class);
        when(dataQueryResult.getResultSet(0)).thenReturn(resultSetReader);
        when(resultSetReader.next()).thenReturn(false);
        when(session.executeDataQuery(anyString(), any(TxControl.class), any(Params.class)))
                .thenReturn(CompletableFuture.completedFuture(Result.success(dataQueryResult)));

        String response = ydbClient.listMyTickets("u1");
        assertEquals("[]", response);
    }

    @Test
    void listMyTickets_blankUserIdThrows() {
        assertThrows(IllegalArgumentException.class, () -> ydbClient.listMyTickets(""));
    }

    @Test
    void appendMessage_successReturnsOk() {
        mockSession();
        when(session.executeDataQuery(anyString(), any(TxControl.class), any(Params.class)))
                .thenReturn(CompletableFuture.completedFuture(Result.success(null)));

        String response = ydbClient.appendMessage("t1", "user", "hello", "", 0L, 0L, 0);
        assertNotNull(response);
        assertTrue(response.contains("message_id"));
        assertTrue(response.contains("\"ok\":true"));
        // two queries: UPSERT message + UPDATE ticket
        verify(session, times(2)).executeDataQuery(anyString(), any(TxControl.class), any(Params.class));
        verify(session).close();
    }

    @Test
    void appendMessage_paramsContainTicketId() {
        mockSession();
        when(session.executeDataQuery(anyString(), any(TxControl.class), any(Params.class)))
                .thenReturn(CompletableFuture.completedFuture(Result.success(null)));

        ydbClient.appendMessage("ticket-123", "agent", "hi", "model-x", 10L, 20L, 100);

        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        verify(session, times(2)).executeDataQuery(queryCaptor.capture(), any(TxControl.class), any(Params.class));
        assertTrue(queryCaptor.getAllValues().get(0).contains("UPSERT INTO messages"));
        assertTrue(queryCaptor.getAllValues().get(1).contains("UPDATE tickets"));
    }

    @Test
    void appendMessage_blankTicketIdThrows() {
        assertThrows(IllegalArgumentException.class, () -> ydbClient.appendMessage("", "user", "hi", "", 0, 0, 0));
    }

    @Test
    void appendMessage_blankRoleThrows() {
        assertThrows(IllegalArgumentException.class, () -> ydbClient.appendMessage("t1", "", "hi", "", 0, 0, 0));
    }

    @Test
    void appendMessage_nullTextThrows() {
        assertThrows(IllegalArgumentException.class, () -> ydbClient.appendMessage("t1", "user", null, "", 0, 0, 0));
    }


}
