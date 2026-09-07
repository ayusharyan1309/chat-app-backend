package com.ayush.chat.saas.tenant;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

/**
 * Interceptor that resolves tenant from request headers and sets TenantContext.
 * 
 * Headers:
 * - X-Tenant-ID: The tenant identifier
 * - X-Tenant-Domain: Alternative - resolve tenant by domain
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TenantInterceptor implements HandlerInterceptor {

    private static final String TENANT_ID_HEADER = "X-Tenant-ID";
    private static final String TENANT_DOMAIN_HEADER = "X-Tenant-Domain";
    
    private final TenantRepository tenantRepository;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, 
                            Object handler) {
        String tenantId = request.getHeader(TENANT_ID_HEADER);
        String tenantDomain = request.getHeader(TENANT_DOMAIN_HEADER);
        
        try {
            Tenant tenant = null;
            
            if (tenantId != null && !tenantId.isEmpty()) {
                tenant = tenantRepository.findByTenantIdentifier(tenantId).orElse(null);
            } else if (tenantDomain != null && !tenantDomain.isEmpty()) {
                tenant = tenantRepository.findByDomain(tenantDomain).orElse(null);
            }
            
            if (tenant != null && tenant.getIsActive()) {
                TenantContext.setCurrentTenant(tenant.getTenantIdentifier());
                TenantContext.setCurrentTenantId(tenant.getId());
                TenantContext.setCurrentTenantName(tenant.getTenantName());
                
                log.debug("Set tenant context for: {}", tenant.getTenantIdentifier());
            } else if (tenantId != null || tenantDomain != null) {
                log.warn("Tenant not found or inactive: {} / {}", tenantId, tenantDomain);
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\": \"Tenant not found or inactive\"}");
                return false;
            }
            // If no tenant header, proceed without tenant context (for public APIs)
            
        } catch (Exception e) {
            log.error("Error resolving tenant", e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return false;
        }
        
        return true;
    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response,
                          Object handler, ModelAndView modelAndView) {
        // Clear tenant context after request
        TenantContext.clear();
    }
}
