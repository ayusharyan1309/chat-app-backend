package com.ayush.chat.tenant.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Value objects stored as JSON columns on the tenant row, with their JPA converters.
 */
public final class TenantConfig {

    private TenantConfig() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Branding(String displayName, String logoUrl, String primaryColor, String accentColor) {
        public static final Branding DEFAULT = new Branding(null, null, "#0B6E4F", "#F2A541");

        public static Branding named(String displayName) {
            return new Branding(displayName, null, DEFAULT.primaryColor, DEFAULT.accentColor);
        }
    }

    public record Settings(Integer maxMessageLength, Integer editWindowMinutes, Integer maxFileSizeMb,
                           Boolean allowUserSearch) {

        public static final Settings DEFAULT = new Settings(4096, 15, 25, true);

        /** Fill unset values from defaults. */
        public Settings withDefaults() {
            return new Settings(
                    maxMessageLength != null ? maxMessageLength : DEFAULT.maxMessageLength,
                    editWindowMinutes != null ? editWindowMinutes : DEFAULT.editWindowMinutes,
                    maxFileSizeMb != null ? maxFileSizeMb : DEFAULT.maxFileSizeMb,
                    allowUserSearch != null ? allowUserSearch : DEFAULT.allowUserSearch);
        }
    }

    /**
     * How end users authenticate. Platform-issued tokens (via the server API) always work;
     * {@code external} adds an exchange for Firebase ID tokens or the tenant's own JWTs.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Auth(Boolean devLoginEnabled, Integer tokenTtlMinutes, External external) {

        public static final Auth DEFAULT = new Auth(false, 1440, External.NONE);

        public Auth withDefaults() {
            return new Auth(devLoginEnabled != null ? devLoginEnabled : DEFAULT.devLoginEnabled,
                    tokenTtlMinutes != null ? tokenTtlMinutes : DEFAULT.tokenTtlMinutes,
                    external != null ? external : External.NONE);
        }

        public Auth withExternal(External replacement) {
            return new Auth(devLoginEnabled, tokenTtlMinutes, replacement);
        }
    }

    /** {@code jwtSecret} is encrypted at rest; {@link TenantDirectory} decrypts it into snapshots. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record External(ExternalAuthType type, String firebaseProjectId, String jwtIssuer, String jwtAudience,
                           String jwksUrl, String jwtSecret, String userIdClaim, String nameClaim, String emailClaim) {

        public static final External NONE = new External(ExternalAuthType.NONE, null, null, null, null, null,
                "sub", "name", "email");

        public ExternalAuthType typeOrNone() {
            return type == null ? ExternalAuthType.NONE : type;
        }

        public External withJwtSecret(String secret) {
            return new External(type, firebaseProjectId, jwtIssuer, jwtAudience, jwksUrl, secret, userIdClaim, nameClaim,
                    emailClaim);
        }
    }
}
