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
import tech.ydb.table.settings.ExecuteDataQuerySettings;
import tech.ydb.table.transaction.TableTransaction;

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
}
