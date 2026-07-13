package com.inventoryart.product;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ProductFamilyRepository extends JpaRepository<ProductFamily, UUID> {
  Optional<ProductFamily> findByIdAndTenantId(UUID id, UUID tenantId);

  List<ProductFamily> findAllByTenantIdAndIdIn(UUID tenantId, List<UUID> ids);

  @Query(
      "select f from ProductFamily f where f.tenantId=:tenantId and (:q='' or lower(f.name) like lower(concat('%',:q,'%')) or lower(coalesce(f.category,'')) like lower(concat('%',:q,'%')) or lower(coalesce(f.artistName,'')) like lower(concat('%',:q,'%')) or exists (select p.id from Product p where p.tenantId=f.tenantId and p.familyId=f.id and lower(p.sku) like lower(concat('%',:q,'%')))) and (:allCategories=true or lower(coalesce(f.category,'')) in :categories) and exists (select variant.id from Product variant where variant.tenantId=f.tenantId and variant.familyId=f.id and (:enabled is null or variant.enabled=:enabled) and (:lowStock is null or (:lowStock=true and variant.currentStock<=variant.lowStockThreshold) or (:lowStock=false and variant.currentStock>variant.lowStockThreshold)))")
  Page<ProductFamily> search(
      UUID tenantId,
      String q,
      Boolean enabled,
      Boolean lowStock,
      boolean allCategories,
      List<String> categories,
      Pageable pageable);
}
