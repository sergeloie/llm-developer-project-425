package ru.anseranser.ydb;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tech.ydb.common.transaction.TxMode;
import tech.ydb.core.Result;
import tech.ydb.core.Status;
import tech.ydb.core.grpc.GrpcTransport;
import tech.ydb.table.Session;
import tech.ydb.table.TableClient;
import tech.ydb.table.query.DataQuery;
import tech.ydb.table.query.DataQueryResult;
import tech.ydb.table.query.Params;
import tech.ydb.table.result.ValueReader;
import tech.ydb.table.result.ResultSetReader;
import tech.ydb.table.settings.ExecuteDataQuerySettings;
import tech.ydb.table.transaction.TableTransaction;
import tech.ydb.table.values.PrimitiveValue;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class YdbClientTest {

    @Mock
    private GrpcTransport transport;

    @Mock
    private TableClient tableClient;

    @Mock
    private Session session;

    @Mock
    private TableTransaction transaction;

    private YdbClient ydbClient;

    @BeforeEach
    void setUp() {
        ydbClient = new YdbClient(transport, tableClient);
    }

    @Test
    void executeQuery_preparesAndExecutesQuery() {
        String yql = "SELECT * FROM tickets WHERE user_id = $user_id";
        Map<String, Object> params = Map.of("user_id", "user@example.com");

        when(tableClient.createSession(any(Duration.class)))
                .thenReturn(CompletableFuture.completedFuture(Result.success(session)));

        DataQuery preparedQuery = mock(DataQuery.class);
        when(session.prepareDataQuery(eq(yql)))
                .thenReturn(CompletableFuture.completedFuture(Result.success(preparedQuery)));

        DataQueryResult queryResult = mock(DataQueryResult.class);
        when(preparedQuery.execute(
                any(),
                any(Params.class),
                any(ExecuteDataQuerySettings.class)
        )).thenReturn(CompletableFuture.completedFuture(Result.success(queryResult)));

        CompletableFuture<Result<DataQueryResult>> future = ydbClient.executeQuery(yql, params);
        Result<DataQueryResult> result = future.join();

        assertNotNull(result);
        assertTrue(result.isSuccess());
        verify(session).prepareDataQuery(eq(yql));
        verify(preparedQuery).execute(
                any(),
                any(Params.class),
                any(ExecuteDataQuerySettings.class)
        );
        verify(session).close();
    }

    @Test
    void executeQuery_withNullParams_executesWithEmptyParams() {
        String yql = "SELECT 1";

        when(tableClient.createSession(any(Duration.class)))
                .thenReturn(CompletableFuture.completedFuture(Result.success(session)));

        DataQuery preparedQuery = mock(DataQuery.class);
        when(session.prepareDataQuery(eq(yql)))
                .thenReturn(CompletableFuture.completedFuture(Result.success(preparedQuery)));

        DataQueryResult queryResult = mock(DataQueryResult.class);
        when(preparedQuery.execute(
                any(),
                any(Params.class),
                any(ExecuteDataQuerySettings.class)
        )).thenReturn(CompletableFuture.completedFuture(Result.success(queryResult)));

        CompletableFuture<Result<DataQueryResult>> future = ydbClient.executeQuery(yql, null);
        Result<DataQueryResult> result = future.join();

        assertNotNull(result);
        assertTrue(result.isSuccess());
    }

    @Test
    void executeInTransaction_executesMultipleQueriesAtomically() {
        QueryParams query1 = new QueryParams(
                "INSERT INTO tickets (id, user_id) VALUES ($id, $user_id)",
                Map.of("id", "ticket-1", "user_id", "user@example.com")
        );
        QueryParams query2 = new QueryParams(
                "INSERT INTO messages (id, ticket_id, text) VALUES ($id, $ticket_id, $text)",
                Map.of("id", "msg-1", "ticket_id", "ticket-1", "text", "Hello")
        );

        when(tableClient.createSession(any(Duration.class)))
                .thenReturn(CompletableFuture.completedFuture(Result.success(session)));

        when(session.createNewTransaction(TxMode.SERIALIZABLE_RW)).thenReturn(transaction);

        DataQueryResult result1 = mock(DataQueryResult.class);
        DataQueryResult result2 = mock(DataQueryResult.class);
        when(transaction.executeDataQuery(eq(query1.yql()), any(Params.class)))
                .thenReturn(CompletableFuture.completedFuture(Result.success(result1)));
        when(transaction.executeDataQuery(eq(query2.yql()), any(Params.class)))
                .thenReturn(CompletableFuture.completedFuture(Result.success(result2)));

        when(transaction.commit()).thenReturn(CompletableFuture.completedFuture(Status.SUCCESS));

        CompletableFuture<List<Result<DataQueryResult>>> future = ydbClient.executeInTransaction(List.of(query1, query2));
        List<Result<DataQueryResult>> results = future.join();

        assertNotNull(results);
        assertEquals(2, results.size());
        verify(transaction).commit();
        verify(session).close();
    }

    @Test
    void close_closesTransportAndClient() {
        ydbClient.close();
        verify(tableClient).close();
        verify(transport).close();
    }

    // --- createTicket ---

    @Test
    void createTicket_success_returnsTicketIdAndCreatedAt() {
        when(tableClient.createSession(any(Duration.class)))
                .thenReturn(CompletableFuture.completedFuture(Result.success(session)));

        when(session.createNewTransaction(TxMode.SERIALIZABLE_RW)).thenReturn(transaction);

        DataQueryResult result1 = mock(DataQueryResult.class);
        DataQueryResult result2 = mock(DataQueryResult.class);
        when(transaction.executeDataQuery(any(String.class), any(Params.class)))
                .thenReturn(CompletableFuture.completedFuture(Result.success(result1)))
                .thenReturn(CompletableFuture.completedFuture(Result.success(result2)));

        when(transaction.commit()).thenReturn(CompletableFuture.completedFuture(Status.SUCCESS));

        String response = ydbClient.createTicket("user@example.com", "bug", "Test ticket");

        assertNotNull(response);
        assertTrue(response.contains("ticket_id"));
        assertTrue(response.contains("created_at"));
        verify(transaction).commit();
        verify(session).close();
    }

    @Test
    void createTicket_ydbCommitFails_throwsRuntimeException() {
        when(tableClient.createSession(any(Duration.class)))
                .thenReturn(CompletableFuture.completedFuture(Result.success(session)));

        when(session.createNewTransaction(TxMode.SERIALIZABLE_RW)).thenReturn(transaction);

        when(transaction.executeDataQuery(any(String.class), any(Params.class)))
                .thenReturn(CompletableFuture.completedFuture(Result.success(mock(DataQueryResult.class))));

        when(transaction.commit())
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("YDB error")));

        assertThrows(RuntimeException.class, () ->
                ydbClient.createTicket("user@example.com", "bug", "Test"));
    }

    @Test
    void createTicket_blankUserId_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () ->
                ydbClient.createTicket("", "bug", "Test"));
    }

    @Test
    void createTicket_nullUserId_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () ->
                ydbClient.createTicket(null, "bug", "Test"));
    }

    @Test
    void createTicket_blankCategory_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () ->
                ydbClient.createTicket("user@example.com", "", "Test"));
    }

    @Test
    void createTicket_nullCategory_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () ->
                ydbClient.createTicket("user@example.com", null, "Test"));
    }

    @Test
    void createTicket_blankText_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () ->
                ydbClient.createTicket("user@example.com", "bug", ""));
    }

    @Test
    void createTicket_nullText_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () ->
                ydbClient.createTicket("user@example.com", "bug", null));
    }

    // --- listMyTickets ---

    @Test
    void listMyTickets_hasTickets_returnsJsonArray() {
        when(tableClient.createSession(any(Duration.class)))
                .thenReturn(CompletableFuture.completedFuture(Result.success(session)));

        DataQuery preparedQuery = mock(DataQuery.class);
        when(session.prepareDataQuery(any(String.class)))
                .thenReturn(CompletableFuture.completedFuture(Result.success(preparedQuery)));

        DataQueryResult queryResult = mock(DataQueryResult.class);
        when(preparedQuery.execute(
                any(),
                any(Params.class),
                any(ExecuteDataQuerySettings.class)
        )).thenReturn(CompletableFuture.completedFuture(Result.success(queryResult)));

        ResultSetReader resultSet = mock(ResultSetReader.class);
        when(queryResult.getResultSet(0)).thenReturn(resultSet);

        ValueReader id1 = mock(ValueReader.class);
        ValueReader status1 = mock(ValueReader.class);
        ValueReader category1 = mock(ValueReader.class);
        ValueReader createdAt1 = mock(ValueReader.class);
        ValueReader text1 = mock(ValueReader.class);

        ValueReader id2 = mock(ValueReader.class);
        ValueReader status2 = mock(ValueReader.class);
        ValueReader category2 = mock(ValueReader.class);
        ValueReader createdAt2 = mock(ValueReader.class);
        ValueReader text2 = mock(ValueReader.class);

        when(resultSet.next()).thenReturn(true).thenReturn(true).thenReturn(false);
        when(resultSet.getColumn("id")).thenReturn(id1).thenReturn(id2);
        when(resultSet.getColumn("status")).thenReturn(status1).thenReturn(status2);
        when(resultSet.getColumn("category")).thenReturn(category1).thenReturn(category2);
        when(resultSet.getColumn("created_at")).thenReturn(createdAt1).thenReturn(createdAt2);
        when(resultSet.getColumn("text")).thenReturn(text1).thenReturn(text2);

        doReturn(PrimitiveValue.newText("ticket-1")).when(id1).getValue();
        doReturn(PrimitiveValue.newText("open")).when(status1).getValue();
        doReturn(PrimitiveValue.newText("bug")).when(category1).getValue();
        doReturn(PrimitiveValue.newText("2025-01-01T00:00:00Z")).when(createdAt1).getValue();
        doReturn(PrimitiveValue.newText("First message")).when(text1).getValue();

        doReturn(PrimitiveValue.newText("ticket-2")).when(id2).getValue();
        doReturn(PrimitiveValue.newText("closed")).when(status2).getValue();
        doReturn(PrimitiveValue.newText("feature")).when(category2).getValue();
        doReturn(PrimitiveValue.newText("2025-01-02T00:00:00Z")).when(createdAt2).getValue();
        doReturn(null).when(text2).getValue();

        String response = ydbClient.listMyTickets("user@example.com");

        assertNotNull(response);
        assertTrue(response.contains("ticket-1"));
        assertTrue(response.contains("ticket-2"));
        assertTrue(response.contains("open"));
        assertTrue(response.contains("closed"));
        assertTrue(response.contains("First message"));
        verify(session).close();
    }

    @Test
    void listMyTickets_noTickets_returnsEmptyJsonArray() {
        when(tableClient.createSession(any(Duration.class)))
                .thenReturn(CompletableFuture.completedFuture(Result.success(session)));

        DataQuery preparedQuery = mock(DataQuery.class);
        when(session.prepareDataQuery(any(String.class)))
                .thenReturn(CompletableFuture.completedFuture(Result.success(preparedQuery)));

        DataQueryResult queryResult = mock(DataQueryResult.class);
        when(preparedQuery.execute(
                any(),
                any(Params.class),
                any(ExecuteDataQuerySettings.class)
        )).thenReturn(CompletableFuture.completedFuture(Result.success(queryResult)));

        ResultSetReader resultSet = mock(ResultSetReader.class);
        when(queryResult.getResultSet(0)).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);

        String response = ydbClient.listMyTickets("user@example.com");

        assertNotNull(response);
        assertEquals("[]", response);
        verify(session).close();
    }

    @Test
    void listMyTickets_ydbQueryFails_throwsRuntimeException() {
        when(tableClient.createSession(any(Duration.class)))
                .thenReturn(CompletableFuture.completedFuture(Result.success(session)));

        DataQuery preparedQuery = mock(DataQuery.class);
        when(session.prepareDataQuery(any(String.class)))
                .thenReturn(CompletableFuture.completedFuture(Result.success(preparedQuery)));

        when(preparedQuery.execute(
                any(),
                any(Params.class),
                any(ExecuteDataQuerySettings.class)
        )).thenReturn(CompletableFuture.failedFuture(new RuntimeException("YDB query error")));

        assertThrows(RuntimeException.class, () ->
                ydbClient.listMyTickets("user@example.com"));
        verify(session).close();
    }

    @Test
    void listMyTickets_blankUserId_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () ->
                ydbClient.listMyTickets(""));
    }

    @Test
    void listMyTickets_nullUserId_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () ->
                ydbClient.listMyTickets(null));
    }
}
