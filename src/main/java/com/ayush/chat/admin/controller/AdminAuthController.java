package com.ayush.chat.admin.controller;

import com.ayush.chat.admin.dto.request.AdminLoginRequest;
import com.ayush.chat.admin.dto.request.ChangePasswordRequest;
import com.ayush.chat.admin.dto.request.CreateAdminRequest;
import com.ayush.chat.admin.dto.response.AdminLoginResponse;
import com.ayush.chat.admin.dto.response.AdminView;
import com.ayush.chat.auth.dto.LoginResult;
import com.ayush.chat.auth.security.Principals.AdminPrincipal;
import com.ayush.chat.auth.service.AdminAuthService;
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

import java.util.List;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminAuthController {

    private final AdminAuthService admins;

    @PostMapping("/auth/login")
    public AdminLoginResponse login(@RequestBody AdminLoginRequest request, HttpServletRequest http) {
        // getRemoteAddr() already reflects X-Forwarded-For, but only when it came from a trusted proxy.
        LoginResult result = admins.login(request.email(), request.password(), http.getRemoteAddr());
        return new AdminLoginResponse(result.token().token(), result.token().expiresAt(), AdminView.of(result.admin()));
    }

    @GetMapping("/auth/me")
    public AdminView me(@AuthenticationPrincipal AdminPrincipal me) {
        return AdminView.of(admins.get(me.id()));
    }

    @PostMapping("/auth/change-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@AuthenticationPrincipal AdminPrincipal me, @RequestBody ChangePasswordRequest request) {
        admins.changePassword(me, request.currentPassword(), request.newPassword());
    }

    @GetMapping("/admins")
    public List<AdminView> list() {
        return admins.list().stream().map(AdminView::of).toList();
    }

    @PostMapping("/admins")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminView create(@AuthenticationPrincipal AdminPrincipal me, @RequestBody CreateAdminRequest request) {
        return AdminView.of(admins.create(me, request.email(), request.name(), request.password(), request.role()));
    }

    @DeleteMapping("/admins/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AdminPrincipal me, @PathVariable Long id) {
        admins.delete(me, id);
    }
}
