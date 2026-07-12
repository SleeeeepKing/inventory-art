package com.inventoryart.report;

import com.inventoryart.exception.BusinessException;
import com.inventoryart.exception.NotFoundException;
import com.inventoryart.security.CurrentUser;
import com.inventoryart.security.CurrentUserService;
import com.inventoryart.user.UserRole;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Types;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventorySalesReportService {
  private static final String BASE =
      """
        from inventory_movements m
        join inventory_sale_batches b on b.tenant_id=m.tenant_id and b.id=m.sale_batch_id
        join products p on p.tenant_id=m.tenant_id and p.id=m.product_id
        where (:tenantId is null or m.tenant_id=cast(:tenantId as uuid))
          and b.attributed_date>=:start and b.attributed_date<=:end
          and m.movement_type='SALE' and m.quantity<0 and m.unit_price is not null
        """;

  private final NamedParameterJdbcTemplate jdbc;
  private final CurrentUserService currentUser;

  public InventorySalesReportService(
      NamedParameterJdbcTemplate jdbc, CurrentUserService currentUser) {
    this.jdbc = jdbc;
    this.currentUser = currentUser;
  }

  @Transactional(readOnly = true)
  public ReportDtos.InventorySalesReport tenantReport(LocalDate start, LocalDate end) {
    return report(currentUser.tenantId(), start, end, false);
  }

  @Transactional(readOnly = true)
  public ReportDtos.InventorySalesReport adminReport(
      UUID tenantId, LocalDate start, LocalDate end) {
    CurrentUser user = currentUser.get();
    if (user.role() != UserRole.ADMIN) {
      throw new BusinessException(
          "FORBIDDEN", "Administrator access required", HttpStatus.FORBIDDEN);
    }
    return report(tenantId, start, end, true);
  }

  private ReportDtos.InventorySalesReport report(
      UUID tenantId, LocalDate requestedStart, LocalDate requestedEnd, boolean admin) {
    Settings settings = settings(tenantId, admin);
    LocalDate end = requestedEnd == null ? LocalDate.now(settings.zone()) : requestedEnd;
    LocalDate start = requestedStart == null ? end.minusDays(29) : requestedStart;
    int maxDays = admin && tenantId == null ? 90 : 731;
    if (end.isBefore(start) || start.plusDays(maxDays - 1L).isBefore(end)) {
      throw new BusinessException(
          "INVALID_REPORT_RANGE",
          "Inventory sales report range must be ordered and no longer than " + maxDays + " days");
    }
    MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("tenantId", tenantId == null ? null : tenantId.toString(), Types.VARCHAR)
            .addValue("start", Date.valueOf(start))
            .addValue("end", Date.valueOf(end));
    List<ReportDtos.InventorySalesMetrics> currencies =
        jdbc.query(
            """
            select b.currency, sum(abs(m.quantity)) units, count(distinct b.id) batches,
                   sum(abs(m.quantity)*m.unit_price) attributed_amount,
                   sum(abs(m.quantity)*m.unit_price)/nullif(sum(abs(m.quantity)),0) weighted_average,
                   min(m.unit_price) minimum_price,max(m.unit_price) maximum_price
            """
                + BASE
                + " group by b.currency order by b.currency",
            params,
            (rs, row) ->
                new ReportDtos.InventorySalesMetrics(
                    rs.getString("currency"),
                    rs.getLong("units"),
                    rs.getLong("batches"),
                    money(rs.getBigDecimal("attributed_amount")),
                    money(rs.getBigDecimal("weighted_average")),
                    money(rs.getBigDecimal("minimum_price")),
                    money(rs.getBigDecimal("maximum_price"))));
    return new ReportDtos.InventorySalesReport(
        start,
        end,
        settings.zone().getId(),
        currencies,
        groups("p.id", "p.sku", "p.name", "p.id,p.sku,p.name,b.currency", params),
        groups(
            "null::uuid", "null::varchar", "b.sales_channel", "b.sales_channel,b.currency", params),
        groups(
            "null::uuid",
            "null::varchar",
            "coalesce(b.event_name,'')",
            "b.event_name,b.currency",
            params));
  }

  private List<ReportDtos.InventorySalesGroup> groups(
      String productId, String sku, String label, String groupBy, MapSqlParameterSource params) {
    return jdbc.query(
        """
            select %s product_id,%s sku,%s label,b.currency,
                   sum(abs(m.quantity)) units,count(distinct b.id) batches,
                   sum(abs(m.quantity)*m.unit_price) attributed_amount,
                   sum(abs(m.quantity)*m.unit_price)/nullif(sum(abs(m.quantity)),0) weighted_average,
                   min(m.unit_price) minimum_price,max(m.unit_price) maximum_price
            """
                .formatted(productId, sku, label)
            + BASE
            + " group by "
            + groupBy
            + " order by attributed_amount desc,label limit 100",
        params,
        (rs, row) ->
            new ReportDtos.InventorySalesGroup(
                rs.getObject("product_id", UUID.class),
                rs.getString("sku"),
                rs.getString("label"),
                rs.getString("currency"),
                rs.getLong("units"),
                rs.getLong("batches"),
                money(rs.getBigDecimal("attributed_amount")),
                money(rs.getBigDecimal("weighted_average")),
                money(rs.getBigDecimal("minimum_price")),
                money(rs.getBigDecimal("maximum_price"))));
  }

  private Settings settings(UUID tenantId, boolean admin) {
    if (tenantId == null) {
      if (!admin)
        throw new BusinessException("TENANT_REQUIRED", "Tenant is required", HttpStatus.FORBIDDEN);
      return new Settings(ZoneId.of("UTC"));
    }
    List<Settings> result =
        jdbc.query(
            "select timezone from tenants where id=:id",
            Map.of("id", tenantId),
            (rs, row) -> new Settings(safeZone(rs.getString("timezone"))));
    if (result.isEmpty()) throw new NotFoundException("Tenant");
    return result.getFirst();
  }

  private static ZoneId safeZone(String value) {
    try {
      return ZoneId.of(value);
    } catch (RuntimeException ignored) {
      return ZoneId.of("UTC");
    }
  }

  private static BigDecimal money(BigDecimal value) {
    return value == null ? BigDecimal.ZERO.setScale(4) : value.setScale(4, RoundingMode.HALF_UP);
  }

  private record Settings(ZoneId zone) {}
}
