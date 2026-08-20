package ru.anseranser.ydb;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import tech.ydb.auth.AuthProvider;
import tech.ydb.auth.AuthIdentity;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Auth provider that fetches IAM tokens from the Yandex Cloud metadata service.
 *
 * This is the Java equivalent of Python's {@code ydb.iam.MetadataUrlCredentials()}.
 * It queries the metadata endpoint at 169.254.169.254 to obtain a short-lived IAM token,
 * then caches it and refreshes before expiry.
 *
 * This approach is required for Yandex Cloud Functions because:
 * - IAM tokens expire after ~12 hours when created manually
 * - The metadata service automatically provides fresh tokens
 * - No static YDB_TOKEN environment variable is needed
 */
class MetadataTokenProvider implements AuthProvider {

    private static final String METADATA_URL =
            "http://169.254.169.254/computeMetadata/v1/instance/service-accounts/default/token";

    /** Refresh token 60 seconds before it actually expires. */
    private static final long REFRESH_BUFFER_SECONDS = 60;

    private final HttpClient httpClient;

    private volatile String cachedToken;
    private volatile long expiresAtEpochSecond; // System.currentTimeMillis() / 1000

    MetadataTokenProvider() {
        this.httpClient = HttpClient.newHttpClient();
    }

    /** Package-private constructor for tests — inject a mock HttpClient. */
    MetadataTokenProvider(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public AuthIdentity createAuthIdentity() {
        return this::getToken;
    }

    private String getToken() {
        if (cachedToken == null || System.currentTimeMillis() / 1000 >= expiresAtEpochSecond) {
            synchronized (this) {
                if (cachedToken == null || System.currentTimeMillis() / 1000 >= expiresAtEpochSecond) {
                    refreshToken();
                }
            }
        }
        return cachedToken;
    }

    private void refreshToken() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(METADATA_URL))
                    .header("Metadata-Flavor", "Google")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException(
                        "Metadata service returned HTTP " + response.statusCode() + ": " + response.body());
            }

            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            String accessToken = json.get("access_token").getAsString();
            int expiresIn = json.get("expires_in").getAsInt(); // seconds

            this.cachedToken = accessToken;
            this.expiresAtEpochSecond = System.currentTimeMillis() / 1000 + expiresIn - REFRESH_BUFFER_SECONDS;

            System.out.println("[MetadataTokenProvider] refreshToken: OK, expiresIn=" + expiresIn + "s");
        } catch (IOException | InterruptedException e) {
            System.out.println("[MetadataTokenProvider] refreshToken: ERROR " + e.getMessage());
            throw new RuntimeException("Failed to fetch IAM token from metadata service", e);
        }
    }
}
