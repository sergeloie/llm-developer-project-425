package ru.anseranser.ydb;

import tech.ydb.auth.AuthProvider;
import tech.ydb.auth.AuthIdentity;

class StaticTokenProvider implements AuthProvider {
    private final String token;

    StaticTokenProvider(String token) {
        this.token = token;
    }

    @Override
    public AuthIdentity createAuthIdentity() {
        return () -> token;
    }
}
