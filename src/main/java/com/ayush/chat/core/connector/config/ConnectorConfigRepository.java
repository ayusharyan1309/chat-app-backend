package com.ayush.chat.core.connector.config;

import com.ayush.chat.core.connector.ConnectorKind;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConnectorConfigRepository extends JpaRepository<ConnectorConfigEntity, Long> {

    Optional<ConnectorConfigEntity> findByScopeKeyAndKind(String scopeKey, ConnectorKind kind);

    List<ConnectorConfigEntity> findByScopeKey(String scopeKey);

    List<ConnectorConfigEntity> findByKindAndScopeKeyStartingWith(ConnectorKind kind, String scopeKeyPrefix);
}
