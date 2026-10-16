package com.ayush.chat.blob.s3;

import com.ayush.chat.blob.BlobStore;
import com.ayush.chat.blob.BlobStore.BlobObject;
import com.ayush.chat.core.connector.ConfigField;
import com.ayush.chat.core.connector.ConnectorConfig;
import com.ayush.chat.core.connector.ConnectorDescriptor;
import com.ayush.chat.core.connector.ConnectorKind;
import com.ayush.chat.core.connector.ConnectorRegistry;
import com.ayush.chat.core.connector.ConnectorSettings;
import com.ayush.chat.core.connector.Maturity;
import com.ayush.chat.core.exception.InfrastructureUnavailableException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Descriptor and settings validation, plus the object operations through the real AWS SDK. A tiny
 * local endpoint plays S3 (HEAD bucket, path-style PUT/GET/DELETE object, error responses), so the
 * connect path, uploads, downloads, presigning and error mapping run without real S3.
 */
class S3BlobStoreFactoryTest {

    private record StoredObject(byte[] data, String contentType) {
    }

    private static final String OBJECT_PATH = "/media-bucket/";

    private final S3BlobStoreFactory factory = new S3BlobStoreFactory();
    private final AtomicInteger headRequests = new AtomicInteger();
    private final Map<String, StoredObject> objects = new ConcurrentHashMap<>();
    private HttpServer fakeS3;
    private BlobStore store;

    @BeforeEach
    void startFakeEndpoint() throws IOException {
        fakeS3 = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fakeS3.createContext("/", exchange -> {
            try (exchange) {
                String path = exchange.getRequestURI().getPath();
                if ("HEAD".equals(exchange.getRequestMethod())) {
                    int status = path.startsWith("/media-bucket") ? 200 : path.startsWith("/locked-bucket") ? 403 : 404;
                    headRequests.incrementAndGet();
                    exchange.sendResponseHeaders(status, -1);
                } else if (!path.startsWith(OBJECT_PATH)) {
                    exchange.sendResponseHeaders(405, -1);
                } else {
                    handleObject(exchange, path.substring(OBJECT_PATH.length()));
                }
            }
        });
        fakeS3.start();
    }

    /** Object requests; keys containing "broken" answer 500 like a failing S3 node. */
    private void handleObject(HttpExchange exchange, String key) throws IOException {
        if (key.contains("broken")) {
            byte[] error = "<Error><Code>InternalError</Code><Message>injected</Message></Error>"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/xml");
            exchange.sendResponseHeaders(500, error.length);
            exchange.getResponseBody().write(error);
            return;
        }
        switch (exchange.getRequestMethod()) {
            case "PUT" -> {
                byte[] data = requestBody(exchange);
                objects.put(key, new StoredObject(data, exchange.getRequestHeaders().getFirst("Content-Type")));
                exchange.getResponseHeaders().add("ETag", etag(data));
                exchange.sendResponseHeaders(200, -1);
            }
            case "GET" -> {
                StoredObject object = objects.get(key);
                if (object == null) {
                    byte[] error = "<Error><Code>NoSuchKey</Code><Message>missing</Message></Error>"
                            .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/xml");
                    exchange.sendResponseHeaders(404, error.length);
                    exchange.getResponseBody().write(error);
                    return;
                }
                exchange.getResponseHeaders().add("Content-Type", object.contentType());
                exchange.getResponseHeaders().add("ETag", etag(object.data()));
                exchange.sendResponseHeaders(200, object.data().length);
                exchange.getResponseBody().write(object.data());
            }
            case "DELETE" -> {
                objects.remove(key);
                exchange.sendResponseHeaders(204, -1);
            }
            default -> exchange.sendResponseHeaders(405, -1);
        }
    }

