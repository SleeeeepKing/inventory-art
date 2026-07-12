package com.inventoryart.report;

import com.inventoryart.exception.BusinessException;
import com.inventoryart.exception.NotFoundException;
import com.inventoryart.security.CurrentUser;
import com.inventoryart.security.CurrentUserService;
import com.inventoryart.user.UserRole;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
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
public class ReportService {
  private final NamedParameterJdbcTemplate jdbc;
  private final CurrentUserService currentUser;

  public ReportService(NamedParameterJdbcTemplate jdbc, CurrentUserService currentUser) {
    this.jdbc = jdbc;
    this.currentUser = currentUser;
  }

  @Transactional(readOnly = true)
  public ReportDtos.Dashboard tenantDashboard(
      LocalDate start, LocalDate end, ReportGranularity granularity) {
    return dashboard(currentUser.tenantId(), start, end, granularity, false);
  }

  @Transactional(readOnly = true)
  public ReportDtos.Dashboard adminDashboard(
      UUID tenantId, LocalDate start, LocalDate end, ReportGranularity granularity) {
    CurrentUser user = currentUser.get();
    if (user.role() != UserRole.ADMIN) {
      throw new BusinessException(
          "FORBIDDEN", "Administrator access required", HttpStatus.FORBIDDEN);
    }
    if (granularity == ReportGranularity.HOUR && tenantId == null) {
      throw new BusinessException(
          "TENANT_REQUIRED_FOR_HOURLY_REPORT",
          "Select one tenant before using hourly report granularity");
    }
    return dashboard(tenantId, start, end, granularity, true);
  }

  private ReportDtos.Dashboard dashboard(
      UUID tenantId,
      LocalDate requestedStart,
      LocalDate requestedEnd,
      ReportGranularity requestedGranularity,
      boolean admin) {
    ReportGranularity granularity =
        requestedGranularity == null ? ReportGranularity.DAY : requestedGranularity;
    TenantSettings settings = settings(tenantId, admin);
    LocalDate end = requestedEnd == null ? LocalDate.now(settings.zone()) : requestedEnd;
    LocalDate start = requestedStart == null ? end.minusDays(29) : requestedStart;
    validateRange(start, end, granularity);
    Instant from = start.atStartOfDay(settings.zone()).toInstant();
    Instant to = end.plusDays(1).atStartOfDay(settings.zone()).toInstant();
    MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("tenantId", tenantId == null ? null : tenantId.toString(), Types.VARCHAR)
            .addValue("from", Timestamp.from(from))
            .addValue("to", Timestamp.from(to))
            .addValue("timezone", settings.zone().getId())
            .addValue("granularity", granularity.name());

    List<ReportDtos.CurrencyMetrics> metrics =
        jdbc.query(
            """
              select currency, coalesce(sum(total_amount),0) total_sales,
                     count(*) transaction_count, coalesce(avg(total_amount),0) average_value
                from orders
               where (:tenantId is null or tenant_id=cast(:tenantId as uuid))
                 and order_date>=:from and order_date<:to
               group by currency order by currency
              """,
            params,
            (rs, row) ->
                new ReportDtos.CurrencyMetrics(
                    rs.getString("currency"),
                    money(rs.getBigDecimal("total_sales")),
                    rs.getLong("transaction_count"),
                    money(rs.getBigDecimal("average_value"))));

    List<ReportDtos.TrendPoint> trends =
        jdbc.query(
            """
              select case when :granularity='HOUR'
                          then to_char(date_trunc('hour', order_date at time zone :timezone), 'YYYY-MM-DD"T"HH24:MI:SS')
                          else to_char((order_date at time zone :timezone)::date, 'YYYY-MM-DD')
                     end report_bucket,
                     currency, sum(total_amount) total_sales, count(*) transactions
                from orders
               where (:tenantId is null or tenant_id=cast(:tenantId as uuid))
                 and order_date>=:from and order_date<:to
               group by 1,currency order by 1,currency
              """,
            params,
            (rs, row) ->
                new ReportDtos.TrendPoint(
                    rs.getString("report_bucket"),
                    rs.getString("currency"),
                    money(rs.getBigDecimal("total_sales")),
                    rs.getLong("transactions")));

    List<ReportDtos.Breakdown> byEvent =
        jdbc.query(
            """
              select e.name label,o.currency,sum(o.total_amount) total_sales,count(*) transactions
                from orders o
                join sales_events e on e.tenant_id=o.tenant_id and e.id=o.event_id
               where (:tenantId is null or o.tenant_id=cast(:tenantId as uuid))
                 and o.order_date>=:from and o.order_date<:to
               group by e.name,o.currency order by total_sales desc,e.name
              """,
            params,
            (rs, row) ->
                new ReportDtos.Breakdown(
                    rs.getString("label"),
                    rs.getString("currency"),
                    money(rs.getBigDecimal("total_sales")),
                    rs.getLong("transactions")));

    return new ReportDtos.Dashboard(
        start,
        end,
        settings.zone().getId(),
        settings.currency(),
        granularity.name(),
        metrics,
        trends,
        byEvent);
  }

  private void validateRange(LocalDate start, LocalDate end, ReportGranularity granularity) {
    if (end.isBefore(start) || start.plusYears(2).isBefore(end)) {
      throw new BusinessException(
          "INVALID_REPORT_RANGE", "Report date range must be ordered and no longer than two years");
    }
    if (granularity == ReportGranularity.HOUR && start.plusDays(30).isBefore(end)) {
      throw new BusinessException(
          "HOURLY_REPORT_RANGE_TOO_LARGE", "Hourly reports are limited to 31 days");
    }
  }

  private TenantSettings settings(UUID tenantId, boolean admin) {
    if (tenantId == null) {
      if (!admin) {
        throw new BusinessException("TENANT_REQUIRED", "Tenant is required", HttpStatus.FORBIDDEN);
      }
      return new TenantSettings(ZoneId.of("UTC"), "MULTI");
    }
    List<TenantSettings> result =
        jdbc.query(
            "select timezone,default_currency from tenants where id=:id",
            Map.of("id", tenantId),
            (rs, row) ->
                new TenantSettings(
                    ZoneId.of(rs.getString("timezone")), rs.getString("default_currency")));
    if (result.isEmpty()) {
      throw new NotFoundException("Tenant");
    }
    return result.getFirst();
  }

  private static BigDecimal money(BigDecimal value) {
    return value == null ? BigDecimal.ZERO.setScale(4) : value.setScale(4, RoundingMode.HALF_UP);
  }

  private record TenantSettings(ZoneId zone, String currency) {}
}
