package com.ayush.chat.auth;

import com.ayush.chat.auth.AuthRepositories.AdminUserRepository;
import com.ayush.chat.core.config.InfrastructureService;
import com.ayush.chat.core.config.PlatformProperties;
import com.ayush.chat.core.error.ApiException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Locale;

/**
 * Dashboard operators: login (with brute-force throttling), bootstrap owner and admin management.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminAuthService {

    private static final int MAX_FAILED_ATTEMPTS = 10;
    private static final int MAX_FAILED_ATTEMPTS_PER_ACCOUNT = 50;

    private final AdminUserRepository admins;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwt;
    private final PlatformProperties properties;
    private final InfrastructureService infrastructure;

    public record LoginResult(JwtService.IssuedToken token, AdminUser admin) {
    }

    /** Admin id -> current role (empty when deleted or inactive), so revocations bite within seconds. */
    private final Cache<Long, Optional<AdminUser.Role>> activeRoles = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(15)).maximumSize(10_000).build();

    /** The admin's current role, or empty when the account no longer exists or is inactive. */
    public Optional<AdminUser.Role> currentRole(Long id) {
        return activeRoles.get(id, k -> admins.findById(k).filter(AdminUser::isActive).map(AdminUser::getRole));
    }

    @PostConstruct
    void bootstrapOwner() {
        if (admins.count() > 0) {
            return;
        }
        PlatformProperties.BootstrapAdmin bootstrap = properties.getSecurity().getBootstrapAdmin();
        AdminUser owner = new AdminUser();
        owner.setEmail(bootstrap.getEmail().toLowerCase(Locale.ROOT));
        owner.setName(bootstrap.getName());
        owner.setRole(AdminUser.Role.OWNER);
        owner.setPasswordHash(passwordEncoder.encode(bootstrap.getPassword()));
        admins.save(owner);
        log.warn("Created bootstrap owner {} — change the password after first login.", owner.getEmail());
    }

    @Transactional
    public LoginResult login(String email, String password, String ip) {
        if (email == null || password == null) {
            throw ApiException.badRequest("email and password are required");
        }
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        // Two windows: per account+IP (normal typos) and per account across all IPs (distributed guessing).
        String ipKey = "login-fail:" + normalized + ":" + ip;
        String accountKey = "login-fail:" + normalized;
        long ipFailures = infrastructure.cache().get(ipKey).map(Long::parseLong).orElse(0L);
        long accountFailures = infrastructure.cache().get(accountKey).map(Long::parseLong).orElse(0L);
        if (ipFailures >= MAX_FAILED_ATTEMPTS || accountFailures >= MAX_FAILED_ATTEMPTS_PER_ACCOUNT) {
            throw ApiException.rateLimited("Too many failed attempts. Try again in 15 minutes.");
        }
        AdminUser admin = admins.findByEmailIgnoreCase(normalized).filter(AdminUser::isActive).orElse(null);
        if (admin == null || !passwordEncoder.matches(password, admin.getPasswordHash())) {
            infrastructure.cache().increment(ipKey, 1, Duration.ofMinutes(15));
            infrastructure.cache().increment(accountKey, 1, Duration.ofMinutes(15));
            throw ApiException.unauthorized("Invalid email or password");
        }
        infrastructure.cache().delete(ipKey);
        admin.setLastLoginAt(Instant.now());
        admins.save(admin);
        return new LoginResult(jwt.issueAdmin(admin), admin);
    }

    @Transactional(readOnly = true)
    public AdminUser get(Long id) {
        return admins.findById(id).filter(AdminUser::isActive).orElseThrow(() -> ApiException.unauthorized("Admin no longer exists"));
    }

    @Transactional(readOnly = true)
    public List<AdminUser> list() {
        return admins.findAll();
    }

    @Transactional
    public AdminUser create(String email, String name, String password, AdminUser.Role role) {
        if (email == null || !email.contains("@")) {
            throw ApiException.badRequest("A valid email is required");
        }
        validatePassword(password);
        if (admins.findByEmailIgnoreCase(email.trim()).isPresent()) {
            throw ApiException.conflict("An admin with this email already exists");
        }
        AdminUser admin = new AdminUser();
        admin.setEmail(email.trim().toLowerCase(Locale.ROOT));
        admin.setName(name);
        admin.setRole(role == null ? AdminUser.Role.VIEWER : role);
        admin.setPasswordHash(passwordEncoder.encode(password));
        return admins.save(admin);
    }

    @Transactional
    public void delete(Long id, Long actorId, AdminUser.Role actorRole) {
        if (id.equals(actorId)) {
            throw ApiException.badRequest("You cannot delete your own account");
        }
        AdminUser admin = admins.findById(id).orElseThrow(() -> ApiException.notFound("Admin"));
        if (admin.getRole() == AdminUser.Role.OWNER && actorRole != AdminUser.Role.OWNER) {
            throw ApiException.forbidden("Only owners can remove owners");
        }
        if (admin.getRole() == AdminUser.Role.OWNER && admins.countByRoleAndActiveTrue(AdminUser.Role.OWNER) <= 1) {
            throw ApiException.conflict("Cannot delete the last owner");
        }
        admins.delete(admin);
        activeRoles.invalidate(id);
    }

    @Transactional
    public void changePassword(Long id, String current, String next) {
        AdminUser admin = get(id);
        if (current == null || !passwordEncoder.matches(current, admin.getPasswordHash())) {
            throw ApiException.badRequest("Current password is incorrect");
        }
        validatePassword(next);
        admin.setPasswordHash(passwordEncoder.encode(next));
        admins.save(admin);
    }

    private static void validatePassword(String password) {
        if (password == null || password.length() < 10) {
            throw ApiException.badRequest("Password must be at least 10 characters");
        }
    }
}
