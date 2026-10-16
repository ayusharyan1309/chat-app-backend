package com.ayush.chat.chat.controller;

import com.ayush.chat.auth.dto.ExternalIdentity;
import com.ayush.chat.auth.dto.IssuedToken;
import com.ayush.chat.auth.service.ExternalTokenVerifier;
import com.ayush.chat.auth.service.UserTokenService;
import com.ayush.chat.chat.dto.request.DevLoginRequest;
import com.ayush.chat.chat.dto.request.TokenExchangeRequest;
import com.ayush.chat.chat.dto.response.ClientAuthOptions;
import com.ayush.chat.chat.dto.response.TenantClientConfig;
import com.ayush.chat.chat.dto.response.UserTokenResponse;
import com.ayush.chat.chat.dto.response.UserView;
import com.ayush.chat.chat.service.UserDirectoryService;
import com.ayush.chat.core.exception.DomainException;
import com.ayush.chat.tenant.model.ExternalAuthType;
import com.ayush.chat.tenant.model.Feature;
import com.ayush.chat.tenant.model.TenantConfig;
import com.ayush.chat.tenant.model.TenantSnapshot;
import com.ayush.chat.tenant.service.TenantDirectory;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

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
    public UserTokenResponse devLogin(@RequestBody DevLoginRequest request) {
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
    public UserTokenResponse exchange(@RequestBody TokenExchangeRequest request) {
        TenantSnapshot t = tenants.requireUsable(request.tenant());
        t.require(Feature.EXTERNAL_AUTH);
        if (request.token() == null || request.token().isBlank()) {
            throw DomainException.badRequest("token is required");
        }
        ExternalIdentity identity = verifier.verify(t.auth().external(), request.token());
        return issue(t, users.ensure(t.slug(), identity.userId(), identity.displayName(), identity.email()));
    }

    private UserTokenResponse issue(TenantSnapshot t, UserView user) {
        IssuedToken token = tokens.issue(t.slug(), user.id(), Duration.ofMinutes(t.auth().tokenTtlMinutes()));
        return new UserTokenResponse(token.token(), token.expiresAt(), user);
    }
}
