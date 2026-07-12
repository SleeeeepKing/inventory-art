package com.inventoryart.sumup;

import com.inventoryart.audit.AuditService;
import com.inventoryart.exception.BusinessException;
import com.inventoryart.exception.NotFoundException;
import com.inventoryart.security.CurrentUserService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExternalTransactionLinkService {
  private static final BigDecimal LINK_TOLERANCE = new BigDecimal("0.01");
  private final JdbcTemplate jdbc;
  private final CurrentUserService currentUser;
  private final AuditService audit;

  public ExternalTransactionLinkService(
      JdbcTemplate jdbc, CurrentUserService currentUser, AuditService audit) {
    this.jdbc = jdbc;
    this.currentUser = currentUser;
    this.audit = audit;
  }

  @Transactional(readOnly = true)
  public List<ExternalTransactionDtos.OrderMatch> matches(UUID transactionId) {
    UUID tenantId = currentUser.tenantId();
    Transaction transaction = required(transactionId, tenantId, false);
    return jdbc.query(
        """
            select o.id,o.order_number,o.total_amount,o.currency,o.order_date,
                   case when abs(o.total_amount-?)<=0.01
                          and abs(extract(epoch from (o.order_date-?)))<=86400
                        then 'EXACT' else 'POSSIBLE' end match_status
            from orders o
            where o.tenant_id=? and o.status<>'CANCELLED' and o.currency=?
              and abs(o.total_amount-?)<=0.50
              and abs(extract(epoch from (o.order_date-?)))<=259200
              and (o.external_transaction_id is null or o.external_transaction_id=?)
              and not exists (select 1 from external_transactions other
                              where other.tenant_id=o.tenant_id and other.linked_order_id=o.id
                                and other.id<>? and other.active=true)
            order by case when abs(o.total_amount-?)<=0.01 then 0 else 1 end,
                     abs(extract(epoch from (o.order_date-?)))
            limit 20
            """,
        (rs, row) ->
            new ExternalTransactionDtos.OrderMatch(
                rs.getObject("id", UUID.class),
                rs.getString("order_number"),
                rs.getBigDecimal("total_amount"),
                rs.getString("currency"),
                rs.getTimestamp("order_date").toInstant(),
                rs.getString("match_status")),
        transaction.amount(),
        java.sql.Timestamp.from(transaction.occurredAt()),
        tenantId,
        transaction.currency(),
        transaction.amount(),
        java.sql.Timestamp.from(transaction.occurredAt()),
        transaction.providerTransactionId(),
        transactionId,
        transaction.amount(),
        java.sql.Timestamp.from(transaction.occurredAt()));
  }

  @Transactional
  public ExternalTransactionDtos.Response link(UUID transactionId, UUID orderId) {
    UUID tenantId = currentUser.tenantId();
    Transaction transaction = required(transactionId, tenantId, true);
    Order order =
        jdbc.query(
            """
            select id,total_amount,currency from orders where tenant_id=? and id=? for update
            """,
            rs ->
                rs.next()
                    ? new Order(
                        rs.getObject("id", UUID.class),
                        rs.getBigDecimal("total_amount"),
                        rs.getString("currency"))
                    : null,
            tenantId,
            orderId);
    if (order == null) throw new NotFoundException("Order");
    if (transaction.linkedOrderId() != null) {
      if (transaction.linkedOrderId().equals(orderId)) return response(transactionId, tenantId);
      throw new BusinessException(
          "EXTERNAL_TRANSACTION_ALREADY_LINKED",
          "External transaction is already linked to another order",
          HttpStatus.CONFLICT);
    }
    Integer conflicts =
        jdbc.queryForObject(
            """
            select count(*) from external_transactions where tenant_id=? and linked_order_id=? and id<>? and active=true
            """,
            Integer.class,
            tenantId,
            orderId,
            transactionId);
    if (conflicts != null && conflicts > 0) {
      throw new BusinessException(
          "ORDER_ALREADY_LINKED", "Order is linked to another transaction", HttpStatus.CONFLICT);
    }
    if (!transaction.currency().equalsIgnoreCase(order.currency())) {
      throw new BusinessException(
          "LINK_CURRENCY_MISMATCH", "Transaction and order currencies differ", HttpStatus.CONFLICT);
    }
    if (transaction.amount().subtract(order.amount()).abs().compareTo(LINK_TOLERANCE) > 0) {
      throw new BusinessException(
          "LINK_AMOUNT_MISMATCH", "Transaction and order amounts differ", HttpStatus.CONFLICT);
    }
    String providerReference =
        transaction.providerTransactionId() == null
            ? transactionId.toString()
            : transaction.providerTransactionId();
    jdbc.update(
        "update external_transactions set linked_order_id=?,updated_at=now() where tenant_id=? and id=?",
        orderId,
        tenantId,
        transactionId);
    jdbc.update(
        """
            update orders set external_provider=?,external_transaction_id=?,payment_status='PAID',
                              updated_at=now(),version=version+1
            where tenant_id=? and id=?
            """,
        transaction.provider(),
        providerReference,
        tenantId,
        orderId);
    audit.record(
        tenantId,
        "EXTERNAL_TRANSACTION_LINK_ORDER",
        "EXTERNAL_TRANSACTION",
        transactionId,
        "SUCCESS",
        Map.of("orderId", orderId));
    return response(transactionId, tenantId);
  }

  private Transaction required(UUID id, UUID tenantId, boolean lock) {
    String suffix = lock ? " for update" : "";
    Transaction result =
        jdbc.query(
            """
            select id,provider,provider_transaction_id,occurred_at,amount,currency,linked_order_id
            from external_transactions where tenant_id=? and id=? and active=true
            """
                + suffix,
            rs ->
                rs.next()
                    ? new Transaction(
                        rs.getObject("id", UUID.class),
                        rs.getString("provider"),
                        rs.getString("provider_transaction_id"),
                        rs.getTimestamp("occurred_at").toInstant(),
                        rs.getBigDecimal("amount"),
                        rs.getString("currency"),
                        rs.getObject("linked_order_id", UUID.class))
                    : null,
            tenantId,
            id);
    if (result == null) throw new NotFoundException("External transaction");
    return result;
  }

  private ExternalTransactionDtos.Response response(UUID id, UUID tenantId) {
    return jdbc.query(
        """
            select id,tenant_id,provider,provider_transaction_id,provider_transaction_code,transaction_type,
                   transaction_status,occurred_at,amount,currency,fee_amount,net_amount,refund_amount,
                   payment_method,card_type,masked_card_info,payout_reference,payout_date,description,
                   linked_order_id,import_batch_id
            from external_transactions where tenant_id=? and id=?
            """,
        rs -> {
          if (!rs.next()) throw new NotFoundException("External transaction");
          return new ExternalTransactionDtos.Response(
              rs.getObject("id", UUID.class),
              rs.getObject("tenant_id", UUID.class),
              rs.getString("provider"),
              rs.getString("provider_transaction_id"),
              rs.getString("provider_transaction_code"),
              rs.getString("transaction_type"),
              rs.getString("transaction_status"),
              rs.getTimestamp("occurred_at").toInstant(),
              rs.getBigDecimal("amount"),
              rs.getString("currency"),
              rs.getBigDecimal("fee_amount"),
              rs.getBigDecimal("net_amount"),
              rs.getBigDecimal("refund_amount"),
              rs.getString("payment_method"),
              rs.getString("card_type"),
              rs.getString("masked_card_info"),
              rs.getString("payout_reference"),
              rs.getTimestamp("payout_date") == null
                  ? null
                  : rs.getTimestamp("payout_date").toInstant(),
              rs.getString("description"),
              rs.getObject("linked_order_id", UUID.class),
              rs.getObject("import_batch_id", UUID.class));
        },
        tenantId,
        id);
  }

  private record Transaction(
      UUID id,
      String provider,
      String providerTransactionId,
      Instant occurredAt,
      BigDecimal amount,
      String currency,
      UUID linkedOrderId) {}

  private record Order(UUID id, BigDecimal amount, String currency) {}
}
