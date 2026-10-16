package com.ayush.chat.blob;

import com.ayush.chat.core.connector.Connector;

import java.io.InputStream;
import java.time.Duration;
import java.util.Optional;

/**
 * Object storage for media attachments. Keys are tenant-prefixed by the caller.
 */
public interface BlobStore extends Connector {

    void put(String key, InputStream data, long size, String contentType);

    Optional<BlobObject> get(String key);

    void delete(String key);

    /** Direct download URL when the backend supports it (e.g. S3 presigned); empty otherwise. */
    Optional<String> presignedUrl(String key, Duration ttl);

    record BlobObject(InputStream data, long size, String contentType) {
    }
}
