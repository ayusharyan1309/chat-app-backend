package com.ayush.chat.blob.s3;

import com.ayush.chat.blob.BlobStore;
import com.ayush.chat.blob.BlobStore.BlobObject;
import com.ayush.chat.core.connector.ConnectorSettings;
import com.ayush.chat.core.util.Ids;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs against a real bucket. Configure with environment variables:
 * {@code S3_TEST_BUCKET} (required), {@code S3_TEST_REGION}, {@code S3_TEST_ENDPOINT},
 * {@code S3_TEST_ACCESS_KEY_ID}, {@code S3_TEST_SECRET_ACCESS_KEY}, {@code S3_TEST_PATH_STYLE}.
 * Objects are written under a random prefix and deleted afterwards.
 */
@EnabledIfEnvironmentVariable(named = "S3_TEST_BUCKET", matches = ".+")
class S3BlobStoreIntegrationTest {

    private final S3BlobStoreFactory factory = new S3BlobStoreFactory();
    private final List<String> written = new ArrayList<>();
    private BlobStore store;

    @BeforeEach
    void connect() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("bucket", System.getenv("S3_TEST_BUCKET"));
        putIfSet(raw, "region", "S3_TEST_REGION");
        putIfSet(raw, "endpoint", "S3_TEST_ENDPOINT");
        putIfSet(raw, "accessKeyId", "S3_TEST_ACCESS_KEY_ID");
        putIfSet(raw, "secretAccessKey", "S3_TEST_SECRET_ACCESS_KEY");
        putIfSet(raw, "pathStyle", "S3_TEST_PATH_STYLE");
        raw.put("keyPrefix", "chat-it-" + Ids.randomToken(8).toLowerCase());
        store = factory.create(ConnectorSettings.withDefaults(factory.descriptor(), raw));
    }

    @AfterEach
    void cleanUp() {
        if (store != null) {
            written.forEach(store::delete);
            store.close();
        }
    }

    private static void putIfSet(Map<String, Object> raw, String key, String env) {
        String value = System.getenv(env);
        if (value != null && !value.isBlank()) {
            raw.put(key, value);
        }
    }

    private String key(String name) {
        written.add(name);
        return name;
    }

    @Test
    void isHealthy() {
        assertThat(store.health().healthy()).isTrue();
    }

    @Test
    void putGetDeleteRoundTrip() throws Exception {
        byte[] content = "hello media".getBytes(StandardCharsets.UTF_8);
        String key = key("tenant-1/hello.txt");
        store.put(key, new ByteArrayInputStream(content), content.length, "text/plain");

        BlobObject blob = store.get(key).orElseThrow();
        try (InputStream in = blob.data()) {
            assertThat(in.readAllBytes()).isEqualTo(content);
        }
        assertThat(blob.size()).isEqualTo(content.length);
        assertThat(blob.contentType()).startsWith("text/plain");

        store.delete(key);
        assertThat(store.get(key)).isEmpty();
        store.delete(key); // deleting a missing object is fine
    }

    @Test
    void uploadWithUnknownLengthAndDefaultContentType() throws Exception {
        byte[] content = new byte[70_000];
        new java.util.Random(7).nextBytes(content);
        String key = key("tenant-1/blob.bin");
        store.put(key, new ByteArrayInputStream(content), -1, null);
        BlobObject blob = store.get(key).orElseThrow();
        try (InputStream in = blob.data()) {
            assertThat(in.readAllBytes()).isEqualTo(content);
        }
        assertThat(blob.contentType()).isEqualTo("application/octet-stream");
    }

    @Test
    void missingObjectIsEmpty() {
        assertThat(store.get("does/not/exist")).isEmpty();
    }

    @Test
    void presignedUrlDownloadsTheObject() throws Exception {
        byte[] content = "presigned".getBytes(StandardCharsets.UTF_8);
        String key = key("tenant-1/presigned.txt");
        store.put(key, new ByteArrayInputStream(content), content.length, "text/plain");

        String url = store.presignedUrl(key, Duration.ofMinutes(2)).orElseThrow();
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo(content);
        }
    }
}
