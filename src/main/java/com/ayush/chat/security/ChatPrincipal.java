package com.ayush.chat.security;

import lombok.*;

import java.security.Principal;

@Getter
@Setter
@Data
@AllArgsConstructor
public class ChatPrincipal implements Principal {
    private final String name;
    private final String userType; // "ADMIN" or "APP"
    private final String userId;

    private final Long orgId;


    @Override
    public String getName() {
        return name;
    }

    public String getUserId() {
        return userId;
    }

    public String getUserType() {
        return userType;
    }

    public Long getOrgId() {
        return orgId;
    }
}
