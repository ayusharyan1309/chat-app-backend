package com.ayush.chat.store.firestore;

import com.ayush.chat.core.connector.ConfigField;
import com.ayush.chat.core.connector.ConnectorDescriptor;
import com.ayush.chat.core.connector.ConnectorFactory;
import com.ayush.chat.core.connector.ConnectorKind;
import com.ayush.chat.core.connector.ConnectorSettings;
import com.ayush.chat.store.MessageStore;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.firestore.FirestoreOptions;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Builds a dedicated Firestore client per connector (no global {@code FirebaseApp}), so several
 * projects/databases can be used side by side and swapped at runtime.
 */
@Component
public class FirestoreMessageStoreFactory implements ConnectorFactory<MessageStore> {

    static final String EMULATOR_ENV = "FIRESTORE_EMULATOR_HOST";

    @Override
    public ConnectorDescriptor descriptor() {
        return new ConnectorDescriptor(ConnectorKind.MESSAGE_STORE, "firestore", "Google Cloud Firestore",
                "Serverless document database. Tenants are isolated by document hierarchy; sequence numbers, "
                        + "idempotency and receipts are transactional; needs no composite indexes. Each conversation "
                        + "sustains about one message per second (Firestore per-document write limit).",
                "Document", "BETA",
                List.of("Firebase Cloud Firestore", "Firestore emulator"),
                List.of(ConfigField.of("projectId", "Project id").required().placeholder("my-gcp-project").build(),
                        ConfigField.of("credentialsJson", "Service account key (JSON)").secret()
                                .description("Contents of a service-account key file. Optional when Credentials "
                                        + "path is set, on GCP with Application Default Credentials, or when "
                                        + EMULATOR_ENV + " is set").build(),
                        ConfigField.of("credentialsPath", "Credentials path")
                                .placeholder("/etc/chat/firestore-sa.json")
                                .description("Path to a service-account key file readable on every node").build(),
                        ConfigField.of("databaseId", "Database id").defaultValue("(default)")
                                .description("Firestore database within the project").build(),
                        ConfigField.of("collectionPrefix", "Collection prefix").defaultValue("chat_")
                                .description("Prepended to every collection name so several stores can share a database")
                                .build()));
    }

    @Override
    public MessageStore create(ConnectorSettings settings) throws IOException {
        FirestoreOptions.Builder options = FirestoreOptions.newBuilder()
                .setProjectId(settings.required("projectId"))
                .setDatabaseId(settings.string("databaseId", "(default)"));
        String emulator = System.getenv(EMULATOR_ENV);
        if (emulator != null && !emulator.isBlank()) {
            options.setEmulatorHost(emulator.trim()); // plaintext channel and emulator credentials
        } else {
            options.setCredentials(credentials(settings));
        }
        return new FirestoreMessageStore(options.build(), settings.string("collectionPrefix", "chat_"));
    }

    /** Service-account JSON, then a key file, then Application Default Credentials. */
    static GoogleCredentials credentials(ConnectorSettings settings) throws IOException {
        if (settings.has("credentialsJson")) {
            byte[] json = settings.string("credentialsJson").getBytes(StandardCharsets.UTF_8);
            try (InputStream in = new ByteArrayInputStream(json)) {
                return ServiceAccountCredentials.fromStream(in);
            } catch (IOException e) {
                throw new IllegalArgumentException("Service account key is not valid service-account JSON: "
                        + e.getMessage(), e);
            }
        }
        if (settings.has("credentialsPath")) {
            Path path = Path.of(settings.string("credentialsPath"));
            if (!Files.isReadable(path)) {
                throw new IllegalArgumentException("Credentials file is not readable: " + path);
            }
            try (InputStream in = Files.newInputStream(path)) {
                return ServiceAccountCredentials.fromStream(in);
            }
        }
        try {
            return GoogleCredentials.getApplicationDefault();
        } catch (IOException e) {
            throw new IllegalArgumentException("No Firestore credentials: set a service account key or credentials "
                    + "path (or configure Application Default Credentials)", e);
        }
    }
}
