package com.ayush.chat.admin;

import com.ayush.chat.audit.AuditService;
import com.ayush.chat.auth.AdminAuthService;
import com.ayush.chat.auth.AdminUser;
import com.ayush.chat.auth.Principals.AdminPrincipal;
import com.ayush.chat.core.error.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminAuthController {

    private final AdminAuthService admins;
    private final AuditService audit;

    public record LoginRequest(String email, String password) {
    }

    public record AdminView(Long id, String email, String name, AdminUser.Role role, Instant lastLoginAt, Instant createdAt) {
        static AdminView of(AdminUser a) {
            return new AdminView(a.getId(), a.getEmail(), a.getName(), a.getRole(), a.getLastLoginAt(), a.getCreatedAt());
        }
    }

    public record LoginResponse(String token, Instant expiresAt, AdminView admin) {
    }

    public record PasswordChange(String currentPassword, String newPassword) {
    }

    public record CreateAdmin(String email, String name, String password, AdminUser.Role role) {
    }

    @PostMapping("/auth/login")
    public LoginResponse login(@RequestBody LoginRequest request, HttpServletRequest http) {
        // getRemoteAddr() already reflects X-Forwarded-For, but only when it came from a trusted proxy.
        AdminAuthService.LoginResult result = admins.login(request.email(), request.password(), http.getRemoteAddr());
        return new LoginResponse(result.token().token(), result.token().expiresAt(), AdminView.of(result.admin()));
    }

    @GetMapping("/auth/me")
    public AdminView me(@AuthenticationPrincipal AdminPrincipal me) {
        return AdminView.of(admins.get(me.id()));
    }

    @PostMapping("/auth/change-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@AuthenticationPrincipal AdminPrincipal me, @RequestBody PasswordChange request) {
        admins.changePassword(me.id(), request.currentPassword(), request.newPassword());
        audit.record("admin.password_changed", "admin", me.email(), null, null);
    }

    @GetMapping("/admins")
    public List<AdminView> list() {
        return admins.list().stream().map(AdminView::of).toList();
    }

    @PostMapping("/admins")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminView create(@AuthenticationPrincipal AdminPrincipal me, @RequestBody CreateAdmin request) {
        if (request.role() == AdminUser.Role.OWNER && me.role() != AdminUser.Role.OWNER) {
            throw DomainException.forbidden("Only owners can create owners");
        }
        AdminUser created = admins.create(request.email(), request.name(), request.password(), request.role());
        audit.record("admin.created", "admin", created.getEmail(), null, "role=" + created.getRole());
        return AdminView.of(created);
    }

    @DeleteMapping("/admins/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AdminPrincipal me, @PathVariable Long id) {
        admins.delete(id, me.id(), me.role());
        audit.record("admin.deleted", "admin", String.valueOf(id), null, null);
    }
}
