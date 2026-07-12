package com.inventoryart.order;

import com.inventoryart.exception.BusinessException;
import com.inventoryart.exception.NotFoundException;
import com.inventoryart.event.SalesEvent;
import com.inventoryart.event.SalesEventRepository;
import com.inventoryart.payment.Payment;
import com.inventoryart.payment.PaymentRepository;
import com.inventoryart.product.Product;
import com.inventoryart.product.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class OrderService {
    private static final BigDecimal HUNDRED=new BigDecimal("100");
    private static final BigDecimal ALLOCATION_TOLERANCE=new BigDecimal("0.50");
    private final OrderRepository orders;private final OrderItemRepository items;private final ProductRepository products;private final OrderRefundRepository refunds;private final OrderRefundItemRepository refundItems;private final PaymentRepository payments;private final SalesEventRepository events;
    public OrderService(OrderRepository orders,OrderItemRepository items,ProductRepository products,OrderRefundRepository refunds,OrderRefundItemRepository refundItems,PaymentRepository payments,SalesEventRepository events){this.orders=orders;this.items=items;this.products=products;this.refunds=refunds;this.refundItems=refundItems;this.payments=payments;this.events=events;}
    @Transactional public OrderDtos.Response create(UUID tenant,UUID user,OrderDtos.Request request){
        EventSelection event=event(tenant,request);
        SalesOrder order=orders.save(new SalesOrder(UUID.randomUUID(),tenant,number(),OrderSource.MANUAL,OrderStatus.DRAFT,AllocationStatus.FULLY_ALLOCATED,event.channel(),event.id(),event.name(),request.customerName(),request.customerEmail(),request.customerNote(),request.currency().toUpperCase(),request.paymentMethod(),request.paymentStatus(),request.orderDate(),user));
        List<OrderItem> built=buildItems(tenant,order.getId(),request);if(!built.isEmpty())items.saveAll(built);setRecordedTotal(order,request.totalAmount());
        order.confirmed();recordManualPaymentIfPaid(order);
        return response(order,built);
    }
    @Transactional public OrderDtos.Response update(UUID tenant,UUID user,UUID id,OrderDtos.Request request){
        SalesOrder order=orders.findLocked(id,tenant).orElseThrow(()->new NotFoundException("Order"));
        if(order.getStatus()==OrderStatus.CANCELLED||order.getStatus()==OrderStatus.REFUNDED)throw new BusinessException("ORDER_NOT_EDITABLE","Order cannot be edited");
        if(!order.getCurrency().equalsIgnoreCase(request.currency()))throw new BusinessException("CURRENCY_MISMATCH","Order currency cannot be changed");
        List<OrderItem> old=items.findAllByTenantIdAndOrderIdOrderByCreatedAt(tenant,id);List<OrderItem> replacement=buildItems(tenant,id,request);
        if(old.stream().anyMatch(i->i.getRefundedQuantity()>0))throw new BusinessException("ORDER_NOT_EDITABLE","Refunded order items cannot be replaced");
        EventSelection event=event(tenant,request);
        items.deleteAllByTenantIdAndOrderId(tenant,id);items.flush();if(!replacement.isEmpty())items.saveAll(replacement);order.updateDetails(event.channel(),event.id(),event.name(),request.customerName(),request.customerEmail(),request.customerNote(),request.paymentMethod(),request.paymentStatus(),request.orderDate());setRecordedTotal(order,request.totalAmount());return response(order,replacement);
    }
    @Transactional public OrderDtos.Response allocate(UUID tenant,UUID user,UUID id,OrderDtos.Request request){
        SalesOrder order=orders.findLocked(id,tenant).orElseThrow(()->new NotFoundException("Order"));
        if(order.getSource()!=OrderSource.SUMUP_IMPORT)throw new BusinessException("ORDER_NOT_EXTERNAL","Only imported orders can be allocated");
        if(order.getStatus()==OrderStatus.CANCELLED||order.getStatus()==OrderStatus.REFUNDED)throw new BusinessException("ORDER_NOT_ALLOCATABLE","Order cannot be allocated");
        if(!order.getCurrency().equalsIgnoreCase(request.currency()))throw new BusinessException("CURRENCY_MISMATCH","Order currency cannot be changed");
        List<OrderItem> replacement=buildItems(tenant,id,request);Totals totals=totals(replacement);BigDecimal externalTotal=money(order.getTotalAmount());BigDecimal difference=money(externalTotal.subtract(totals.total()));
        boolean fullyAllocated=!replacement.isEmpty()&&difference.abs().compareTo(ALLOCATION_TOLERANCE)<=0;
        items.deleteAllByTenantIdAndOrderId(tenant,id);items.flush();if(!replacement.isEmpty())items.saveAll(replacement);
        EventSelection event=event(tenant,request);
        order.updateDetails(event.channel(),event.id(),event.name(),request.customerName(),request.customerEmail(),request.customerNote(),request.paymentMethod(),request.paymentStatus(),request.orderDate());
        order.setAmounts(totals.subtotal(),totals.discount(),totals.tax(),externalTotal,difference);order.setAllocationStatus(fullyAllocated?AllocationStatus.FULLY_ALLOCATED:AllocationStatus.PARTIALLY_ALLOCATED);
        return response(order,replacement);
    }
    @Transactional public OrderDtos.Response confirm(UUID tenant,UUID user,UUID id){
        SalesOrder order=orders.findLocked(id,tenant).orElseThrow(()->new NotFoundException("Order"));
        if(order.getStatus()!=OrderStatus.DRAFT)throw new BusinessException("ORDER_ALREADY_CONFIRMED","Order has already been confirmed");
        List<OrderItem> lines=items.findAllByTenantIdAndOrderIdOrderByCreatedAt(tenant,id);
        order.confirmed();recordManualPaymentIfPaid(order);return response(order,lines);
    }
    @Transactional public OrderDtos.Response cancel(UUID tenant,UUID user,UUID id){
        SalesOrder order=orders.findLocked(id,tenant).orElseThrow(()->new NotFoundException("Order"));
        if(order.getStatus()==OrderStatus.CANCELLED)throw new BusinessException("ORDER_ALREADY_CANCELLED","Order has already been cancelled");
        if(order.getStatus()==OrderStatus.PARTIALLY_REFUNDED||order.getStatus()==OrderStatus.REFUNDED)throw new BusinessException("ORDER_CANNOT_BE_CANCELLED","Refunded order cannot be cancelled");
        List<OrderItem> lines=items.findAllByTenantIdAndOrderIdOrderByCreatedAt(tenant,id);
        order.cancelled();return response(order,lines);
    }
    @Transactional public OrderDtos.Response refund(UUID tenant,UUID user,UUID id,OrderDtos.RefundRequest request){
        SalesOrder order=orders.findLocked(id,tenant).orElseThrow(()->new NotFoundException("Order"));
        if(order.getStatus()==OrderStatus.CANCELLED||order.getStatus()==OrderStatus.DRAFT||order.getStatus()==OrderStatus.REFUNDED)throw new BusinessException("ORDER_CANNOT_BE_REFUNDED","Order cannot be refunded");
        List<OrderItem> all=items.findAllByTenantIdAndOrderIdOrderByCreatedAt(tenant,id);Map<UUID,OrderItem> byId=all.stream().collect(Collectors.toMap(OrderItem::getId,i->i));List<OrderDtos.RefundLine> requested=request.items()==null?List.of():request.items();BigDecimal amount=BigDecimal.ZERO;Map<UUID,BigDecimal> amounts=new HashMap<>();
        Set<UUID> seen=new HashSet<>();for(OrderDtos.RefundLine line:requested){
            if(!seen.add(line.orderItemId()))throw new BusinessException("INVALID_REFUND","Duplicate refund line");OrderItem item=byId.get(line.orderItemId());if(item==null)throw new NotFoundException("Order item");
            if(line.quantity()>item.refundableQuantity())throw new BusinessException("INVALID_REFUND_QUANTITY","Refund quantity exceeds remaining quantity");
            BigDecimal lineAmount=refundAmount(item,line.quantity());amounts.put(item.getId(),lineAmount);amount=amount.add(lineAmount);
        }
        if(requested.isEmpty()){
            if(request.amount()==null)throw new BusinessException("REFUND_AMOUNT_REQUIRED","Refund amount is required when no order items are selected");
            amount=money(request.amount());
        }else{
            amount=money(amount);
            if(request.amount()!=null){
                BigDecimal explicit=money(request.amount()),rawTotal=amount,allocated=BigDecimal.ZERO;
                for(int index=0;index<requested.size();index++){
                    UUID itemId=requested.get(index).orderItemId();
                    BigDecimal share=index==requested.size()-1?explicit.subtract(allocated):rawTotal.signum()>0?money(amounts.get(itemId).multiply(explicit).divide(rawTotal,8,RoundingMode.HALF_UP)):money(explicit.divide(BigDecimal.valueOf(requested.size()),8,RoundingMode.HALF_UP));
                    amounts.put(itemId,share);allocated=allocated.add(share);
                }
                amount=explicit;
            }
        }
        BigDecimal remaining=money(order.getTotalAmount().subtract(order.getRefundAmount()));
        if(amount.signum()<=0||amount.compareTo(remaining)>0)throw new BusinessException("INVALID_REFUND_AMOUNT","Refund amount exceeds the remaining order amount");
        OrderRefund refund=refunds.save(new OrderRefund(tenant,id,amount,request.reason(),user));
        for(OrderDtos.RefundLine line:requested){
            OrderItem item=byId.get(line.orderItemId());item.addRefunded(line.quantity());refundItems.save(new OrderRefundItem(tenant,refund.getId(),item.getId(),line.quantity(),amounts.get(item.getId())));
        }
        boolean full=order.getRefundAmount().add(amount).compareTo(order.getTotalAmount())>=0;order.refunded(amount,full);return response(order,all);
    }
    @Transactional(readOnly=true) public OrderDtos.Response get(UUID tenant,UUID id){SalesOrder o=orders.findByIdAndTenantId(id,tenant).orElseThrow(()->new NotFoundException("Order"));return response(o,items.findAllByTenantIdAndOrderIdOrderByCreatedAt(tenant,id));}
    public OrderDtos.Response response(SalesOrder o,List<OrderItem> lines){return new OrderDtos.Response(o.getId(),o.getOrderNumber(),o.getSource().name(),o.getStatus().name(),o.getAllocationStatus().name(),o.getSalesChannel().name(),o.getEventId(),o.getEventName(),o.getCustomerName(),o.getCustomerEmail(),o.getCustomerNote(),o.getCurrency(),o.getSubtotal(),o.getDiscountAmount(),o.getTaxAmount(),o.getRefundAmount(),o.getTotalAmount(),o.getUnallocatedAmount(),o.getPaymentMethod().name(),o.getPaymentStatus().name(),o.getOrderDate(),o.getVersion(),lines.stream().map(i->new OrderDtos.ItemResponse(i.getId(),i.getProductId(),i.getProductSkuSnapshot(),i.getProductNameSnapshot(),i.getUnitPrice(),i.getQuantity(),i.getDiscountAmount(),i.getTaxRate(),i.getTaxAmount(),i.getLineTotal(),i.getRefundedQuantity())).toList(),o.getCreatedAt(),o.getUpdatedAt());}
    private List<OrderItem> buildItems(UUID tenant,UUID orderId,OrderDtos.Request request){List<OrderItem> result=new ArrayList<>();for(OrderDtos.ItemRequest line:request.items()==null?List.<OrderDtos.ItemRequest>of():request.items()){Product p=products.findByIdAndTenantId(line.productId(),tenant).filter(Product::isEnabled).orElseThrow(()->new NotFoundException("Product"));if(!p.getCurrency().equalsIgnoreCase(request.currency()))throw new BusinessException("CURRENCY_MISMATCH","Product currency does not match order");BigDecimal unit=money(line.unitPrice()==null?p.getSalePrice():line.unitPrice());BigDecimal discount=money(line.discountAmount()==null?BigDecimal.ZERO:line.discountAmount());BigDecimal gross=unit.multiply(BigDecimal.valueOf(line.quantity()));if(discount.compareTo(gross)>0)throw new BusinessException("INVALID_DISCOUNT","Discount exceeds line amount");BigDecimal rate=line.taxRate()==null?BigDecimal.ZERO:line.taxRate();BigDecimal tax=money(gross.subtract(discount).multiply(rate).divide(HUNDRED,4,RoundingMode.HALF_UP));BigDecimal total=money(gross.subtract(discount).add(tax));result.add(new OrderItem(tenant,orderId,p.getId(),p.getSku(),p.getName(),unit,line.quantity(),discount,rate,tax,total));}return result;}
    private void setRecordedTotal(SalesOrder o,BigDecimal total){BigDecimal value=money(total);o.setAmounts(value,BigDecimal.ZERO.setScale(4),BigDecimal.ZERO.setScale(4),value,BigDecimal.ZERO.setScale(4));}
    private Totals totals(List<OrderItem> lines){BigDecimal subtotal=lines.stream().map(i->i.getUnitPrice().multiply(BigDecimal.valueOf(i.getQuantity()))).reduce(BigDecimal.ZERO,BigDecimal::add);BigDecimal discount=lines.stream().map(OrderItem::getDiscountAmount).reduce(BigDecimal.ZERO,BigDecimal::add);BigDecimal tax=lines.stream().map(OrderItem::getTaxAmount).reduce(BigDecimal.ZERO,BigDecimal::add);return new Totals(money(subtotal),money(discount),money(tax),money(subtotal.subtract(discount).add(tax)));}
    private BigDecimal refundAmount(OrderItem item,int quantity){int before=item.getRefundedQuantity();BigDecimal total=item.getLineTotal();BigDecimal previous=total.multiply(BigDecimal.valueOf(before)).divide(BigDecimal.valueOf(item.getQuantity()),4,RoundingMode.HALF_UP);BigDecimal after=total.multiply(BigDecimal.valueOf(before+quantity)).divide(BigDecimal.valueOf(item.getQuantity()),4,RoundingMode.HALF_UP);return after.subtract(previous).setScale(4,RoundingMode.HALF_UP);}
    private void recordManualPaymentIfPaid(SalesOrder order){if(order.getSource()==OrderSource.MANUAL&&order.getPaymentStatus()==PaymentStatus.PAID&&!payments.existsByTenantIdAndOrderId(order.getTenantId(),order.getId()))payments.save(new Payment(order.getTenantId(),order.getId(),"MANUAL",null,order.getTotalAmount(),order.getCurrency(),order.getPaymentMethod(),PaymentStatus.PAID,order.getOrderDate()));}
    private EventSelection event(UUID tenant,OrderDtos.Request request){
        if(request.eventId()!=null){
            SalesEvent event=events.findByIdAndTenantId(request.eventId(),tenant).orElseThrow(()->new NotFoundException("Sales event"));
            if(!event.isEnabled())throw new BusinessException("SALES_EVENT_DISABLED","Sales event is disabled");
            return new EventSelection(SalesChannel.EXHIBITION,event.getId(),event.getName());
        }
        String name=request.salesChannel()==SalesChannel.EXHIBITION&&request.eventName()!=null&&!request.eventName().isBlank()?request.eventName().trim():null;
        return new EventSelection(request.salesChannel(),null,name);
    }
    private BigDecimal money(BigDecimal v){return v.setScale(4,RoundingMode.HALF_UP);}private String number(){return "ORD-"+DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC).format(Instant.now())+"-"+UUID.randomUUID().toString().substring(0,8).toUpperCase();}
    private record EventSelection(SalesChannel channel,UUID id,String name){}
    private record Totals(BigDecimal subtotal,BigDecimal discount,BigDecimal tax,BigDecimal total){}
}
