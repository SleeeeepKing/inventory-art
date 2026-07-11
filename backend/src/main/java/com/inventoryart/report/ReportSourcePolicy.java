package com.inventoryart.report;

/** Centralized inclusion policy used by every financial dashboard query. */
final class ReportSourcePolicy {
    private ReportSourcePolicy() {}

    static final String INCLUDED_SALES_CTE = """
        with included_sales as (
          select o.tenant_id, o.order_date as occurred_at, o.currency,
                 (o.total_amount + o.discount_amount) as gross_amount,
                 o.discount_amount, o.refund_amount,
                 coalesce(f.fee_amount, 0) as fee_amount,
                 (o.total_amount - o.refund_amount) as net_amount,
                 1::bigint as order_count,
                 case when o.payment_status in ('PAID','PARTIALLY_REFUNDED','REFUNDED') then 1 else 0 end::bigint as payment_count,
                 o.source, o.sales_channel, o.payment_method, coalesce(o.event_name, '') as event_name
          from orders o
          left join lateral (
            select coalesce(sum(e.fee_amount), 0) as fee_amount
            from external_transactions e
            where e.tenant_id = o.tenant_id and e.linked_order_id = o.id and e.active = true
          ) f on true
          where (:tenantId is null or o.tenant_id = cast(:tenantId as uuid))
            and o.order_date >= :from and o.order_date < :to
            and o.status in ('CONFIRMED','COMPLETED','PARTIALLY_REFUNDED','REFUNDED')

          union all

          select e.tenant_id, e.occurred_at, e.currency,
                 e.amount as gross_amount, 0::numeric as discount_amount,
                 coalesce(e.refund_amount, 0) as refund_amount,
                 coalesce(e.fee_amount, 0) as fee_amount,
                 (e.amount - coalesce(e.refund_amount, 0)) as net_amount,
                 1::bigint as order_count, 1::bigint as payment_count,
                 'SUMUP_IMPORT' as source, 'SUMUP' as sales_channel,
                 coalesce(e.payment_method, 'SUMUP') as payment_method, '' as event_name
          from external_transactions e
          where (:tenantId is null or e.tenant_id = cast(:tenantId as uuid))
            and e.occurred_at >= :from and e.occurred_at < :to
            and e.active = true and e.provider = 'SUMUP'
            and e.transaction_type = 'PAYMENT' and e.transaction_status in ('SUCCESSFUL','REFUNDED','PARTIALLY_REFUNDED')
            and e.linked_order_id is null
            and not exists (
              select 1 from orders o where o.tenant_id = e.tenant_id
                and o.external_provider = e.provider
                and o.external_transaction_id = e.provider_transaction_id
            )
        )
        """;
}
