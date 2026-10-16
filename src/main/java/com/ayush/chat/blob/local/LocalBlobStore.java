package com.ayush.chat.blob.local;

import com.ayush.chat.blob.BlobStore;
import com.ayush.chat.core.connector.ConnectorKind;
import com.ayush.chat.core.connector.HealthStatus;
import com.ayush.chat.core.error.InfrastructureUnavailableException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Stores blobs on the local filesystem (or a shared volume such as NFS/EFS for clusters).
 * Content type is kept in a sidecar file. Filesystem failures surface as
 * {@link InfrastructureUnavailableException} (503).
 */
public class LocalBlobStore implements BlobStore {

    private static final String COMPONENT = ConnectorKind.BLOB_STORE.getDisplayName();
    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    private final Path root;

    public LocalBlobStore(Path root) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        Files.createDirectories(this.root);
    }

    @Override
    public String providerId() {
        return "local";
    }

    @Override
    public HealthStatus health() {
        try {
            long usable = Files.getFileStore(root).getUsableSpace();
            boolean writable = Files.isWritable(root);
            return writable
                    ? HealthStatus.up("Writable at " + root, Map.of("freeBytes", usable))
                    : HealthStatus.down("Directory not writable: " + root);
        } catch (IOException e) {
            return HealthStatus.down(e.getMessage());
        }
    }

    /** Written to a temporary file first and moved into place, so readers never see a partial blob. */
    @Override
    public void put(String key, InputStream data, long size, String contentType) {
        Path target = resolve(key);
        Path tmp = null;
        try {
            Files.createDirectories(target.getParent());
            tmp = Files.createTempFile(target.getParent(), ".upload", ".tmp");
            Files.copy(data, tmp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            tmp = null;
            Files.writeString(sidecar(target), contentType == null ? DEFAULT_CONTENT_TYPE : contentType);
        } catch (IOException e) {
            throw new InfrastructureUnavailableException(COMPONENT, e);
        } finally {
            if (tmp != null) {
                deleteQuietly(tmp);
            }
        }
    }

    @Override
    public Optional<BlobObject> get(String key) {
        Path target = resolve(key);
        if (!Files.isRegularFile(target)) {
            return Optional.empty();
        }
        try {
            Path meta = sidecar(target);
            String contentType = Files.exists(meta) ? Files.readString(meta) : DEFAULT_CONTENT_TYPE;
            return Optional.of(new BlobObject(Files.newInputStream(target), Files.size(target), contentType));
        } catch (IOException e) {
            throw new InfrastructureUnavailableException(COMPONENT, e);
        }
    }

    @Override
    public void delete(String key) {
        Path target = resolve(key);
        try {
            Files.deleteIfExists(target);
            Files.deleteIfExists(sidecar(target));
        } catch (IOException e) {
            throw new InfrastructureUnavailableException(COMPONENT, e);
        }
    }

    @Override
    public Optional<String> presignedUrl(String key, Duration ttl) {
        return Optional.empty();
    }

    @Override
    public void close() {
        // nothing to release
    }

    private Path resolve(String key) {
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root) || target.equals(root)) {
            throw new IllegalArgumentException("Invalid blob key");
        }
        return target;
    }

    private static Path sidecar(Path target) {
        return target.resolveSibling(target.getFileName() + ".meta");
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best effort: a leftover temporary file is harmless
        }
    }
}
