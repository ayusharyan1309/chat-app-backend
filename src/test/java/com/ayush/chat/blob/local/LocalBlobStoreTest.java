package com.ayush.chat.blob.local;

import com.ayush.chat.blob.BlobStore;
import com.ayush.chat.blob.BlobStore.BlobObject;
import com.ayush.chat.core.connector.ConnectorSettings;
import com.ayush.chat.core.connector.Maturity;
import com.ayush.chat.core.exception.InfrastructureUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalBlobStoreTest {

    private final LocalBlobStoreFactory factory = new LocalBlobStoreFactory();

    @TempDir
    Path dir;
    private BlobStore store;

    @BeforeEach
    void create() throws Exception {
        store = factory.create(ConnectorSettings.withDefaults(factory.descriptor(),
                Map.of("directory", dir.resolve("media").toString())));
    }

    private static ByteArrayInputStream bytes(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void descriptorDeclaresLocalStorage() {
        assertThat(factory.descriptor().id()).isEqualTo("local");
        assertThat(factory.descriptor().maturity()).isEqualTo(Maturity.GA);
        assertThat(factory.descriptor().field("directory").required()).isTrue();
        assertThat(factory.descriptor()).isSameAs(factory.descriptor());
    }

    @Test
    void putGetDeleteRoundTrip() throws Exception {
        store.put("tenant-1/a/photo.png", bytes("png-bytes"), 9, "image/png");
        BlobObject blob = store.get("tenant-1/a/photo.png").orElseThrow();
        try (InputStream in = blob.data()) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("png-bytes");
        }
        assertThat(blob.size()).isEqualTo(9);
        assertThat(blob.contentType()).isEqualTo("image/png");

        store.put("tenant-1/a/photo.png", bytes("v2"), 2, null); // overwrite
        BlobObject replaced = store.get("tenant-1/a/photo.png").orElseThrow();
        try (InputStream in = replaced.data()) {
            assertThat(in.readAllBytes()).hasSize(2);
        }
        assertThat(replaced.contentType()).isEqualTo("application/octet-stream");

        store.delete("tenant-1/a/photo.png");
        assertThat(store.get("tenant-1/a/photo.png")).isEmpty();
        store.delete("tenant-1/a/photo.png"); // idempotent
    }

    @Test
    void missingBlobIsEmptyAndNoPresignedUrls() {
        assertThat(store.get("tenant-1/none")).isEmpty();
        assertThat(store.presignedUrl("tenant-1/none", Duration.ofMinutes(1))).isEmpty();
    }

    @Test
    void keysCannotEscapeTheRoot() throws IOException {
        Files.writeString(dir.resolve("outside.txt"), "secret");
        assertThatThrownBy(() -> store.get("../outside.txt")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.put("../../evil", bytes("x"), 1, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.delete("")).isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.readString(dir.resolve("outside.txt"))).isEqualTo("secret");
    }

    @Test
    void filesystemFailuresAreUnavailableAndLeaveNoTemporaryFiles() throws IOException {
        Files.writeString(dir.resolve("media").resolve("tenant-2"), "a file where a directory is needed");
        assertThatThrownBy(() -> store.put("tenant-2/x.png", bytes("x"), 1, "image/png"))
                .isInstanceOf(InfrastructureUnavailableException.class);

        InputStream failing = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("connection reset");
            }
        };
        assertThatThrownBy(() -> store.put("tenant-3/x.png", failing, 10, "image/png"))
                .isInstanceOf(InfrastructureUnavailableException.class);
        try (var leftovers = Files.list(dir.resolve("media").resolve("tenant-3"))) {
            assertThat(leftovers).isEmpty();
        }
    }

    @Test
    void healthReportsWritableDirectory() {
        assertThat(store.health().healthy()).isTrue();
        assertThat(store.health().details()).containsKey("freeBytes");
    }
}
