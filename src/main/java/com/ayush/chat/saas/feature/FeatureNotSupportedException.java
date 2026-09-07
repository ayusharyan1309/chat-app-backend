package com.ayush.chat.saas.feature;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Exception thrown when a feature is not enabled for the current tenant.
 */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class FeatureNotSupportedException extends RuntimeException {
    
    public FeatureNotSupportedException(String message) {
        super(message);
    }
    
    public FeatureNotSupportedException(String featureId, String tenantId) {
        super(String.format("Feature '%s' is not enabled for tenant %s", featureId, tenantId));
    }
}
