package com.ayush.chat.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every tunable of the platform ({@code platform.*} in application.yml / environment), with
 * production defaults. Connector settings here are bootstrap defaults; the dashboard can override
 * them at runtime. Registered as the {@code platformProperties} bean so {@code @Scheduled}
 * intervals can reference it.
 */
@Data
@Component("platformProperties")
@ConfigurationProperties(prefix = "platform")
public class PlatformProperties {

    /** Unique id of this node; generated when blank. */
    private String nodeId;

    /** Externally reachable base URL, used to build media links. */
    private String publicUrl = "http://localhost:8080";

    private List<String> corsAllowedOrigins = List.of("http://localhost:5173");

    /** How often nodes poll the control plane for connector config changes made on other nodes. */
    private Duration configPollInterval = Duration.ofSeconds(5);

    private Security security = new Security();
    private AdminLogin adminLogin = new AdminLogin();
    private Bus bus = new Bus();
    private Realtime realtime = new Realtime();
    private Presence presence = new Presence();
    private Cluster cluster = new Cluster();
    private Connectors connectorRuntime = new Connectors();
    private Caches caches = new Caches();
    private Locks locks = new Locks();
    private Webhooks webhooks = new Webhooks();
    private Metering metering = new Metering();
    private Jwks jwks = new Jwks();

    /** Bootstrap connector config keyed by kind slug (message-store, cache, event-bus, blob-store, push). */
    private Map<String, ConnectorBootstrap> connectors = new LinkedHashMap<>();

    @Data
    public static class Security {
        public static final String DEV_MASTER_KEY = "dev-only-master-key-change-me";

        private String masterKey;
        private String jwtSecret;
        private Duration adminTokenTtl = Duration.ofHours(12);
        private Duration userTokenTtl = Duration.ofHours(24);
        /** Bounds for per-tenant user token lifetimes. */
        private Duration minUserTokenTtl = Duration.ofMinutes(5);
        private Duration maxUserTokenTtl = Duration.ofDays(30);
        /** Lifetime of impersonation tokens issued from the dashboard. */
        private Duration impersonationTokenTtl = Duration.ofHours(8);
        private BootstrapAdmin bootstrapAdmin = new BootstrapAdmin();
        /** Allow tenant callback URLs (webhooks, JWKS) that resolve to private networks. */
        private boolean allowPrivateNetworkCallbacks;

        /** The configured master key, or the development key when unset or blank. */
        public String effectiveMasterKey() {
            return masterKey == null || masterKey.isBlank() ? DEV_MASTER_KEY : masterKey;
        }

        public boolean isUsingDevMasterKey() {
            return masterKey == null || masterKey.isBlank();
        }
    }

    @Data
    public static class BootstrapAdmin {
        private String email = "admin@local";
        private String password = "admin12345";
        private String name = "Platform Owner";
    }

    @Data
    public static class AdminLogin {
        private int maxFailuresPerAccountAndIp = 10;
        private int maxFailuresPerAccount = 50;
        private Duration failureWindow = Duration.ofMinutes(15);
    }

    @Data
    public static class Bus {
        /** Prefix for the platform's internal topics (connectors may add their own prefix). */
        private String topicPrefix = "";
    }

    @Data
    public static class Realtime {
        private int inboundThreads = 32;
        private int outboundThreads = 16;
        private DataSize maxFrameSize = DataSize.ofKilobytes(128);
        private DataSize sendBufferSize = DataSize.ofMegabytes(1);
        private Duration sendTimeLimit = Duration.ofSeconds(20);
        private Duration heartbeat = Duration.ofSeconds(20);
    }

    @Data
    public static class Presence {
        private Duration ttl = Duration.ofSeconds(60);
        private Duration heartbeat = Duration.ofSeconds(20);
        private int maxWatchedUsers = 500;
    }

    @Data
    public static class Cluster {
        private Duration nodeTtl = Duration.ofSeconds(30);
        private Duration heartbeat = Duration.ofSeconds(10);
        private int slotWindow = 256;
    }

    @Data
    public static class Connectors {
        /** How long a swapped-out connector keeps serving in-flight calls before it is closed. */
        private Duration retireGrace = Duration.ofSeconds(30);
        /** After a dedicated tenant connector fails to connect, requests fail fast for this long. */
        private Duration tenantFailureBackoff = Duration.ofSeconds(5);
    }

    /** Node-local cache lifetimes; cluster-wide invalidation makes these upper bounds on staleness. */
    @Data
    public static class Caches {
        private Duration tenantSnapshot = Duration.ofSeconds(15);
        private Duration userStatus = Duration.ofSeconds(30);
        private Duration membership = Duration.ofSeconds(10);
        private Duration conversation = Duration.ofSeconds(30);
        private Duration blocks = Duration.ofSeconds(10);
        private Duration apiKeys = Duration.ofSeconds(30);
        private Duration adminRoles = Duration.ofSeconds(15);
        private Duration webhookTargets = Duration.ofSeconds(30);
    }

    @Data
    public static class Locks {
        private Duration lease = Duration.ofSeconds(10);
        private Duration maxWait = Duration.ofSeconds(3);
    }

    @Data
    public static class Webhooks {
        private int maxAttempts = 5;
        private Duration initialBackoff = Duration.ofSeconds(10);
        private Duration requestTimeout = Duration.ofSeconds(10);
        private Duration deliveryRetention = Duration.ofDays(14);
    }

    @Data
    public static class Metering {
        private Duration usageFlushInterval = Duration.ofSeconds(30);
        private Duration metricsFlushInterval = Duration.ofSeconds(5);
        private Duration metricsRetention = Duration.ofHours(25);
    }

    @Data
    public static class Jwks {
        private Duration cacheTtl = Duration.ofHours(1);
        private Duration minRefreshInterval = Duration.ofSeconds(60);
        private Duration fetchTimeout = Duration.ofSeconds(5);
    }

    @Data
    public static class ConnectorBootstrap {
        private String provider;
        private Map<String, Object> settings = new LinkedHashMap<>();
    }
}
