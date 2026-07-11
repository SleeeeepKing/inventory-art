package com.inventoryart.report;

import com.inventoryart.exception.BusinessException;
import com.inventoryart.security.CurrentUser;
import com.inventoryart.security.CurrentUserService;
import com.inventoryart.user.UserRole;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ReportService {
    private final NamedParameterJdbcTemplate jdbc;
    private final CurrentUserService currentUser;

    public ReportService(NamedParameterJdbcTemplate jdbc, CurrentUserService currentUser) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true)
    public ReportDtos.Dashboard tenantDashboard(LocalDate start, LocalDate end) {
        return dashboard(currentUser.tenantId(), start, end, false);
    }

    @Transactional(readOnly = true)
    public ReportDtos.Dashboard adminDashboard(UUID tenantId, LocalDate start, LocalDate end) {
        CurrentUser user = currentUser.get();
        if (user.role() != UserRole.ADMIN) {
            throw new BusinessException("FORBIDDEN", "Administrator access required", HttpStatus.FORBIDDEN);
        }
        return dashboard(tenantId, start, end, true);
    }

    private ReportDtos.Dashboard dashboard(UUID tenantId, LocalDate requestedStart, LocalDate requestedEnd, boolean admin) {
        TenantSettings settings = settings(tenantId, admin);
        LocalDate end = requestedEnd == null ? LocalDate.now(settings.zone()) : requestedEnd;
        LocalDate start = requestedStart == null ? end.minusDays(29) : requestedStart;
        if (end.isBefore(start) || start.plusYears(2).isBefore(end)) {
            throw new BusinessException("INVALID_REPORT_RANGE", "Report date range must be ordered and no longer than two years");
        }
        Instant from = start.atStartOfDay(settings.zone()).toInstant();
        Instant to = end.plusDays(1).atStartOfDay(settings.zone()).toInstant();
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("tenantId", tenantId == null ? null : tenantId.toString())
            .addValue("from", Timestamp.from(from)).addValue("to", Timestamp.from(to))
            .addValue("timezone", settings.zone().getId());

        List<ReportDtos.CurrencyMetrics> metrics = jdbc.query(ReportSourcePolicy.INCLUDED_SALES_CTE + """
            select currency, coalesce(sum(gross_amount),0) gross, coalesce(sum(discount_amount),0) discounts,
                   coalesce(sum(refund_amount),0) refunds, coalesce(sum(net_amount),0) net,
                   coalesce(sum(fee_amount),0) fees, coalesce(sum(net_amount-fee_amount),0) after_fees,
                   coalesce(sum(order_count),0) orders, coalesce(sum(payment_count),0) payments
            from included_sales group by currency order by currency
            """, params, (rs, row) -> {
                BigDecimal net = rs.getBigDecimal("net");
                long orders = rs.getLong("orders");
                return new ReportDtos.CurrencyMetrics(rs.getString("currency"), rs.getBigDecimal("gross"),
                    rs.getBigDecimal("discounts"), rs.getBigDecimal("refunds"), net, rs.getBigDecimal("fees"),
                    rs.getBigDecimal("after_fees"), orders, rs.getLong("payments"),
                    orders == 0 ? BigDecimal.ZERO : net.divide(BigDecimal.valueOf(orders), 4, java.math.RoundingMode.HALF_UP));
            });
        List<ReportDtos.DailyTrend> trends = jdbc.query(ReportSourcePolicy.INCLUDED_SALES_CTE + """
            select (occurred_at at time zone :timezone)::date report_day, currency,
                   sum(net_amount) net, sum(fee_amount) fees, sum(order_count) orders
            from included_sales group by 1, currency order by 1, currency
            """, params, (rs, row) -> new ReportDtos.DailyTrend(rs.getDate("report_day").toLocalDate(),
            rs.getString("currency"), rs.getBigDecimal("net"), rs.getBigDecimal("fees"), rs.getLong("orders")));

        List<ReportDtos.ProductRank> topProducts = jdbc.query("""
            select p.id, p.sku, p.name, coalesce(sum(oi.quantity - oi.refunded_quantity),0) quantity,
                   coalesce(sum(oi.line_total),0) revenue
            from order_items oi join orders o on o.tenant_id=oi.tenant_id and o.id=oi.order_id
            join products p on p.tenant_id=oi.tenant_id and p.id=oi.product_id
            where (:tenantId is null or o.tenant_id=cast(:tenantId as uuid)) and o.order_date>=:from and o.order_date<:to
              and o.status in ('CONFIRMED','COMPLETED','PARTIALLY_REFUNDED','REFUNDED')
            group by p.id,p.sku,p.name order by quantity desc, revenue desc limit 10
            """, params, (rs, row) -> new ReportDtos.ProductRank(rs.getObject("id", UUID.class), rs.getString("sku"),
            rs.getString("name"), rs.getLong("quantity"), rs.getBigDecimal("revenue")));

        long lowStock = scalar("select count(*) from products where (:tenantId is null or tenant_id=cast(:tenantId as uuid)) and enabled=true and current_stock<=low_stock_threshold", params);
        long unallocated = scalar("select count(*) from orders where (:tenantId is null or tenant_id=cast(:tenantId as uuid)) and allocation_status<>'FULLY_ALLOCATED' and status<>'CANCELLED'", params);
        long importErrors = scalar("select coalesce(sum(error_rows),0) from import_batches where (:tenantId is null or tenant_id=cast(:tenantId as uuid)) and status<>'REVERSED'", params);
        return new ReportDtos.Dashboard(start, end, settings.zone().getId(), settings.currency(), metrics, trends,
            topProducts, breakdown("source", params), breakdown("sales_channel", params),
            breakdown("payment_method", params), breakdown("event_name", params), lowStock, unallocated, importErrors);
    }

    private List<ReportDtos.Breakdown> breakdown(String field, MapSqlParameterSource params) {
        return jdbc.query(ReportSourcePolicy.INCLUDED_SALES_CTE + " select " + field + " label,currency,sum(net_amount) net,sum(order_count) orders from included_sales group by " + field + ",currency order by net desc",
            params, (rs, row) -> new ReportDtos.Breakdown(rs.getString("label"), rs.getString("currency"),
                rs.getBigDecimal("net"), rs.getLong("orders")));
    }

    private long scalar(String sql, MapSqlParameterSource params) {
        Long value = jdbc.queryForObject(sql, params, Long.class);
        return value == null ? 0 : value;
    }

    private TenantSettings settings(UUID tenantId, boolean admin) {
        if (tenantId == null) {
            if (!admin) throw new BusinessException("TENANT_REQUIRED", "Tenant is required", HttpStatus.FORBIDDEN);
            return new TenantSettings(ZoneId.of("UTC"), "MULTI");
        }
        List<TenantSettings> result = jdbc.query("select timezone,default_currency from tenants where id=:id",
            Map.of("id", tenantId), (rs, row) -> new TenantSettings(ZoneId.of(rs.getString("timezone")), rs.getString("default_currency")));
        if (result.isEmpty()) throw new com.inventoryart.exception.NotFoundException("Tenant");
        return result.getFirst();
    }

    private record TenantSettings(ZoneId zone, String currency) {}
}
