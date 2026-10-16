package com.ayush.chat.blob.s3;

import com.ayush.chat.blob.BlobStore;
import com.ayush.chat.core.connector.ConnectorKind;
import com.ayush.chat.core.connector.HealthStatus;
import com.ayush.chat.core.exception.InfrastructureUnavailableException;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Amazon S3 and S3-compatible object storage (MinIO, R2, Spaces, B2, GCS interop, Wasabi).
 * Downloads can bypass the chat nodes through presigned GET URLs. S3 failures at runtime (and use
 * after {@link #close()}) surface as {@link InfrastructureUnavailableException} (503).
 */
public class S3BlobStore implements BlobStore {

    private static final String COMPONENT = ConnectorKind.BLOB_STORE.getDisplayName();

    /** SigV4 presigned URLs are valid for at most 7 days. */
    static final Duration MAX_PRESIGN_TTL = Duration.ofDays(7);
    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    /**
     * @param endpoint        null for AWS; base URL of an S3-compatible service otherwise
     * @param accessKeyId     null (with secretAccessKey null) to use the default AWS credentials chain
     * @param presignTtl      used when {@link #presignedUrl} is called without a TTL
     */
    public record Options(String bucket, String region, String endpoint, String accessKeyId, String secretAccessKey,
                          boolean pathStyle, String keyPrefix, Duration presignTtl) {
    }

    private final S3Client s3;
    private final S3Presigner presigner;
    private final AwsCredentialsProvider credentials;
    private final String bucket;
    private final String keyPrefix;
    private final Duration presignTtl;
    private final String location;
    private final AtomicBoolean closed = new AtomicBoolean();

    public S3BlobStore(Options options) {
        this.bucket = requireText(options.bucket(), "Bucket");
        this.keyPrefix = normalizePrefix(options.keyPrefix());
        this.presignTtl = options.presignTtl() == null || options.presignTtl().isNegative() || options.presignTtl().isZero()
                ? Duration.ofMinutes(15) : options.presignTtl();
        URI endpoint = parseEndpoint(options.endpoint());
        Region region = Region.of(requireText(options.region(), "Region"));
        this.location = (endpoint == null ? "s3://" : endpoint + "/") + bucket;
        this.credentials = credentials(options.accessKeyId(), options.secretAccessKey());

        S3Configuration serviceConfig = S3Configuration.builder().pathStyleAccessEnabled(options.pathStyle()).build();
        S3ClientBuilder clientBuilder = S3Client.builder().region(region).credentialsProvider(credentials)
                .serviceConfiguration(serviceConfig);
        S3Presigner.Builder presignerBuilder = S3Presigner.builder().region(region).credentialsProvider(credentials)
                .serviceConfiguration(serviceConfig);
        if (endpoint != null) {
            clientBuilder.endpointOverride(endpoint);
            presignerBuilder.endpointOverride(endpoint);
        }
        this.s3 = clientBuilder.build();
        this.presigner = presignerBuilder.build();
        try {
            s3.headBucket(b -> b.bucket(bucket));
        } catch (SdkException e) {
            close();
            throw new IllegalStateException(describe(e), e);
        }
    }

    @Override
    public String providerId() {
        return "s3";
    }

    @Override
    public HealthStatus health() {
        if (closed.get()) {
            return HealthStatus.down("closed");
        }
        try {
            s3.headBucket(b -> b.bucket(bucket));
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("bucket", bucket);
            details.put("keyPrefix", keyPrefix);
            return HealthStatus.up("Bucket reachable: " + location, details);
        } catch (SdkException e) {
            return HealthStatus.down(describe(e));
        }
    }

    @Override
    public void put(String key, InputStream data, long size, String contentType) {
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey(key))
                .contentType(contentType == null || contentType.isBlank() ? DEFAULT_CONTENT_TYPE : contentType)
                .build();
        RequestBody body;
        if (size >= 0) {
            body = RequestBody.fromInputStream(data, size);
        } else {
            try {
                body = RequestBody.fromBytes(data.readAllBytes()); // unknown length: S3 needs Content-Length
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to read upload for " + key, e);
            }
        }
        call(() -> s3.putObject(request, body));
    }

    @Override
    public Optional<BlobObject> get(String key) {
        String objectKey = objectKey(key);
        return call(() -> {
            try {
                ResponseInputStream<GetObjectResponse> in = s3.getObject(b -> b.bucket(bucket).key(objectKey));
                GetObjectResponse response = in.response();
                long size = response.contentLength() == null ? -1 : response.contentLength();
                String contentType = response.contentType() == null ? DEFAULT_CONTENT_TYPE : response.contentType();
                return Optional.of(new BlobObject(in, size, contentType));
            } catch (NoSuchKeyException e) {
                return Optional.empty();
            } catch (S3Exception e) {
                if (e.statusCode() == 404) { // some S3-compatibles omit the NoSuchKey error code
                    return Optional.empty();
                }
                throw e;
            }
        });
    }

    @Override
    public void delete(String key) {
        String objectKey = objectKey(key);
        call(() -> s3.deleteObject(b -> b.bucket(bucket).key(objectKey)));
    }

    /** Signed locally (no request to S3); fails only when credentials cannot be resolved. */
    @Override
    public Optional<String> presignedUrl(String key, Duration ttl) {
        Duration duration = ttl == null || ttl.isZero() || ttl.isNegative() ? presignTtl : ttl;
        if (duration.compareTo(MAX_PRESIGN_TTL) > 0) {
            duration = MAX_PRESIGN_TTL;
        }
        Duration signatureDuration = duration;
        String objectKey = objectKey(key);
        return Optional.of(call(() -> presigner.presignGetObject(r -> r.signatureDuration(signatureDuration)
                .getObjectRequest(g -> g.bucket(bucket).key(objectKey))).url().toString()));
    }

    /** Runs one S3 operation: SDK failures (and use after close) become a retryable 503. */
    private <T> T call(Supplier<T> operation) {
        if (closed.get()) {
            throw new InfrastructureUnavailableException(COMPONENT, "closed");
        }
        try {
            return operation.get();
        } catch (SdkException e) {
            throw new InfrastructureUnavailableException(COMPONENT, e);
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            closeQuietly(s3);
            closeQuietly(presigner);
            if (credentials instanceof AutoCloseable closeable) {
                closeQuietly(closeable);
            }
        }
    }

    String objectKey(String key) {
        String k = requireText(key, "Key");
        while (k.startsWith("/")) {
            k = k.substring(1);
        }
        return keyPrefix + k;
    }

    /** Blank stays blank; otherwise no leading slash and exactly one trailing slash. */
    static String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return "";
        }
        String p = prefix.trim();
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p.isEmpty() ? "" : p + "/";
    }

    static URI parseEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return null;
        }
        URI uri;
        try {
            uri = URI.create(endpoint.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid endpoint URL: " + endpoint, e);
        }
        if (uri.getScheme() == null || !(uri.getScheme().equals("http") || uri.getScheme().equals("https"))
                || uri.getHost() == null) {
            throw new IllegalArgumentException("Endpoint must be an http(s) URL, e.g. https://minio.example.com:9000");
        }
        return uri;
    }

    private static AwsCredentialsProvider credentials(String accessKeyId, String secretAccessKey) {
        boolean hasId = accessKeyId != null && !accessKeyId.isBlank();
        boolean hasSecret = secretAccessKey != null && !secretAccessKey.isBlank();
        if (hasId != hasSecret) {
            throw new IllegalArgumentException("Access key id and secret access key must be set together");
        }
        return hasId
                ? StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId.trim(), secretAccessKey.trim()))
                : DefaultCredentialsProvider.builder().build();
    }

    private String describe(SdkException e) {
        if (e instanceof S3Exception s3e) {
            return switch (s3e.statusCode()) {
                case 404 -> "Bucket not found: " + location;
                case 403 -> "Access denied to " + location + " (check credentials and bucket policy)";
                case 301 -> "Bucket " + bucket + " is in a different region than configured";
                default -> "S3 error " + s3e.statusCode() + " on " + location + ": "
                        + (s3e.awsErrorDetails() != null && s3e.awsErrorDetails().errorMessage() != null
                        ? s3e.awsErrorDetails().errorMessage() : s3e.getMessage());
            };
        }
        return "S3 request to " + location + " failed: " + e.getMessage();
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
            // shutting down anyway
        }
    }
}
