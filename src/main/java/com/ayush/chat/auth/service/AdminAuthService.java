package com.ayush.chat.auth.service;

import com.ayush.chat.audit.model.AuditAction;
import com.ayush.chat.audit.service.AuditService;
import com.ayush.chat.auth.dto.LoginResult;
import com.ayush.chat.auth.entity.AdminUser;
import com.ayush.chat.auth.repository.AdminUserRepository;
import com.ayush.chat.auth.security.Principals;
import com.ayush.chat.cache.CacheKeys;
import com.ayush.chat.cache.CacheStore;
import com.ayush.chat.core.config.PlatformProperties;
import com.ayush.chat.core.connector.runtime.PlatformConnectors;
import com.ayush.chat.core.exception.DomainException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Dashboard operators: login with brute-force throttling, and admin management. Role rules
 * (only owners create or remove owners; the last owner stays) are enforced here, not in controllers.
 */
@Service
@RequiredArgsConstructor
public class AdminAuthService {

    private static final int MIN_PASSWORD_LENGTH = 10;

    private final AdminUserRepository admins;
    private final AdminDirectory directory;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwt;
    private final PlatformProperties properties;
    private final PlatformConnectors platform;
    private final AuditService audit;

    /**
     * Two failure windows: per account and IP (typos) and per account across all IPs (distributed
     * guessing). Unknown accounts are counted too, so responses never reveal which emails exist.
     */
    @Transactional
    public LoginResult login(String email, String password, String ip) {
        if (email == null || password == null) {
            throw DomainException.badRequest("email and password are required");
        }
        PlatformProperties.AdminLogin limits = properties.getAdminLogin();
        String normalized = normalize(email);
        String ipKey = CacheKeys.loginFailures(normalized, ip);
        String accountKey = CacheKeys.loginFailures(normalized);
        CacheStore cache = platform.cache();
        if (count(cache, ipKey) >= limits.getMaxFailuresPerAccountAndIp()
                || count(cache, accountKey) >= limits.getMaxFailuresPerAccount()) {
            throw DomainException.rateLimited("Too many failed attempts. Try again in "
                    + limits.getFailureWindow().toMinutes() + " minutes.");
        }
        AdminUser admin = admins.findByEmailIgnoreCase(normalized).filter(AdminUser::isActive).orElse(null);
        if (admin == null || !passwordEncoder.matches(password, admin.getPasswordHash())) {
            cache.increment(ipKey, 1, limits.getFailureWindow());
            cache.increment(accountKey, 1, limits.getFailureWindow());
            throw DomainException.unauthorized("Invalid email or password");
        }
        cache.delete(ipKey);
        admin.setLastLoginAt(Instant.now());
        return new LoginResult(jwt.issueAdmin(admin), admin);
    }

    @Transactional(readOnly = true)
    public AdminUser get(Long id) {
        return admins.findById(id).filter(AdminUser::isActive).orElseThrow(() -> DomainException.unauthorized("Admin no longer exists"));
    }

    @Transactional(readOnly = true)
    public List<AdminUser> list() {
        return admins.findAll();
    }

    @Transactional
    public AdminUser create(Principals.AdminPrincipal actor, String email, String name, String password, AdminUser.Role role) {
        AdminUser.Role effectiveRole = role == null ? AdminUser.Role.VIEWER : role;
        if (effectiveRole == AdminUser.Role.OWNER && actor.role() != AdminUser.Role.OWNER) {
            throw DomainException.forbidden("Only owners can create owners");
        }
        if (email == null || !email.contains("@")) {
            throw DomainException.badRequest("A valid email is required");
        }
        validatePassword(password);
        if (admins.findByEmailIgnoreCase(email.trim()).isPresent()) {
            throw DomainException.conflict("An admin with this email already exists");
        }
        AdminUser admin = new AdminUser();
        admin.setEmail(normalize(email));
        admin.setName(name);
        admin.setRole(effectiveRole);
        admin.setPasswordHash(passwordEncoder.encode(password));
        admins.save(admin);
        audit.record(AuditAction.ADMIN_CREATED, admin.getEmail(), null, "role=" + admin.getRole());
        return admin;
    }

    @Transactional
    public void delete(Principals.AdminPrincipal actor, Long id) {
        if (id.equals(actor.id())) {
            throw DomainException.badRequest("You cannot delete your own account");
        }
        AdminUser admin = admins.findById(id).orElseThrow(() -> DomainException.notFound("Admin"));
        if (admin.getRole() == AdminUser.Role.OWNER) {
            if (actor.role() != AdminUser.Role.OWNER) {
                throw DomainException.forbidden("Only owners can remove owners");
            }
            if (admins.countByRoleAndActiveTrue(AdminUser.Role.OWNER) <= 1) {
                throw DomainException.conflict("Cannot delete the last owner");
            }
        }
        admins.delete(admin);
        directory.forget(id);
        audit.record(AuditAction.ADMIN_DELETED, String.valueOf(id), null, admin.getEmail());
    }

    @Transactional
    public void changePassword(Principals.AdminPrincipal actor, String current, String next) {
        AdminUser admin = get(actor.id());
        if (current == null || !passwordEncoder.matches(current, admin.getPasswordHash())) {
            throw DomainException.badRequest("Current password is incorrect");
        }
        validatePassword(next);
        admin.setPasswordHash(passwordEncoder.encode(next));
        audit.record(AuditAction.ADMIN_PASSWORD_CHANGED, admin.getEmail(), null, null);
    }

    private static long count(CacheStore cache, String key) {
        return cache.get(key).map(Long::parseLong).orElse(0L);
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static void validatePassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw DomainException.badRequest("Password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
    }
}
