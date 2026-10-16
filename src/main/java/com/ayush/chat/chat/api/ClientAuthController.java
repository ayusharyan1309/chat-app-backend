package com.ayush.chat.chat.api;

import com.ayush.chat.auth.ExternalTokenVerifier;
import com.ayush.chat.auth.JwtService;
import com.ayush.chat.auth.UserTokenService;
import com.ayush.chat.chat.Views.UserView;
import com.ayush.chat.chat.application.UserDirectoryService;
import com.ayush.chat.core.error.DomainException;
import com.ayush.chat.tenant.ExternalAuthType;
import com.ayush.chat.tenant.Feature;
import com.ayush.chat.tenant.TenantConfig;
import com.ayush.chat.tenant.TenantDirectory;
import com.ayush.chat.tenant.TenantSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Public client endpoints: tenant bootstrap config and end-user sign-in.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ClientAuthController {

    private final TenantDirectory tenants;
    private final UserDirectoryService users;
    private final UserTokenService tokens;
    private final ExternalTokenVerifier verifier;

    public record DevLoginRequest(String tenant, String userId, String displayName) {
    }

    public record ExchangeRequest(String tenant, String token) {
    }

    public record TokenResponse(String token, Instant expiresAt, UserView user) {
    }

    public record ClientAuthOptions(boolean devLoginEnabled, ExternalAuthType externalType) {
    }

    /** What a client app needs before sign-in: branding, enabled features, auth options and limits. */
    public record TenantClientConfig(String tenant, String name, TenantConfig.Branding branding, List<String> features,
                                     ClientAuthOptions auth, TenantConfig.Settings settings) {
    }

    @GetMapping("/tenants/{slug}/config")
    public TenantClientConfig tenantConfig(@PathVariable String slug) {
        TenantSnapshot t = tenants.requireUsable(slug);
        return new TenantClientConfig(t.slug(), t.name(),
                t.has(Feature.CUSTOM_BRANDING) ? t.branding() : TenantConfig.Branding.DEFAULT,
                t.features().stream().map(Feature::getId).sorted().toList(),
                new ClientAuthOptions(Boolean.TRUE.equals(t.auth().devLoginEnabled()),
                        t.has(Feature.EXTERNAL_AUTH) ? t.auth().external().typeOrNone() : ExternalAuthType.NONE),
                t.settings());
    }

    @PostMapping("/auth/dev-login")
    public TokenResponse devLogin(@RequestBody DevLoginRequest request) {
        TenantSnapshot t = tenants.requireUsable(request.tenant());
        if (!Boolean.TRUE.equals(t.auth().devLoginEnabled())) {
            throw DomainException.forbidden("Dev login is disabled for this tenant");
        }
        if (request.userId() == null || request.userId().isBlank()) {
            throw DomainException.badRequest("userId is required");
        }
        return issue(t, users.ensure(t.slug(), request.userId().trim(), request.displayName(), null));
    }

    @PostMapping("/auth/exchange")
    public TokenResponse exchange(@RequestBody ExchangeRequest request) {
        TenantSnapshot t = tenants.requireUsable(request.tenant());
        t.require(Feature.EXTERNAL_AUTH);
        if (request.token() == null || request.token().isBlank()) {
            throw DomainException.badRequest("token is required");
        }
        ExternalTokenVerifier.Identity identity = verifier.verify(t.auth().external(), request.token());
        return issue(t, users.ensure(t.slug(), identity.userId(), identity.displayName(), identity.email()));
    }

    private TokenResponse issue(TenantSnapshot t, UserView user) {
        JwtService.IssuedToken token = tokens.issue(t.slug(), user.id(), Duration.ofMinutes(t.auth().tokenTtlMinutes()));
        return new TokenResponse(token.token(), token.expiresAt(), user);
    }
}
