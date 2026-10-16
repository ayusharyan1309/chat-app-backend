package com.ayush.chat.chat.api;

import com.ayush.chat.auth.ExternalTokenVerifier;
import com.ayush.chat.auth.JwtService;
import com.ayush.chat.chat.ChatService;
import com.ayush.chat.chat.Views.UserView;
import com.ayush.chat.core.error.DomainException;
import com.ayush.chat.store.model.ChatUser;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Public client endpoints: tenant bootstrap config and user authentication.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ClientAuthController {

    private final TenantDirectory tenants;
    private final ChatService chat;
    private final JwtService jwt;
    private final ExternalTokenVerifier verifier;

    public record DevLoginRequest(String tenant, String userId, String displayName) {
    }

    public record ExchangeRequest(String tenant, String token) {
    }

    public record TokenResponse(String token, Instant expiresAt, UserView user) {
    }

    @GetMapping("/tenants/{slug}/config")
    public Map<String, Object> tenantConfig(@PathVariable String slug) {
        TenantSnapshot t = tenants.requireUsable(slug);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("tenant", t.slug());
        config.put("name", t.name());
        config.put("branding", t.has(Feature.CUSTOM_BRANDING) ? t.branding() : TenantConfig.Branding.DEFAULT);
        config.put("features", t.features().stream().map(Feature::getId).sorted().toList());
        config.put("auth", Map.of("devLoginEnabled", t.auth().devLoginEnabled(),
                "externalType", t.has(Feature.EXTERNAL_AUTH) ? t.auth().external().typeOrNone() : "none"));
        config.put("settings", t.settings());
        return config;
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
        ChatUser user = chat.ensureUser(t.slug(), request.userId().trim(), request.displayName(), null);
        return issue(t, user);
    }

    @PostMapping("/auth/exchange")
    public TokenResponse exchange(@RequestBody ExchangeRequest request) {
        TenantSnapshot t = tenants.requireUsable(request.tenant());
        t.require(Feature.EXTERNAL_AUTH);
        if (request.token() == null || request.token().isBlank()) {
            throw DomainException.badRequest("token is required");
        }
        ExternalTokenVerifier.Identity identity = verifier.verify(t.auth().external(), request.token());
        ChatUser user = chat.ensureUser(t.slug(), identity.userId(), identity.displayName(), identity.email());
        return issue(t, user);
    }

    private TokenResponse issue(TenantSnapshot t, ChatUser user) {
        JwtService.IssuedToken token = jwt.issueUser(t.slug(), user.getId(), Duration.ofMinutes(t.auth().tokenTtlMinutes()));
        return new TokenResponse(token.token(), token.expiresAt(), chat.getUser(t.slug(), user.getId()));
    }
}
