package com.ayush.chat.auth;

import com.ayush.chat.core.config.PlatformProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Current role of each dashboard operator, cached briefly so every request can re-check it:
 * deleted or demoted admins lose access within {@code platform.caches.admin-roles}, not when their
 * token expires.
 */
@Component
public class AdminDirectory {

    private final AdminUserRepository admins;
    private final Cache<Long, Optional<AdminUser.Role>> roles;

    public AdminDirectory(AdminUserRepository admins, PlatformProperties properties) {
        this.admins = admins;
        this.roles = Caffeine.newBuilder().expireAfterWrite(properties.getCaches().getAdminRoles()).maximumSize(10_000).build();
    }

    /** Empty when the account no longer exists or is inactive. */
    public Optional<AdminUser.Role> currentRole(Long adminId) {
        return roles.get(adminId, id -> admins.findById(id).filter(AdminUser::isActive).map(AdminUser::getRole));
    }

    public void forget(Long adminId) {
        roles.invalidate(adminId);
    }
}
