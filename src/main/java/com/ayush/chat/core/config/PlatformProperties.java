package com.ayush.chat.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bootstrap configuration ({@code platform.*} in application.yml / environment).
 * Connector settings here are the defaults; the dashboard can override them at runtime.
 */
@Data
@ConfigurationProperties(prefix = "platform")
public class PlatformProperties {

    private String version = "2.0.0";

    /** Unique id of this node; generated when blank. */
    private String nodeId;

    /** Externally reachable base URL, used to build media links. */
    private String publicUrl = "http://localhost:8080";

    private List<String> corsAllowedOrigins = List.of("http://localhost:5173");

    /** How often nodes poll the control plane for connector config changes made on other nodes. */
    private Duration configPollInterval = Duration.ofSeconds(5);

    private Security security = new Security();

    private Bus bus = new Bus();

    private Media media = new Media();

    /** Bootstrap connector config keyed by kind slug (message-store, cache, event-bus, blob-store, push). */
    private Map<String, ConnectorBootstrap> connectors = new LinkedHashMap<>();

    @Data
    public static class Security {
        private String masterKey;
        private String jwtSecret;
        private Duration adminTokenTtl = Duration.ofHours(12);
        private Duration userTokenTtl = Duration.ofHours(24);
        private BootstrapAdmin bootstrapAdmin = new BootstrapAdmin();
    }

    @Data
    public static class BootstrapAdmin {
        private String email = "admin@local";
        private String password = "admin12345";
        private String name = "Platform Owner";
    }

    @Data
    public static class Bus {
        /** Prefix for internal topics on the event bus. */
        private String topicPrefix = "platform.";
    }

    @Data
    public static class Media {
        private Duration signedUrlTtl = Duration.ofHours(6);
        private long maxUploadBytes = 100L * 1024 * 1024;
    }

    @Data
    public static class ConnectorBootstrap {
        private String provider;
        private Map<String, Object> settings = new LinkedHashMap<>();
    }
}
