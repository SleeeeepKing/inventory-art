package com.inventoryart.common;

import com.inventoryart.exception.BusinessException;
import com.inventoryart.inventory.MovementType;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminDataQueryService {
  private final NamedParameterJdbcTemplate jdbc;

  public AdminDataQueryService(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional(readOnly = true)
  public PageResponse<AdminOrderRow> orders(
      UUID tenantId,
      UUID userId,
      Instant requestedFrom,
      Instant requestedTo,
      UUID eventId,
      String q,
      int page,
      int requestedSize) {
    Range range = range(tenantId, requestedFrom, requestedTo);
    int size = Math.min(Math.max(requestedSize, 1), 50);
    int safePage = Math.max(page, 0);
    MapSqlParameterSource params =
        baseParams(tenantId, userId, range, safePage, size)
            .addValue("eventId", eventId == null ? null : eventId.toString(), Types.VARCHAR)
            .addValue("q", q == null || q.isBlank() ? "" : q.trim());
    String where =
        """
            where (:tenantId is null or o.tenant_id=cast(:tenantId as uuid))
              and (:userId is null or o.created_by=cast(:userId as uuid))
              and (:eventId is null or o.event_id=cast(:eventId as uuid))
              and o.order_date>=:from and o.order_date<:to
              and (:q='' or lower(o.order_number) like lower(concat('%',:q,'%'))
                   or lower(e.name) like lower(concat('%',:q,'%')))
            """;
    String fromSql =
        """
            from orders o
            join tenants t on t.id=o.tenant_id
            join sales_events e on e.tenant_id=o.tenant_id and e.id=o.event_id
            left join users u on u.id=o.created_by
            """;
    List<AdminOrderRow> rows =
        jdbc.query(
            """
            select o.id,o.tenant_id,t.name tenant_name,o.order_number,
                   o.event_id,e.name event_name,o.currency,o.total_amount,o.order_date,
                   o.created_by,u.display_name created_by_name
            """
                + fromSql
                + where
                + " order by o.order_date desc limit :limit offset :offset",
            params,
            (rs, rowNum) ->
                new AdminOrderRow(
                    rs.getObject("id", UUID.class),
                    rs.getObject("tenant_id", UUID.class),
                    rs.getString("tenant_name"),
                    rs.getString("order_number"),
                    rs.getObject("event_id", UUID.class),
                    rs.getString("event_name"),
                    rs.getString("currency"),
                    rs.getBigDecimal("total_amount"),
                    rs.getTimestamp("order_date").toInstant(),
                    rs.getObject("created_by", UUID.class),
                    rs.getString("created_by_name")));
    long total = count("select count(*) " + fromSql + where, params);
    return page(rows, safePage, size, total, "orderDate: DESC");
  }

  @Transactional(readOnly = true)
  public PageResponse<AdminInventoryRow> inventory(
      UUID tenantId,
      UUID userId,
      Instant requestedFrom,
      Instant requestedTo,
      MovementType type,
      UUID productId,
      UUID eventId,
      int page,
      int requestedSize) {
    Range range = range(tenantId, requestedFrom, requestedTo);
    int size = Math.min(Math.max(requestedSize, 1), 50);
    int safePage = Math.max(page, 0);
    MapSqlParameterSource params =
        baseParams(tenantId, userId, range, safePage, size)
            .addValue("type", type == null ? null : type.name(), Types.VARCHAR)
            .addValue("productId", productId == null ? null : productId.toString(), Types.VARCHAR)
            .addValue("eventId", eventId == null ? null : eventId.toString(), Types.VARCHAR);
    String fromSql =
        """
            from inventory_movements m
            join tenants t on t.id=m.tenant_id
            join products p on p.tenant_id=m.tenant_id and p.id=m.product_id
            left join users u on u.id=m.operator_id
            left join inventory_sale_batches b on b.tenant_id=m.tenant_id and b.id=m.sale_batch_id
            left join sales_events e on e.tenant_id=b.tenant_id and e.id=b.event_id
            """;
    String where =
        """
            where (:tenantId is null or m.tenant_id=cast(:tenantId as uuid))
              and (:userId is null or m.operator_id=cast(:userId as uuid))
              and (:type is null or m.movement_type=:type)
              and (:productId is null or m.product_id=cast(:productId as uuid))
              and (:eventId is null or b.event_id=cast(:eventId as uuid))
              and m.created_at>=:from and m.created_at<:to
            """;
    List<AdminInventoryRow> rows =
        jdbc.query(
            """
            select m.id,m.tenant_id,t.name tenant_name,m.product_id,p.sku,p.name product_name,
                   m.movement_type,m.quantity,m.stock_before,m.stock_after,
                   b.event_id,e.name event_name,b.attributed_date,
                   m.remark,m.operator_id,u.display_name operator_name,m.created_at
            """
                + fromSql
                + where
                + " order by m.created_at desc limit :limit offset :offset",
            params,
            (rs, rowNum) ->
                new AdminInventoryRow(
                    rs.getObject("id", UUID.class),
                    rs.getObject("tenant_id", UUID.class),
                    rs.getString("tenant_name"),
                    rs.getObject("product_id", UUID.class),
                    rs.getString("sku"),
                    rs.getString("product_name"),
                    rs.getString("movement_type"),
                    rs.getInt("quantity"),
                    rs.getInt("stock_before"),
                    rs.getInt("stock_after"),
                    rs.getObject("event_id", UUID.class),
                    rs.getString("event_name"),
                    rs.getObject("attributed_date", LocalDate.class),
                    rs.getString("remark"),
                    rs.getObject("operator_id", UUID.class),
                    rs.getString("operator_name"),
                    rs.getTimestamp("created_at").toInstant()));
    long total = count("select count(*) " + fromSql + where, params);
    return page(rows, safePage, size, total, "createdAt: DESC");
  }

  private MapSqlParameterSource baseParams(
      UUID tenantId, UUID userId, Range range, int page, int size) {
    return new MapSqlParameterSource()
        .addValue("tenantId", tenantId == null ? null : tenantId.toString(), Types.VARCHAR)
        .addValue("userId", userId == null ? null : userId.toString(), Types.VARCHAR)
        .addValue("from", Timestamp.from(range.from()))
        .addValue("to", Timestamp.from(range.to()))
        .addValue("limit", size)
        .addValue("offset", page * size);
  }

  private Range range(UUID tenantId, Instant requestedFrom, Instant requestedTo) {
    Instant to = requestedTo == null ? Instant.now().plusSeconds(1) : requestedTo;
    Instant from = requestedFrom == null ? to.minus(Duration.ofDays(30)) : requestedFrom;
    long maxDays = tenantId == null ? 90 : 730;
    if (!to.isAfter(from) || Duration.between(from, to).compareTo(Duration.ofDays(maxDays)) > 0) {
      throw new BusinessException(
          "INVALID_ADMIN_QUERY_RANGE",
          "Administrator list range must be ordered and no longer than " + maxDays + " days",
          HttpStatus.BAD_REQUEST);
    }
    return new Range(from, to);
  }

  private long count(String sql, MapSqlParameterSource params) {
    Long value = jdbc.queryForObject(sql, params, Long.class);
    return value == null ? 0 : value;
  }

  private static <T> PageResponse<T> page(
      List<T> rows, int page, int size, long total, String sort) {
    int pages = total == 0 ? 0 : (int) Math.ceil((double) total / size);
    return new PageResponse<>(rows, page, size, total, pages, sort);
  }

  private record Range(Instant from, Instant to) {}

  public record AdminOrderRow(
      UUID id,
      UUID tenantId,
      String tenantName,
      String orderNumber,
      UUID eventId,
      String eventName,
      String currency,
      BigDecimal totalAmount,
      Instant orderDate,
      UUID createdBy,
      String createdByName) {}

  public record AdminInventoryRow(
      UUID id,
      UUID tenantId,
      String tenantName,
      UUID productId,
      String productSku,
      String productName,
      String type,
      int quantity,
      int stockBefore,
      int stockAfter,
      UUID eventId,
      String eventName,
      LocalDate attributedDate,
      String remark,
      UUID operatorId,
      String operatorName,
      Instant createdAt) {}
}
