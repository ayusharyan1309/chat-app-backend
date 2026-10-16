package com.ayush.chat.chat.application;

import com.ayush.chat.audit.AuditAction;
import com.ayush.chat.audit.AuditService;
import com.ayush.chat.chat.Views.UserView;
import com.ayush.chat.chat.event.ControlMessage;
import com.ayush.chat.core.error.DomainException;
import com.ayush.chat.store.model.ChatUser;
import com.ayush.chat.store.model.FieldLimits;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * End users of a tenant: provisioning (server API and just-in-time logins), profiles, push
 * devices, search and deactivation.
 */
@Service
@RequiredArgsConstructor
public class UserDirectoryService {

    private static final int MAX_PAGE = 200;

    private final TenantScopes scopes;
    private final ChatViews views;
    private final UserStatusService status;
    private final ConversationNotifier notifier;
    private final AuditService audit;

    public record UserInput(String id, String displayName, String email, String phone, String avatarUrl, String about,
                            Map<String, Object> metadata) {
    }

    /** Create or update a user; only the fields present in {@code input} change. */
    public UserView upsert(String tenant, UserInput input) {
        TenantScope scope = scopes.of(tenant);
        InputValidator.userId(input.id());
        InputValidator.profile(input.displayName(), input.email(), input.phone(), input.avatarUrl(), input.about());
        Optional<ChatUser> existing = scope.store().findUser(scope.slug(), input.id());
        if (existing.isEmpty()) {
            Long max = scope.tenant().limits().maxUsers();
            if (max != null && scope.store().countUsers(scope.slug()) >= max) {
                throw DomainException.quotaExceeded("User limit of " + max + " reached for this tenant");
            }
        }
        Instant now = Instant.now();
        ChatUser base = existing.orElseGet(() -> ChatUser.builder().tenantId(scope.slug()).id(input.id()).createdAt(now).build());
        ChatUser user = base.toBuilder()
                .displayName(firstNonBlank(input.displayName(), base.getDisplayName(), input.id()))
                .email(input.email() != null ? input.email() : base.getEmail())
                .phone(input.phone() != null ? input.phone() : base.getPhone())
                .avatarUrl(input.avatarUrl() != null ? input.avatarUrl() : base.getAvatarUrl())
                .about(input.about() != null ? input.about() : base.getAbout())
                .metadata(input.metadata() != null ? input.metadata() : base.getMetadata())
                .updatedAt(now)
                .build();
        ChatUser saved = scope.store().upsertUser(user);
        status.forget(scope.slug(), saved.getId());
        return views.user(scope, saved);
    }

    /** Just-in-time provisioning for external and dev logins; never overwrites an existing profile. */
    public UserView ensure(String tenant, String userId, String displayName, String email) {
        TenantScope scope = scopes.of(tenant);
        Optional<ChatUser> existing = scope.store().findUser(scope.slug(), userId);
        if (existing.isPresent()) {
            if (existing.get().isDeactivated()) {
                throw DomainException.forbidden("User is deactivated");
            }
            return views.user(scope, existing.get());
        }
        return upsert(tenant, new UserInput(userId, displayName, email, null, null, null, null));
    }

    /** Deactivate: drops push devices and closes the user's sockets on every node. */
    public void deactivate(String tenant, String userId) {
        TenantScope scope = scopes.of(tenant);
        ChatUser user = require(scope, userId);
        scope.store().upsertUser(user.toBuilder().deactivated(true).pushTokens(new LinkedHashSet<>())
                .updatedAt(Instant.now()).build());
        status.markDeactivated(scope.slug(), userId);
        notifier.control(ControlMessage.userDeactivated(scope.slug(), userId));
        audit.record(AuditAction.USER_DEACTIVATED, userId, scope.slug(), null);
    }

    public UserView get(String tenant, String userId) {
        TenantScope scope = scopes.of(tenant);
        return views.user(scope, require(scope, userId));
    }

    public UserView updateProfile(String tenant, String userId, String displayName, String avatarUrl, String about) {
        InputValidator.profile(displayName, null, null, avatarUrl, about);
        TenantScope scope = scopes.of(tenant);
        ChatUser user = require(scope, userId);
        ChatUser updated = scope.store().upsertUser(user.toBuilder()
                .displayName(displayName != null && !displayName.isBlank() ? displayName.trim() : user.getDisplayName())
                .avatarUrl(avatarUrl != null ? avatarUrl : user.getAvatarUrl())
                .about(about != null ? about : user.getAbout())
                .updatedAt(Instant.now()).build());
        return views.user(scope, updated);
    }

    /** Register a push token; the oldest is evicted beyond {@link FieldLimits#MAX_DEVICES_PER_USER}. */
    public void addDevice(String tenant, String userId, String token) {
        InputValidator.deviceToken(token);
        TenantScope scope = scopes.of(tenant);
        ChatUser user = require(scope, userId);
        Set<String> tokens = new LinkedHashSet<>(user.getPushTokens());
        tokens.add(token);
        while (tokens.size() > FieldLimits.MAX_DEVICES_PER_USER) {
            tokens.remove(tokens.iterator().next());
        }
        scope.store().upsertUser(user.toBuilder().pushTokens(tokens).updatedAt(Instant.now()).build());
    }

    public void removeDevices(String tenant, String userId, Collection<String> tokens) {
        TenantScope scope = scopes.of(tenant);
        scope.store().findUser(scope.slug(), userId).ifPresent(user -> {
            Set<String> remaining = new LinkedHashSet<>(user.getPushTokens());
            if (remaining.removeAll(tokens)) {
                scope.store().upsertUser(user.toBuilder().pushTokens(remaining).updatedAt(Instant.now()).build());
            }
        });
    }

    /** End-user search (respects the tenant's search setting, hides deactivated users and the viewer). */
    public List<UserView> search(String tenant, String viewerId, String query, int limit) {
        TenantScope scope = scopes.of(tenant);
        if (!Boolean.TRUE.equals(scope.tenant().settings().allowUserSearch())) {
            throw DomainException.forbidden("User search is disabled for this tenant");
        }
        List<ChatUser> visible = scope.store().searchUsers(scope.slug(), query, 0, InputValidator.clamp(limit, 1, MAX_PAGE))
                .stream().filter(u -> !u.isDeactivated() && !u.getId().equals(viewerId)).toList();
        return views.users(scope, visible);
    }

    /** Operator listing (server API, dashboard): everyone, including deactivated users. */
    public List<UserView> list(String tenant, String query, int offset, int limit) {
        TenantScope scope = scopes.of(tenant);
        return views.users(scope, scope.store().searchUsers(scope.slug(), query, Math.max(0, offset),
                InputValidator.clamp(limit, 1, MAX_PAGE)));
    }

    public long count(String tenant) {
        TenantScope scope = scopes.of(tenant);
        return scope.store().countUsers(scope.slug());
    }

    private static ChatUser require(TenantScope scope, String userId) {
        return scope.store().findUser(scope.slug(), userId)
                .orElseThrow(() -> DomainException.notFound("User '" + userId + "'"));
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }
}
