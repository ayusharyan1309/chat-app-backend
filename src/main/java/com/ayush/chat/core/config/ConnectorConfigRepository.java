package com.ayush.chat.core.config;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConnectorConfigRepository extends JpaRepository<ConnectorConfigEntity, Long> {

    Optional<ConnectorConfigEntity> findByScopeKeyAndKind(String scopeKey, String kind);

    List<ConnectorConfigEntity> findByScopeKey(String scopeKey);

    void deleteByScopeKey(String scopeKey);
}
