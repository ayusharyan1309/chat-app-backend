package com.ayush.chat.auth;

import com.ayush.chat.core.config.PlatformProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Locale;

/**
 * Creates the first dashboard owner from {@code platform.security.bootstrap-admin} when no admin
 * exists. Runs once the context is ready, in its own transaction; when two nodes start together the
 * unique email constraint lets exactly one of them win.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class BootstrapOwner implements ApplicationRunner {

    private final AdminUserRepository admins;
    private final PasswordEncoder passwordEncoder;
    private final PlatformProperties properties;
    private final TransactionTemplate transactions;

    @Override
    public void run(ApplicationArguments args) {
        try {
            transactions.executeWithoutResult(status -> {
                if (admins.count() > 0) {
                    return;
                }
                PlatformProperties.BootstrapAdmin bootstrap = properties.getSecurity().getBootstrapAdmin();
                AdminUser owner = new AdminUser();
                owner.setEmail(bootstrap.getEmail().toLowerCase(Locale.ROOT));
                owner.setName(bootstrap.getName());
                owner.setRole(AdminUser.Role.OWNER);
                owner.setPasswordHash(passwordEncoder.encode(bootstrap.getPassword()));
                admins.saveAndFlush(owner);
                log.warn("Created bootstrap owner {} — change the password after first login.", owner.getEmail());
            });
        } catch (DataIntegrityViolationException raced) {
            log.info("Bootstrap owner was created by another node");
        }
    }
}
