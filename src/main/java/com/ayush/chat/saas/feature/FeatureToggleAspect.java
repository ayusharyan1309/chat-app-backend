package com.ayush.chat.saas.feature;

import com.ayush.chat.saas.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;

/**
 * AOP aspect that enforces feature flags on methods annotated with @RequiresFeature.
 * If the feature is not enabled for the current tenant, the method call is blocked.
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class FeatureToggleAspect {

    private final FeatureFlagService featureFlagService;

    @Pointcut("@annotation(requiresFeature)")
    public void requireFeaturePointcut(RequiresFeature requiresFeature) {
    }

    @Around("requireFeaturePointcut(requiresFeature)")
    public Object checkFeature(ProceedingJoinPoint joinPoint, RequiresFeature requiresFeature) throws Throwable {
        FeatureModule module = requiresFeature.value();
        
        String tenantId = String.valueOf(TenantContext.getCurrentTenantId());
        
        if (!featureFlagService.isFeatureEnabled(tenantId, module)) {
            String message = String.format("Feature '%s' is not enabled for tenant %s", 
                    module.getId(), tenantId);
            log.warn(message);
            
            if (requiresFeature.throwException()) {
                throw new FeatureNotSupportedException(message);
            }
            
            return requiresFeature.fallbackMethod().isEmpty() ? null : 
                    getDefaultReturnValue(joinPoint);
        }
        
        return joinPoint.proceed();
    }

    private Object getDefaultReturnValue(ProceedingJoinPoint joinPoint) {
        Class<?> returnType = joinPoint.getSignature().getReturnType();
        if (returnType.isPrimitive()) {
            if (returnType == boolean.class) return false;
            if (returnType == int.class) return 0;
            if (returnType == long.class) return 0L;
            if (returnType == double.class) return 0.0;
        }
        return null;
    }
}
