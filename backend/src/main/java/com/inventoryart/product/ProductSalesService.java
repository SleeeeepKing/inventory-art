package com.inventoryart.product;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** Computes sales facts on demand so derived totals are never written back to products. */
@Service
public class ProductSalesService {
    private final NamedParameterJdbcTemplate jdbc;

    public ProductSalesService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Map<UUID, Summary> summarize(Collection<Product> products) {
        Map<UUID, Summary> result = new HashMap<>();
        Map<UUID, List<Product>> byTenant = products.stream()
            .collect(Collectors.groupingBy(Product::getTenantId));
        byTenant.forEach((tenantId, tenantProducts) -> {
            List<UUID> productIds = tenantProducts.stream().map(Product::getId).distinct().toList();
            jdbc.query("""
                select oi.product_id,
                       coalesce(sum(oi.quantity - oi.refunded_quantity), 0)::bigint as units_sold,
                       coalesce(sum(oi.line_total * (oi.quantity - oi.refunded_quantity) / oi.quantity), 0) as revenue,
                       max(o.order_date) as last_sale_at
                  from order_items oi
                  join orders o on o.tenant_id = oi.tenant_id and o.id = oi.order_id
                  join products p on p.tenant_id = oi.tenant_id and p.id = oi.product_id
                 where oi.tenant_id = :tenantId
                   and oi.product_id in (:productIds)
                   and upper(o.currency) = upper(p.currency)
                   and o.status in ('CONFIRMED','COMPLETED','PARTIALLY_REFUNDED','REFUNDED')
                 group by oi.product_id
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("productIds", productIds), row -> {
                UUID productId = row.getObject("product_id", UUID.class);
                Timestamp lastSale = row.getTimestamp("last_sale_at");
                result.put(productId, new Summary(row.getLong("units_sold"), row.getBigDecimal("revenue"),
                    lastSale == null ? null : lastSale.toInstant()));
            });
        });
        return Map.copyOf(result);
    }

    public record Summary(long unitsSold, BigDecimal revenue, Instant lastSaleAt) {
        public static final Summary EMPTY = new Summary(0, BigDecimal.ZERO, null);
    }
}
