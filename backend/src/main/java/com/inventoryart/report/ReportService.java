package com.inventoryart.report;

import com.inventoryart.exception.BusinessException;
import com.inventoryart.exception.NotFoundException;
import com.inventoryart.security.CurrentUserService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
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
    return dashboard(currentUser.tenantId(), start, end, granularity);
  }

  private ReportDtos.Dashboard dashboard(
      UUID tenantId,
      LocalDate requestedStart,
      LocalDate requestedEnd,
      ReportGranularity requestedGranularity) {
    ReportGranularity granularity =
        requestedGranularity == null ? ReportGranularity.DAY : requestedGranularity;
    TenantSettings settings = settings(tenantId);
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
            .addValue("startDate", Date.valueOf(start))
            .addValue("endDate", Date.valueOf(end))
            .addValue("timezone", settings.zone().getId())
            .addValue("granularity", granularity.name())
            .addValue("defaultCurrency", settings.currency());

    List<ReportDtos.CurrencyMetrics> metrics =
        jdbc.query(
            """
              with financial as (
                select currency,total_amount sales,0::numeric expenses,1 transactions
                  from orders
                 where (:tenantId is null or tenant_id=cast(:tenantId as uuid))
                   and order_date>=:from and order_date<:to
                union all
                select x.currency,0::numeric sales,x.amount expenses,0 transactions
                  from sales_event_expenses x
                  join sales_events e on e.tenant_id=x.tenant_id and e.id=x.event_id
                 where (:tenantId is null or x.tenant_id=cast(:tenantId as uuid))
                   and x.status='ACTIVE'
                   and e.end_date>=:startDate and e.end_date<=:endDate
                union all
                select :defaultCurrency,0::numeric,0::numeric,0
              )
              select currency,coalesce(sum(sales),0) total_sales,
                     coalesce(sum(expenses),0) total_expenses,
                     coalesce(sum(sales)-sum(expenses),0) balance,
                     sum(transactions) transaction_count,
                     case when sum(transactions)>0 then sum(sales)/sum(transactions) else 0 end average_value
                from financial
               group by currency order by currency
              """,
            params,
            (rs, row) ->
                new ReportDtos.CurrencyMetrics(
                    rs.getString("currency"),
                    money(rs.getBigDecimal("total_sales")),
                    money(rs.getBigDecimal("total_expenses")),
                    money(rs.getBigDecimal("balance")),
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
              with event_financial as (
                select e.id event_id,e.name label,o.currency,o.total_amount sales,0::numeric expenses,
                       1 transactions,0 expense_count
                  from orders o
                  join sales_events e on e.tenant_id=o.tenant_id and e.id=o.event_id
                 where (:tenantId is null or o.tenant_id=cast(:tenantId as uuid))
                   and o.order_date>=:from and o.order_date<:to
                union all
                select e.id,e.name label,x.currency,0::numeric,x.amount,0,1
                  from sales_event_expenses x
                  join sales_events e on e.tenant_id=x.tenant_id and e.id=x.event_id
                 where (:tenantId is null or x.tenant_id=cast(:tenantId as uuid))
                   and x.status='ACTIVE'
                   and e.end_date>=:startDate and e.end_date<=:endDate
              )
              select event_id,label,currency,sum(sales) total_sales,sum(expenses) total_expenses,
                     sum(sales)-sum(expenses) balance,sum(transactions) transactions,
                     sum(expense_count) expense_count
                from event_financial
               group by event_id,label,currency
               order by total_sales desc,total_expenses desc,label
              """,
            params,
            (rs, row) ->
                new ReportDtos.Breakdown(
                    rs.getObject("event_id", UUID.class),
                    rs.getString("label"),
                    rs.getString("currency"),
                    money(rs.getBigDecimal("total_sales")),
                    money(rs.getBigDecimal("total_expenses")),
                    money(rs.getBigDecimal("balance")),
                    rs.getLong("transactions"),
                    rs.getLong("expense_count")));

    List<ReportDtos.ExpenseCategoryBreakdown> byCategory =
        jdbc.query(
            """
              select c.id category_id,c.name label,x.currency,sum(x.amount) total_expenses,
                     count(*) expense_count
                from sales_event_expenses x
                join expense_categories c on c.tenant_id=x.tenant_id and c.id=x.category_id
                join sales_events e on e.tenant_id=x.tenant_id and e.id=x.event_id
               where (:tenantId is null or x.tenant_id=cast(:tenantId as uuid))
                 and x.status='ACTIVE'
                 and e.end_date>=:startDate and e.end_date<=:endDate
               group by c.id,c.name,x.currency
               order by total_expenses desc,c.name
              """,
            params,
            (rs, row) ->
                new ReportDtos.ExpenseCategoryBreakdown(
                    rs.getObject("category_id", UUID.class),
                    rs.getString("label"),
                    rs.getString("currency"),
                    money(rs.getBigDecimal("total_expenses")),
                    rs.getLong("expense_count")));

    return new ReportDtos.Dashboard(
        start,
        end,
        settings.zone().getId(),
        settings.currency(),
        granularity.name(),
        metrics,
        trends,
        byEvent,
        byCategory);
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

  private TenantSettings settings(UUID tenantId) {
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
