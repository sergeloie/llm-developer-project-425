package ru.anseranser.ydb;

import tech.ydb.auth.AuthProvider;
import tech.ydb.auth.AuthIdentity;
import tech.ydb.common.transaction.TxMode;
import tech.ydb.core.Result;
import tech.ydb.core.grpc.GrpcTransport;
import tech.ydb.table.Session;
import tech.ydb.table.TableClient;
import tech.ydb.table.query.DataQuery;
import tech.ydb.table.query.DataQueryResult;
import tech.ydb.table.query.Params;
import tech.ydb.table.settings.ExecuteDataQuerySettings;
import tech.ydb.table.transaction.TableTransaction;
import tech.ydb.table.transaction.TxControl;
import tech.ydb.table.values.PrimitiveValue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class YdbClient implements AutoCloseable {

    private final GrpcTransport transport;
    private final TableClient tableClient;

    public YdbClient(String endpoint, String database, String token) {
        this.transport = GrpcTransport.forEndpoint(endpoint, database)
                .withAuthProvider(new StaticTokenProvider(token))
                .build();
        this.tableClient = TableClient.newClient(transport).build();
    }

    public YdbClient(GrpcTransport transport, TableClient tableClient) {
        this.transport = transport;
        this.tableClient = tableClient;
    }

    public CompletableFuture<Result<DataQueryResult>> executeQuery(String yql, Map<String, Object> params) {
        Session session = tableClient.createSession(Duration.ofSeconds(10)).join().getValue();
        try {
            Params queryParams = convertParams(params);
            DataQuery prepared = session.prepareDataQuery(yql).join().getValue();
            return prepared.execute(
                    TxControl.serializableRw().setCommitTx(true),
                    queryParams,
                    new ExecuteDataQuerySettings()
            );
        } finally {
            session.close();
        }
    }

    public CompletableFuture<List<Result<DataQueryResult>>> executeInTransaction(List<QueryParams> queries) {
        Session session = tableClient.createSession(Duration.ofSeconds(10)).join().getValue();
        try {
            TableTransaction tx = session.createNewTransaction(TxMode.SERIALIZABLE_RW);
            List<CompletableFuture<Result<DataQueryResult>>> futures = new ArrayList<>();

            for (QueryParams query : queries) {
                Params queryParams = convertParams(query.params());
                futures.add(tx.executeDataQuery(query.yql(), queryParams));
            }

            tx.commit().join();

            List<Result<DataQueryResult>> results = new ArrayList<>();
            for (CompletableFuture<Result<DataQueryResult>> future : futures) {
                results.add(future.join());
            }
            return CompletableFuture.completedFuture(results);
        } finally {
            session.close();
        }
    }

    private Params convertParams(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return Params.empty();
        }
        Params queryParams = Params.create(params.size());
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            String key = entry.getKey().startsWith("$") ? entry.getKey() : "$" + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof String) {
                queryParams.put(key, PrimitiveValue.newText((String) value));
            } else if (value instanceof Long) {
                queryParams.put(key, PrimitiveValue.newUint64((Long) value));
            } else if (value instanceof Integer) {
                queryParams.put(key, PrimitiveValue.newUint64((long) (Integer) value));
            } else if (value instanceof Double) {
                queryParams.put(key, PrimitiveValue.newDouble((Double) value));
            } else if (value instanceof Boolean) {
                queryParams.put(key, PrimitiveValue.newBool((Boolean) value));
            } else {
                queryParams.put(key, PrimitiveValue.newText(value.toString()));
            }
        }
        return queryParams;
    }

    @Override
    public void close() {
        tableClient.close();
        transport.close();
    }

    private static class StaticTokenProvider implements AuthProvider {
        private final String token;

        StaticTokenProvider(String token) {
            this.token = token;
        }

        @Override
        public AuthIdentity createAuthIdentity() {
            return () -> token;
        }
    }
}
