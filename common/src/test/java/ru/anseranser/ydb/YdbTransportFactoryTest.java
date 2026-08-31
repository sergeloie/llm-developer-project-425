package ru.anseranser.ydb;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class YdbTransportFactoryTest {

    @Test
    void create_nullEndpointThrows() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> YdbTransportFactory.create(null, "/ru-central1/xxx"));
        assertTrue(ex.getMessage().contains("YDB_ENDPOINT"));
    }

    @Test
    void create_blankEndpointThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> YdbTransportFactory.create("   ", "/ru-central1/xxx"));
    }

    @Test
    void create_nullDatabaseThrows() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> YdbTransportFactory.create("grpcs://ydb.serverless.yandexcloud.net:2135", null));
        assertTrue(ex.getMessage().contains("YDB_DATABASE"));
    }

    @Test
    void create_blankDatabaseThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> YdbTransportFactory.create("grpcs://ydb.serverless.yandexcloud.net:2135", "   "));
    }

    @Test
    void create_invalidDatabaseWithoutSlashThrows() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> YdbTransportFactory.create("grpcs://ydb.serverless.yandexcloud.net:2135", "ru-central1/xxx"));
        assertTrue(ex.getMessage().contains("YDB_DATABASE"));
    }

    @Test
    void create_withAuthProvider_nullEndpointThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> YdbTransportFactory.create(null, "/ru-central1/xxx", null));
    }

    @Test
    void create_withAuthProvider_invalidDatabaseThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> YdbTransportFactory.create("grpcs://endpoint:2135", "invalid", null));
    }
}
