package com.ayush.chat.tenant.repository;

import com.ayush.chat.tenant.entity.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TenantRepository extends JpaRepository<Tenant, Long> {

    Optional<Tenant> findBySlug(String slug);

    @Query("select distinct t from Tenant t left join fetch t.featureOverrides where t.slug = :slug")
    Optional<Tenant> findBySlugWithFeatures(@Param("slug") String slug);

    boolean existsBySlug(String slug);

    long countByPlanCode(String planCode);

    /** Rows of {planCode, count} over tenants that are not deleted. */
    @Query("select t.planCode, count(t) from Tenant t where t.status <> com.ayush.chat.tenant.entity.Tenant.Status.DELETED"
            + " group by t.planCode")
    List<Object[]> countByPlan();

    long countByStatusIn(List<Tenant.Status> statuses);

    @Query("select t from Tenant t where t.status <> com.ayush.chat.tenant.entity.Tenant.Status.DELETED order by t.createdAt desc")
    List<Tenant> findAllVisible();

    @Query("select t from Tenant t where t.status = com.ayush.chat.tenant.entity.Tenant.Status.TRIAL and t.trialEndsAt < CURRENT_TIMESTAMP")
    List<Tenant> findExpiredTrials();
}
