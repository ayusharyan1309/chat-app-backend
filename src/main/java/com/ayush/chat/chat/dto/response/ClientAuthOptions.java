package com.ayush.chat.chat.dto.response;

import com.ayush.chat.tenant.model.ExternalAuthType;

public record ClientAuthOptions(boolean devLoginEnabled, ExternalAuthType externalType) {
}