    /** The SDK signs uploads over plain HTTP with aws-chunked encoding: strip the chunk framing. */
    private static byte[] requestBody(HttpExchange exchange) throws IOException {
        byte[] raw = exchange.getRequestBody().readAllBytes();
        String sha = exchange.getRequestHeaders().getFirst("x-amz-content-sha256");
        if (sha == null || !sha.startsWith("STREAMING-")) {
            return raw;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int pos = 0;
        while (pos < raw.length) {
            int lineEnd = pos;
            while (raw[lineEnd] != '\r' || raw[lineEnd + 1] != '\n') {
                lineEnd++;
            }
            String header = new String(raw, pos, lineEnd - pos, StandardCharsets.US_ASCII);
            int size = Integer.parseInt(header.split(";")[0].trim(), 16);
            pos = lineEnd + 2;
            if (size == 0) {
                break;
            }
            out.write(raw, pos, size);
            pos += size + 2;
        }
        return out.toByteArray();
    }

    private static String etag(byte[] data) {
        try {
            return "\"" + HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(data)) + "\"";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterEach
    void stop() {
        if (store != null) {
            store.close();
        }
        fakeS3.stop(0);
    }

    private String endpoint() {
        return "http://127.0.0.1:" + fakeS3.getAddress().getPort();
    }

    private Map<String, Object> fakeSettings(String bucket) {
        Map<String, Object> raw = new HashMap<>();
        raw.put("bucket", bucket);
        raw.put("endpoint", endpoint());
        raw.put("pathStyle", true);
        raw.put("accessKeyId", "test-key");
        raw.put("secretAccessKey", "test-secret");
        return raw;
    }

    private BlobStore create(Map<String, Object> raw) {
        return factory.create(ConnectorSettings.withDefaults(factory.descriptor(), raw));
    }

    @Test
    void descriptorDeclaresS3() {
        ConnectorDescriptor d = factory.descriptor();
        assertThat(d.kind()).isEqualTo(ConnectorKind.BLOB_STORE);
        assertThat(d.id()).isEqualTo("s3");
        assertThat(d.maturity()).isEqualTo(Maturity.GA);
        assertThat(d.compatibleWith()).contains("MinIO", "Cloudflare R2", "DigitalOcean Spaces", "Backblaze B2",
                "Google Cloud Storage (S3 interop)", "Wasabi");
        assertThat(d.field("bucket").required()).isTrue();
        assertThat(d.field("region").defaultValue()).isEqualTo("us-east-1");
        assertThat(d.field("endpoint").required()).isFalse();
        assertThat(d.field("secretAccessKey").type()).isEqualTo(ConfigField.FieldType.SECRET);
        assertThat(d.field("accessKeyId").type()).isNotEqualTo(ConfigField.FieldType.SECRET);
        assertThat(d.field("pathStyle").defaultValue()).isEqualTo(false);
        assertThat(d.field("keyPrefix").defaultValue()).isNull();
        assertThat(d.field("presignTtlSeconds").defaultValue()).isEqualTo(900);
    }

    @Test
    void bucketIsRequired() {
        ConnectorRegistry registry = new ConnectorRegistry(List.of(factory));
        assertThat(registry.validate(new ConnectorConfig(ConnectorKind.BLOB_STORE, "s3", Map.of())))
                .containsExactly("Bucket is required");
        assertThat(registry.validate(new ConnectorConfig(ConnectorKind.BLOB_STORE, "s3", Map.of("bucket", "b")))).isEmpty();
    }

    @Test
    void optionsApplyDefaults() {
        S3BlobStore.Options options = S3BlobStoreFactory.options(
                ConnectorSettings.withDefaults(factory.descriptor(), Map.of("bucket", "b")));
        assertThat(options).isEqualTo(new S3BlobStore.Options("b", "us-east-1", null, null, null, false, "",
                Duration.ofSeconds(900)));
    }

    @Test
    void rejectsInvalidSettingsBeforeConnecting() {
        Map<String, Object> noScheme = fakeSettings("media-bucket");
        noScheme.put("endpoint", "minio.local:9000");
        assertThatThrownBy(() -> create(noScheme)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("http(s)");

        Map<String, Object> halfCredentials = fakeSettings("media-bucket");
        halfCredentials.remove("secretAccessKey");
        assertThatThrownBy(() -> create(halfCredentials)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("together");

        Map<String, Object> badTtl = fakeSettings("media-bucket");
        badTtl.put("presignTtlSeconds", 0);
        assertThatThrownBy(() -> create(badTtl)).isInstanceOf(IllegalArgumentException.class);
        assertThat(headRequests).hasValue(0);
    }

    @Test
    void connectsAndPresignsDownloadUrls() {
        Map<String, Object> raw = fakeSettings("media-bucket");
        raw.put("keyPrefix", "/chat-media/");
        raw.put("presignTtlSeconds", 120);
        store = create(raw);
        assertThat(headRequests).hasValue(1);
        assertThat(store.providerId()).isEqualTo("s3");

        String url = store.presignedUrl("tenant-1/photo 1.png", null).orElseThrow();
        assertThat(url).startsWith(endpoint() + "/media-bucket/chat-media/tenant-1/photo%201.png?")
                .contains("X-Amz-Expires=120").contains("X-Amz-Signature=").contains("X-Amz-Credential=test-key");

        assertThat(store.presignedUrl("k", Duration.ofMinutes(5)).orElseThrow()).contains("X-Amz-Expires=300");
        assertThat(store.presignedUrl("k", Duration.ofDays(30)).orElseThrow()).contains("X-Amz-Expires=604800");
    }

    @Test
    void putGetDeleteRoundTripThroughTheSdk() throws Exception {
        Map<String, Object> raw = fakeSettings("media-bucket");
        raw.put("keyPrefix", "media");
        store = create(raw);

        byte[] content = "hello media".getBytes(StandardCharsets.UTF_8);
        store.put("tenant-1/hello.txt", new ByteArrayInputStream(content), content.length, "text/plain");
        assertThat(objects).containsKey("media/tenant-1/hello.txt");
        BlobObject blob = store.get("tenant-1/hello.txt").orElseThrow();
        try (InputStream in = blob.data()) {
            assertThat(in.readAllBytes()).isEqualTo(content);
        }
        assertThat(blob.size()).isEqualTo(content.length);
        assertThat(blob.contentType()).isEqualTo("text/plain");

        byte[] unknownLength = new byte[70_000];
        store.put("tenant-1/blob.bin", new ByteArrayInputStream(unknownLength), -1, null);
        BlobObject binary = store.get("tenant-1/blob.bin").orElseThrow();
        try (InputStream in = binary.data()) {
            assertThat(in.readAllBytes()).isEqualTo(unknownLength);
        }
        assertThat(binary.contentType()).isEqualTo("application/octet-stream");

        store.delete("tenant-1/hello.txt");
        assertThat(store.get("tenant-1/hello.txt")).isEmpty();
        store.delete("tenant-1/hello.txt"); // deleting a missing object is fine
    }

    @Test
    void backendFailuresAndUseAfterCloseAreUnavailable() {
        store = create(fakeSettings("media-bucket"));
        assertThatThrownBy(() -> store.get("tenant-1/broken.png")).isInstanceOf(InfrastructureUnavailableException.class);
        assertThatThrownBy(() -> store.put("tenant-1/broken.png", new ByteArrayInputStream(new byte[]{1}), 1, "image/png"))
                .isInstanceOf(InfrastructureUnavailableException.class);
        assertThatThrownBy(() -> store.delete("tenant-1/broken.png")).isInstanceOf(InfrastructureUnavailableException.class);

        store.close();
        assertThatThrownBy(() -> store.get("tenant-1/ok.png")).isInstanceOf(InfrastructureUnavailableException.class);
    }

    @Test
    void healthHeadsTheBucket() {
        store = create(fakeSettings("media-bucket"));
        int before = headRequests.get();
        assertThat(store.health().healthy()).isTrue();
        assertThat(headRequests.get()).isEqualTo(before + 1);
        store.close();
        store.close();
        assertThat(store.health().healthy()).isFalse();
    }

    @Test
    void missingBucketFailsFast() {
        assertThatThrownBy(() -> create(fakeSettings("no-such-bucket")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Bucket not found");
    }

    @Test
    void forbiddenBucketFailsFast() {
        assertThatThrownBy(() -> create(fakeSettings("locked-bucket")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Access denied");
    }

    @Test
    void unreachableEndpointFailsFast() {
        Map<String, Object> raw = fakeSettings("media-bucket");
        raw.put("endpoint", "http://127.0.0.1:1");
        assertThatThrownBy(() -> create(raw)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void keyPrefixIsNormalised() {
        assertThat(S3BlobStore.normalizePrefix(null)).isEmpty();
        assertThat(S3BlobStore.normalizePrefix(" ")).isEmpty();
        assertThat(S3BlobStore.normalizePrefix("/")).isEmpty();
        assertThat(S3BlobStore.normalizePrefix("media")).isEqualTo("media/");
        assertThat(S3BlobStore.normalizePrefix("/media/")).isEqualTo("media/");
        assertThat(S3BlobStore.normalizePrefix("a/b//")).isEqualTo("a/b/");
    }
}
