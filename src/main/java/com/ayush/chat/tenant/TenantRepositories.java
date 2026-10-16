package com.ayush.chat.tenant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public final class TenantRepositories {

    private TenantRepositories() {
    }

    public interface TenantRepository extends JpaRepository<Tenant, Long> {
        Optional<Tenant> findBySlug(String slug);

        @Query("select distinct t from Tenant t left join fetch t.featureOverrides where t.slug = :slug")
        Optional<Tenant> findBySlugWithFeatures(@org.springframework.data.repository.query.Param("slug") String slug);

        boolean existsBySlug(String slug);

        long countByPlanCode(String planCode);

        long countByStatusIn(List<Tenant.Status> statuses);

        @Query("select t from Tenant t where t.status <> com.ayush.chat.tenant.Tenant.Status.DELETED order by t.createdAt desc")
        List<Tenant> findAllVisible();

        @Query("select t from Tenant t where t.status = com.ayush.chat.tenant.Tenant.Status.TRIAL and t.trialEndsAt < CURRENT_TIMESTAMP")
        List<Tenant> findExpiredTrials();
    }

    public interface PlanRepository extends JpaRepository<Plan, Long> {
        Optional<Plan> findByCode(String code);

        Optional<Plan> findFirstByDefaultPlanTrue();

        List<Plan> findAllByOrderBySortOrderAscIdAsc();
    }
}
