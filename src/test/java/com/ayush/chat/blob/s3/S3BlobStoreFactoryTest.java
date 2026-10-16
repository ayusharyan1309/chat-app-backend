package com.ayush.chat.blob.s3;

import com.ayush.chat.blob.BlobStore;
import com.ayush.chat.core.connector.ConfigField;
import com.ayush.chat.core.connector.ConnectorConfig;
import com.ayush.chat.core.connector.ConnectorDescriptor;
import com.ayush.chat.core.connector.ConnectorKind;
import com.ayush.chat.core.connector.ConnectorRegistry;
import com.ayush.chat.core.connector.ConnectorSettings;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Descriptor and settings validation. A tiny local endpoint answers HEAD bucket requests so the
 * fail-fast connect path and (offline) URL presigning can be exercised without real S3.
 */
class S3BlobStoreFactoryTest {

    private final S3BlobStoreFactory factory = new S3BlobStoreFactory();
    private final AtomicInteger headRequests = new AtomicInteger();
    private HttpServer fakeS3;
    private BlobStore store;

    @BeforeEach
    void startFakeEndpoint() throws IOException {
        fakeS3 = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fakeS3.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            int status = !"HEAD".equals(exchange.getRequestMethod()) ? 405
                    : path.startsWith("/media-bucket") ? 200
                    : path.startsWith("/locked-bucket") ? 403
                    : 404;
            headRequests.incrementAndGet();
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        fakeS3.start();
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
        assertThat(d.maturity()).isEqualTo("GA");
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
