package com.ayush.chat.blob.s3;

import com.ayush.chat.blob.BlobStore;
import com.ayush.chat.core.connector.ConfigField;
import com.ayush.chat.core.connector.ConnectorDescriptor;
import com.ayush.chat.core.connector.ConnectorFactory;
import com.ayush.chat.core.connector.ConnectorKind;
import com.ayush.chat.core.connector.ConnectorSettings;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

@Component
public class S3BlobStoreFactory implements ConnectorFactory<BlobStore> {

    @Override
    public ConnectorDescriptor descriptor() {
        return new ConnectorDescriptor(ConnectorKind.BLOB_STORE, "s3", "Amazon S3",
                "S3 or any S3-compatible object storage. Clients download media directly via presigned URLs.",
                "Object storage", "GA",
                List.of("MinIO", "Cloudflare R2", "DigitalOcean Spaces", "Backblaze B2",
                        "Google Cloud Storage (S3 interop)", "Wasabi"),
                List.of(ConfigField.of("bucket", "Bucket").required().placeholder("chat-media").build(),
                        ConfigField.of("region", "Region").defaultValue("us-east-1")
                                .description("AWS region; 'auto' for Cloudflare R2").build(),
                        ConfigField.of("endpoint", "Endpoint URL").url()
                                .placeholder("https://<account>.r2.cloudflarestorage.com")
                                .description("Only for S3-compatible services; leave empty for AWS").build(),
                        ConfigField.of("accessKeyId", "Access key id")
                                .description("Leave both keys empty to use the default AWS credentials chain "
                                        + "(env, profile, IAM role)").build(),
                        ConfigField.of("secretAccessKey", "Secret access key").secret().build(),
                        ConfigField.of("pathStyle", "Path-style access").bool(false)
                                .description("Required by MinIO and most self-hosted S3 servers").build(),
                        ConfigField.of("keyPrefix", "Key prefix").placeholder("chat-media/")
                                .description("Folder prepended to every object key").build(),
                        ConfigField.of("presignTtlSeconds", "Download link TTL (s)").integer(900)
                                .description("Default lifetime of presigned download URLs (max 7 days)").build()));
    }

    @Override
    public BlobStore create(ConnectorSettings settings) {
        long ttlSeconds = settings.longValue("presignTtlSeconds", 900);
        if (ttlSeconds <= 0) {
            throw new IllegalArgumentException("Download link TTL must be positive");
        }
        return new S3BlobStore(options(settings));
    }

    static S3BlobStore.Options options(ConnectorSettings settings) {
        return new S3BlobStore.Options(
                settings.required("bucket"),
                settings.string("region", "us-east-1"),
                settings.string("endpoint", null),
                settings.string("accessKeyId", null),
                settings.string("secretAccessKey", null),
                settings.bool("pathStyle", false),
                settings.string("keyPrefix", ""),
                Duration.ofSeconds(settings.longValue("presignTtlSeconds", 900)));
    }
}
