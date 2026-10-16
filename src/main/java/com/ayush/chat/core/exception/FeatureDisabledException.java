package com.ayush.chat.core.exception;

/** The tenant's plan does not include the feature (403 {@code feature_disabled}). */
public class FeatureDisabledException extends DomainException {

    private final String featureId;

    public FeatureDisabledException(String featureId) {
        super(ErrorCode.FEATURE_DISABLED, "Feature '" + featureId + "' is not enabled for this tenant");
        this.featureId = featureId;
    }

    public String getFeatureId() {
        return featureId;
    }
}
