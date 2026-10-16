package com.ayush.chat.blob.local;

import com.ayush.chat.blob.BlobStore;
import com.ayush.chat.core.connector.ConfigField;
import com.ayush.chat.core.connector.ConnectorDescriptor;
import com.ayush.chat.core.connector.ConnectorFactory;
import com.ayush.chat.core.connector.ConnectorKind;
import com.ayush.chat.core.connector.ConnectorSettings;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

@Component
public class LocalBlobStoreFactory implements ConnectorFactory<BlobStore> {

    @Override
    public ConnectorDescriptor descriptor() {
        return new ConnectorDescriptor(ConnectorKind.BLOB_STORE, "local", "Local filesystem",
                "Files on local disk or a shared volume (NFS/EFS) mounted on every node.",
                "Filesystem", "GA", List.of("NFS", "Amazon EFS", "Azure Files"),
                List.of(ConfigField.of("directory", "Directory").required().defaultValue("./data/media")
                        .description("Absolute or relative path; must be shared when running several nodes").build()));
    }

    @Override
    public BlobStore create(ConnectorSettings settings) throws Exception {
        return new LocalBlobStore(Path.of(settings.required("directory")));
    }
}
