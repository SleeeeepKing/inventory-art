package com.inventoryart.expense;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SalesEventExpenseRepository extends JpaRepository<SalesEventExpense, UUID> {
  Optional<SalesEventExpense> findByIdAndTenantIdAndEventId(UUID id, UUID tenantId, UUID eventId);

  Page<SalesEventExpense> findAllByTenantIdAndEventIdAndStatus(
      UUID tenantId, UUID eventId, ExpenseStatus status, Pageable pageable);

  Page<SalesEventExpense> findAllByTenantIdAndEventId(
      UUID tenantId, UUID eventId, Pageable pageable);
}
