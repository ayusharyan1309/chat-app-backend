package com.ayush.chat.chat.application;

import com.ayush.chat.chat.event.ControlMessage;
import com.ayush.chat.core.config.PlatformProperties;
import com.ayush.chat.core.error.DomainException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;

import java.util.List;

/** User-to-user blocks. Checked on every direct message, so lookups are cached per node. */
@Service
public class BlockService {

    private final TenantScopes scopes;
    private final ConversationNotifier notifier;
    private final Cache<String, Boolean> blocks;

    public BlockService(TenantScopes scopes, ConversationNotifier notifier, PlatformProperties properties) {
        this.scopes = scopes;
        this.notifier = notifier;
        this.blocks = Caffeine.newBuilder().expireAfterWrite(properties.getCaches().getBlocks()).maximumSize(500_000).build();
    }

    public List<String> listBlocked(String tenant, String me) {
        TenantScope scope = scopes.of(tenant);
        return scope.store().listBlocked(scope.slug(), me);
    }

    public void setBlocked(String tenant, String me, String other, boolean blocked) {
        if (me.equals(other)) {
            throw DomainException.badRequest("You cannot block yourself");
        }
        TenantScope scope = scopes.of(tenant);
        if (blocked && scope.store().findUser(scope.slug(), other).isEmpty()) {
            throw DomainException.notFound("User '" + other + "'");
        }
        scope.store().setBlocked(scope.slug(), me, other, blocked);
        ControlMessage change = ControlMessage.blockChanged(scope.slug(), me, other);
        forget(change.tenant(), change.ref());
        notifier.control(change);
    }

    public boolean isBlocked(TenantScope scope, String blocker, String blocked) {
        return blocks.get(key(scope.slug(), blocker + "|" + blocked), k -> scope.store().isBlocked(scope.slug(), blocker, blocked));
    }

    /** {@code pair} is {@code blocker|blocked}, as carried by the control message. */
    public void forget(String tenant, String pair) {
        blocks.invalidate(key(tenant, pair));
    }

    private static String key(String tenant, String pair) {
        return tenant + "|" + pair;
    }
}
