package com.ayush.chat.blob.local;

import com.ayush.chat.blob.BlobStore;
import com.ayush.chat.core.connector.ConfigField;
import com.ayush.chat.core.connector.ConnectorDescriptor;
import com.ayush.chat.core.connector.ConnectorFactory;
import com.ayush.chat.core.connector.ConnectorKind;
import com.ayush.chat.core.connector.ConnectorSettings;
import com.ayush.chat.core.connector.Maturity;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** GA: covered by {@code LocalBlobStoreTest} against a real filesystem in CI. */
@Component
public class LocalBlobStoreFactory implements ConnectorFactory<BlobStore> {

    private static final ConnectorDescriptor DESCRIPTOR = new ConnectorDescriptor(ConnectorKind.BLOB_STORE,
            "local", "Local filesystem",
            "Files on local disk or a shared volume (NFS/EFS) mounted on every node.",
            "Filesystem", Maturity.GA, List.of("NFS", "Amazon EFS", "Azure Files"),
            List.of(ConfigField.of("directory", "Directory").required().defaultValue("./data/media")
                    .description("Absolute or relative path; must be shared when running several nodes").build()));

    @Override
    public ConnectorDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public BlobStore create(ConnectorSettings settings) throws IOException {
        return new LocalBlobStore(Path.of(settings.required("directory")));
    }
}
