package com.inventoryart.expense;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExpenseCategoryRepository extends JpaRepository<ExpenseCategory, UUID> {
  Optional<ExpenseCategory> findByIdAndTenantId(UUID id, UUID tenantId);

  Optional<ExpenseCategory> findByTenantIdAndNameIgnoreCase(UUID tenantId, String name);

  List<ExpenseCategory> findAllByTenantIdOrderByNameAsc(UUID tenantId);

  List<ExpenseCategory> findAllByTenantIdAndEnabledOrderByNameAsc(UUID tenantId, boolean enabled);
}
