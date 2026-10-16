package com.ayush.chat.tenant.service;

import com.ayush.chat.core.util.Json;
import com.ayush.chat.tenant.entity.Plan;
import com.ayush.chat.tenant.entity.Tenant;
import com.ayush.chat.tenant.entity.TenantFeatureOverride;
import com.ayush.chat.tenant.entity.converter.LimitsConverter;
import com.ayush.chat.tenant.entity.converter.SettingsConverter;
import com.ayush.chat.tenant.model.ExternalAuthType;
import com.ayush.chat.tenant.model.Feature;
import com.ayush.chat.tenant.model.FeatureSource;
import com.ayush.chat.tenant.model.TenantConfig;
import com.ayush.chat.tenant.model.TenantLimits;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TenantModelTest {

    @Test
    void tenantOverridesWinOverPlanLimitsFieldByField() {
        TenantLimits plan = new TenantLimits(100L, 1000L, 50L, 1024L, 20, 5);
        TenantLimits overrides = new TenantLimits(null, 5000L, null, null, 50, null);
        assertThat(plan.overriddenBy(overrides)).isEqualTo(new TenantLimits(100L, 5000L, 50L, 1024L, 50, 5));
        assertThat(plan.overriddenBy(null)).isEqualTo(plan);
    }

    @Test
    void settingsAndAuthFillDefaults() {
        TenantConfig.Settings settings = new TenantConfig.Settings(null, 0, null, false).withDefaults();
        assertThat(settings).isEqualTo(new TenantConfig.Settings(4096, 0, 25, false));
        TenantConfig.Auth auth = new TenantConfig.Auth(null, null, null).withDefaults();
        assertThat(auth.devLoginEnabled()).isFalse();
        assertThat(auth.tokenTtlMinutes()).isEqualTo(1440);
        assertThat(auth.external().typeOrNone()).isEqualTo(ExternalAuthType.NONE);
    }

    @Test
    void externalAuthTypeKeepsItsLowercaseWireFormat() {
        TenantConfig.External external = new TenantConfig.External(ExternalAuthType.FIREBASE, "proj", null, null, null,
                null, "sub", "name", "email");
        String json = Json.write(external);
        assertThat(json).contains("\"type\":\"firebase\"");
        assertThat(Json.read(json, TenantConfig.External.class)).isEqualTo(external);
        assertThat(Json.read("{\"type\":null}", TenantConfig.External.class).typeOrNone()).isEqualTo(ExternalAuthType.NONE);
    }

    @Test
    void storedRowsWithoutOptionalFieldsStillRead() {
        assertThat(new SettingsConverter().convertToEntityAttribute("{\"maxMessageLength\":10,\"retired\":1}"))
                .isEqualTo(new TenantConfig.Settings(10, null, null, null));
        assertThat(new LimitsConverter().convertToEntityAttribute(null)).isNull();
    }

    @Test
    void effectiveFeaturesAreCorePlusPlanAdjustedByOverrides() {
        Plan plan = new Plan();
        plan.setFeatureIds(Set.of(Feature.GROUP_CHAT.getId(), Feature.WEBHOOKS.getId(), Feature.VOICE_CALLS.getId()));
        Tenant tenant = new Tenant();
        tenant.getFeatureOverrides().add(override(tenant, Feature.WEBHOOKS, false));
        tenant.getFeatureOverrides().add(override(tenant, Feature.PRESENCE, true));

        Set<Feature> features = TenantDirectory.effectiveFeatures(plan, tenant);

        assertThat(features).containsAll(Feature.core())
                .contains(Feature.GROUP_CHAT, Feature.PRESENCE)
                .doesNotContain(Feature.WEBHOOKS)
                .as("planned features are never enabled").doesNotContain(Feature.VOICE_CALLS);
        assertThat(TenantDirectory.sourceOf(Feature.WEBHOOKS, tenant)).isEqualTo(FeatureSource.OVERRIDE);
        assertThat(TenantDirectory.sourceOf(Feature.GROUP_CHAT, tenant)).isEqualTo(FeatureSource.PLAN);
        assertThat(TenantDirectory.sourceOf(Feature.core().get(0), tenant)).isEqualTo(FeatureSource.CORE);
        assertThat(TenantDirectory.sourceOf(Feature.VOICE_CALLS, tenant)).isEqualTo(FeatureSource.PLANNED);
    }

    @Test
    void onlyActiveAndTrialTenantsAreUsable() {
        assertThat(Tenant.Status.ACTIVE.isUsable()).isTrue();
        assertThat(Tenant.Status.TRIAL.isUsable()).isTrue();
        assertThat(Tenant.Status.SUSPENDED.isUsable()).isFalse();
        assertThat(Tenant.Status.DELETED.isUsable()).isFalse();
    }

    private static TenantFeatureOverride override(Tenant tenant, Feature feature, boolean enabled) {
        TenantFeatureOverride o = new TenantFeatureOverride();
        o.setTenant(tenant);
        o.setFeatureId(feature.getId());
        o.setEnabled(enabled);
        return o;
    }
}
