package com.ayush.chat.saas.feature;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to mark methods that require a specific feature to be enabled.
 * If the feature is not enabled for the current tenant, the method will not execute.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresFeature {
    
    /**
     * The required feature module.
     */
    FeatureModule value();
    
    /**
     * Whether to throw FeatureNotSupportedException if feature is disabled.
     */
    boolean throwException() default true;
    
    /**
     * Fallback method name to call if feature is disabled (alternative to exception).
     */
    String fallbackMethod() default "";
}
