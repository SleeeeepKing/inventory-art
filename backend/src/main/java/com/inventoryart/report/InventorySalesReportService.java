package com.inventoryart.report;

import com.inventoryart.exception.BusinessException;
import com.inventoryart.exception.NotFoundException;
import com.inventoryart.security.CurrentUserService;
import java.sql.Date;
import java.sql.Types;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
        join sales_events e on e.tenant_id=b.tenant_id and e.id=b.event_id
        where (:tenantId is null or m.tenant_id=cast(:tenantId as uuid))
          and b.attributed_date>=:start and b.attributed_date<=:end
          and m.movement_type='SALE' and m.quantity<0
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
    return report(currentUser.tenantId(), start, end);
  }

  private ReportDtos.InventorySalesReport report(
      UUID tenantId, LocalDate requestedStart, LocalDate requestedEnd) {
    Settings settings = settings(tenantId);
    LocalDate end = requestedEnd == null ? LocalDate.now(settings.zone()) : requestedEnd;
    LocalDate start = requestedStart == null ? end.minusDays(29) : requestedStart;
    int maxDays = 731;
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
    ReportDtos.InventorySalesMetrics summary =
        jdbc.queryForObject(
            "select coalesce(sum(abs(m.quantity)),0) units,count(distinct b.id) batches " + BASE,
            params,
            (rs, row) ->
                new ReportDtos.InventorySalesMetrics(rs.getLong("units"), rs.getLong("batches")));
    return new ReportDtos.InventorySalesReport(
        start,
        end,
        settings.zone().getId(),
        summary == null ? new ReportDtos.InventorySalesMetrics(0, 0) : summary,
        groups("p.id", "p.sku", "p.name", "p.id,p.sku,p.name", params),
        groups("null::uuid", "null::varchar", "e.name", "e.name", params));
  }

  private List<ReportDtos.InventorySalesGroup> groups(
      String productId, String sku, String label, String groupBy, MapSqlParameterSource params) {
    return jdbc.query(
        """
            select %s product_id,%s sku,%s label,
                   sum(abs(m.quantity)) units,count(distinct b.id) batches
            """
                .formatted(productId, sku, label)
            + BASE
            + " group by "
            + groupBy
            + " order by units desc,label limit 100",
        params,
        (rs, row) ->
            new ReportDtos.InventorySalesGroup(
                rs.getObject("product_id", UUID.class),
                rs.getString("sku"),
                rs.getString("label"),
                rs.getLong("units"),
                rs.getLong("batches")));
  }

  private Settings settings(UUID tenantId) {
    List<Settings> result =
        jdbc.query(
            "select timezone from tenants where id=:id",
            Map.of("id", tenantId),
            (rs, row) -> new Settings(safeZone(rs.getString("timezone"))));
    if (result.isEmpty()) {
      throw new NotFoundException("Tenant");
    }
    return result.getFirst();
  }

  private static ZoneId safeZone(String value) {
    try {
      return ZoneId.of(value);
    } catch (RuntimeException ignored) {
      return ZoneId.of("UTC");
    }
  }

  private record Settings(ZoneId zone) {}
}
