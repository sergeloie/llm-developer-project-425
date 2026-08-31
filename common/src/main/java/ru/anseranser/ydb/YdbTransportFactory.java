package ru.anseranser.ydb;

import tech.ydb.auth.AuthProvider;
import tech.ydb.auth.iam.CloudAuthHelper;
import tech.ydb.core.grpc.GrpcTransport;
import tech.ydb.table.TableClient;

/**
 * Factory for creating YDB {@link TableClient} instances.
 * Wraps {@link GrpcTransport} creation with IAM auth.
 */
public final class YdbTransportFactory {

    private YdbTransportFactory() {
    }

    /**
     * Creates a {@link TableClient} for the given endpoint and database.
     *
     * @param endpoint YDB endpoint (e.g. grpcs://ydb.serverless.yandexcloud.net:2135)
     * @param database YDB database path (e.g. /ru-central1/xxx/yyy)
     * @return configured TableClient
     * @throws IllegalArgumentException if endpoint or database is blank/invalid
     * @throws IllegalStateException if transport initialization fails
     */
    public static TableClient create(String endpoint, String database) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("YDB_ENDPOINT is not set");
        }
        if (database == null || database.isBlank() || !database.startsWith("/")) {
            throw new IllegalArgumentException("YDB_DATABASE is not set or invalid");
        }
        try {
            AuthProvider authProvider = CloudAuthHelper.getAuthProviderFromEnviron();
            GrpcTransport transport = GrpcTransport.forEndpoint(endpoint, database)
                    .withAuthProvider(authProvider)
                    .build();
            return TableClient.newClient(transport).build();
        } catch (Exception e) {
            throw new IllegalStateException("YDB Client initialization failed: " + e.getMessage(), e);
        }
    }

    /**
     * Creates a {@link TableClient} with explicit {@link AuthProvider} (useful for tests).
     */
    public static TableClient create(String endpoint, String database, AuthProvider authProvider) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("YDB_ENDPOINT is not set");
        }
        if (database == null || database.isBlank() || !database.startsWith("/")) {
            throw new IllegalArgumentException("YDB_DATABASE is not set or invalid");
        }
        try {
            GrpcTransport transport = GrpcTransport.forEndpoint(endpoint, database)
                    .withAuthProvider(authProvider)
                    .build();
            return TableClient.newClient(transport).build();
        } catch (Exception e) {
            throw new IllegalStateException("YDB Client initialization failed: " + e.getMessage(), e);
        }
    }
}
