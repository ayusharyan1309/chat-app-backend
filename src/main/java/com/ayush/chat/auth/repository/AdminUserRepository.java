package com.ayush.chat.auth.repository;

import com.ayush.chat.auth.entity.AdminUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AdminUserRepository extends JpaRepository<AdminUser, Long> {

    Optional<AdminUser> findByEmailIgnoreCase(String email);

    long countByRoleAndActiveTrue(AdminUser.Role role);
}
