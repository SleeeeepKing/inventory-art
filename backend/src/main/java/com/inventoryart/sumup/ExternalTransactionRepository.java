package com.inventoryart.sumup;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

interface ExternalTransactionRepository extends JpaRepository<ExternalTransaction, UUID> {
    java.util.Optional<ExternalTransaction> findByIdAndTenantId(UUID id, UUID tenantId);
    @Query("""
        select e from ExternalTransaction e
        where e.tenantId = :tenantId
          and (:status is null or e.transactionStatus = :status)
          and e.occurredAt >= :from
          and e.occurredAt < :to
          and e.active = true
        """)
    Page<ExternalTransaction> search(@Param("tenantId") UUID tenantId,
                                     @Param("status") String status,
                                     @Param("from") Instant from,
                                     @Param("to") Instant to,
                                     Pageable pageable);

    @Query("""
        select e from ExternalTransaction e
        where (:tenantId is null or e.tenantId = :tenantId)
          and (:status is null or e.transactionStatus = :status)
          and e.occurredAt >= :from
          and e.occurredAt < :to
        """)
    Page<ExternalTransaction> adminSearch(@Param("tenantId") UUID tenantId,
                                          @Param("status") String status,
                                          @Param("from") Instant from,
                                          @Param("to") Instant to,
                                          Pageable pageable);
}
