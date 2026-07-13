package com.inventoryart.product;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Computes quantity facts on demand so derived totals are never written back to products. */
@Service
public class ProductSalesService {
  private final NamedParameterJdbcTemplate jdbc;

  public ProductSalesService(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional(readOnly = true)
  public Map<UUID, Summary> summarize(Collection<Product> products) {
    Map<UUID, Summary> result = new HashMap<>();
    Map<UUID, List<Product>> byTenant =
        products.stream().collect(Collectors.groupingBy(Product::getTenantId));
    byTenant.forEach(
        (tenantId, tenantProducts) -> {
          List<UUID> productIds = tenantProducts.stream().map(Product::getId).distinct().toList();
          jdbc.query(
              """
                select l.product_id,
                       coalesce(sum(l.quantity), 0)::bigint as units_sold,
                       max(b.attributed_date) as last_sale_date
                  from inventory_sale_lines l
                  join inventory_sale_batches b
                    on b.tenant_id=l.tenant_id and b.id=l.sale_batch_id
                 where l.tenant_id=:tenantId
                   and l.product_id in (:productIds)
                   and b.status='ACTIVE'
                 group by l.product_id
                """,
              new MapSqlParameterSource()
                  .addValue("tenantId", tenantId)
                  .addValue("productIds", productIds),
              (RowCallbackHandler)
                  row ->
                      result.put(
                          row.getObject("product_id", UUID.class),
                          new Summary(
                              row.getLong("units_sold"),
                              row.getObject("last_sale_date", LocalDate.class))));
        });
    return Map.copyOf(result);
  }

  public record Summary(long unitsSold, LocalDate lastSaleDate) {
    public static final Summary EMPTY = new Summary(0, null);
  }
}
