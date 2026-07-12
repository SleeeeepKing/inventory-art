package com.inventoryart.order;

import com.inventoryart.exception.BusinessException;
import com.inventoryart.exception.NotFoundException;
import com.inventoryart.event.SalesEvent;
import com.inventoryart.event.SalesEventRepository;
import com.inventoryart.inventory.InventoryService;
import com.inventoryart.inventory.MovementType;
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
    private final OrderRepository orders;private final OrderItemRepository items;private final ProductRepository products;private final InventoryService inventory;private final OrderRefundRepository refunds;private final OrderRefundItemRepository refundItems;private final PaymentRepository payments;private final SalesEventRepository events;
    public OrderService(OrderRepository orders,OrderItemRepository items,ProductRepository products,InventoryService inventory,OrderRefundRepository refunds,OrderRefundItemRepository refundItems,PaymentRepository payments,SalesEventRepository events){this.orders=orders;this.items=items;this.products=products;this.inventory=inventory;this.refunds=refunds;this.refundItems=refundItems;this.payments=payments;this.events=events;}
    @Transactional public OrderDtos.Response create(UUID tenant,UUID user,OrderDtos.Request request){
        EventSelection event=event(tenant,request);
        SalesOrder order=orders.save(new SalesOrder(UUID.randomUUID(),tenant,number(),OrderSource.MANUAL,OrderStatus.DRAFT,AllocationStatus.FULLY_ALLOCATED,event.channel(),event.id(),event.name(),request.customerName(),request.customerEmail(),request.customerNote(),request.currency().toUpperCase(),request.paymentMethod(),request.paymentStatus(),request.orderDate(),user));
        List<OrderItem> built=buildItems(tenant,order.getId(),request);items.saveAll(built);setTotals(order,built);return response(order,built);
    }
    @Transactional public OrderDtos.Response update(UUID tenant,UUID user,UUID id,OrderDtos.Request request){
        SalesOrder order=orders.findLocked(id,tenant).orElseThrow(()->new NotFoundException("Order"));
        if(order.getStatus()==OrderStatus.CANCELLED||order.getStatus()==OrderStatus.REFUNDED)throw new BusinessException("ORDER_NOT_EDITABLE","Order cannot be edited");
        if(!order.getCurrency().equalsIgnoreCase(request.currency()))throw new BusinessException("CURRENCY_MISMATCH","Order currency cannot be changed");
        List<OrderItem> old=items.findAllByTenantIdAndOrderIdOrderByCreatedAt(tenant,id);List<OrderItem> replacement=buildItems(tenant,id,request);
        if(old.stream().anyMatch(i->i.getRefundedQuantity()>0))throw new BusinessException("ORDER_NOT_EDITABLE","Refunded order items cannot be replaced");
        if(order.isInventoryApplied())applyQuantityDifference(tenant,user,order,old,replacement);
        EventSelection event=event(tenant,request);
        items.deleteAllByTenantIdAndOrderId(tenant,id);items.flush();items.saveAll(replacement);order.updateDetails(event.channel(),event.id(),event.name(),request.customerName(),request.customerEmail(),request.customerNote(),request.paymentMethod(),request.paymentStatus(),request.orderDate());setTotals(order,replacement);return response(order,replacement);
    }
    @Transactional public OrderDtos.Response allocate(UUID tenant,UUID user,UUID id,OrderDtos.Request request){
        SalesOrder order=orders.findLocked(id,tenant).orElseThrow(()->new NotFoundException("Order"));
        if(order.getSource()!=OrderSource.SUMUP_IMPORT)throw new BusinessException("ORDER_NOT_EXTERNAL","Only imported orders can be allocated");
        if(order.getStatus()==OrderStatus.CANCELLED||order.getStatus()==OrderStatus.REFUNDED)throw new BusinessException("ORDER_NOT_ALLOCATABLE","Order cannot be allocated");
        if(order.isInventoryApplied())throw new BusinessException("ORDER_ALREADY_ALLOCATED","Order inventory has already been applied");
        if(!order.getCurrency().equalsIgnoreCase(request.currency()))throw new BusinessException("CURRENCY_MISMATCH","Order currency cannot be changed");
        List<OrderItem> replacement=buildItems(tenant,id,request);Totals totals=totals(replacement);BigDecimal externalTotal=money(order.getTotalAmount());BigDecimal difference=money(externalTotal.subtract(totals.total()));
        boolean fullyAllocated=difference.abs().compareTo(ALLOCATION_TOLERANCE)<=0;
        items.deleteAllByTenantIdAndOrderId(tenant,id);items.flush();items.saveAll(replacement);
        EventSelection event=event(tenant,request);
        order.updateDetails(event.channel(),event.id(),event.name(),request.customerName(),request.customerEmail(),request.customerNote(),request.paymentMethod(),request.paymentStatus(),request.orderDate());
        order.setAmounts(totals.subtotal(),totals.discount(),totals.tax(),externalTotal,difference);order.setAllocationStatus(fullyAllocated?AllocationStatus.FULLY_ALLOCATED:AllocationStatus.PARTIALLY_ALLOCATED);
        if(fullyAllocated){applyAggregated(tenant,user,order,replacement,-1,MovementType.SALE);order.markInventoryApplied();}
        return response(order,replacement);
    }
    @Transactional public OrderDtos.Response confirm(UUID tenant,UUID user,UUID id){
        SalesOrder order=orders.findLocked(id,tenant).orElseThrow(()->new NotFoundException("Order"));
        if(order.isInventoryApplied()||order.getStatus()!=OrderStatus.DRAFT)throw new BusinessException("ORDER_ALREADY_CONFIRMED","Order has already been confirmed");
        List<OrderItem> lines=items.findAllByTenantIdAndOrderIdOrderByCreatedAt(tenant,id);if(lines.isEmpty())throw new BusinessException("ORDER_ITEMS_REQUIRED","Order must contain items");
        applyAggregated(tenant,user,order,lines,-1,MovementType.SALE);order.confirmed();recordManualPaymentIfPaid(order);return response(order,lines);
    }
    @Transactional public OrderDtos.Response cancel(UUID tenant,UUID user,UUID id){
        SalesOrder order=orders.findLocked(id,tenant).orElseThrow(()->new NotFoundException("Order"));
        if(order.getStatus()==OrderStatus.CANCELLED)throw new BusinessException("ORDER_ALREADY_CANCELLED","Order has already been cancelled");
        if(order.getStatus()==OrderStatus.PARTIALLY_REFUNDED||order.getStatus()==OrderStatus.REFUNDED)throw new BusinessException("ORDER_CANNOT_BE_CANCELLED","Refunded order cannot be cancelled");
        List<OrderItem> lines=items.findAllByTenantIdAndOrderIdOrderByCreatedAt(tenant,id);
        if(order.isInventoryApplied())applyAggregated(tenant,user,order,lines,1,MovementType.ORDER_CANCEL);order.cancelled();return response(order,lines);
    }
    @Transactional public OrderDtos.Response refund(UUID tenant,UUID user,UUID id,OrderDtos.RefundRequest request){
        SalesOrder order=orders.findLocked(id,tenant).orElseThrow(()->new NotFoundException("Order"));
        if(!order.isInventoryApplied()||order.getStatus()==OrderStatus.CANCELLED||order.getStatus()==OrderStatus.DRAFT)throw new BusinessException("ORDER_CANNOT_BE_REFUNDED","Order cannot be refunded");
        List<OrderItem> all=items.findAllByTenantIdAndOrderIdOrderByCreatedAt(tenant,id);Map<UUID,OrderItem> byId=all.stream().collect(Collectors.toMap(OrderItem::getId,i->i));BigDecimal amount=BigDecimal.ZERO;Map<UUID,BigDecimal> amounts=new HashMap<>();
        Set<UUID> seen=new HashSet<>();for(OrderDtos.RefundLine line:request.items()){
            if(!seen.add(line.orderItemId()))throw new BusinessException("INVALID_REFUND","Duplicate refund line");OrderItem item=byId.get(line.orderItemId());if(item==null)throw new NotFoundException("Order item");
            if(line.quantity()>item.refundableQuantity())throw new BusinessException("INVALID_REFUND_QUANTITY","Refund quantity exceeds remaining quantity");
            BigDecimal lineAmount=refundAmount(item,line.quantity());amounts.put(item.getId(),lineAmount);amount=amount.add(lineAmount);
        }
        OrderRefund refund=refunds.save(new OrderRefund(tenant,id,amount,request.reason(),user));
        Map<UUID,Integer> stockReturns=new TreeMap<>();
        for(OrderDtos.RefundLine line:request.items()){
            OrderItem item=byId.get(line.orderItemId());item.addRefunded(line.quantity());refundItems.save(new OrderRefundItem(tenant,refund.getId(),item.getId(),line.quantity(),amounts.get(item.getId())));
            if(item.getProductId()!=null)stockReturns.merge(item.getProductId(),line.quantity(),Integer::sum);
        }
        stockReturns.forEach((productId,quantity)->inventory.apply(tenant,productId,quantity,MovementType.ORDER_REFUND,id,null,order.getOrderNumber(),request.reason(),user));
        boolean full=all.stream().allMatch(i->i.refundableQuantity()==0);order.refunded(amount,full);return response(order,all);
    }
    @Transactional(readOnly=true) public OrderDtos.Response get(UUID tenant,UUID id){SalesOrder o=orders.findByIdAndTenantId(id,tenant).orElseThrow(()->new NotFoundException("Order"));return response(o,items.findAllByTenantIdAndOrderIdOrderByCreatedAt(tenant,id));}
    public OrderDtos.Response response(SalesOrder o,List<OrderItem> lines){return new OrderDtos.Response(o.getId(),o.getOrderNumber(),o.getSource().name(),o.getStatus().name(),o.getAllocationStatus().name(),o.getSalesChannel().name(),o.getEventId(),o.getEventName(),o.getCustomerName(),o.getCustomerEmail(),o.getCustomerNote(),o.getCurrency(),o.getSubtotal(),o.getDiscountAmount(),o.getTaxAmount(),o.getRefundAmount(),o.getTotalAmount(),o.getUnallocatedAmount(),o.getPaymentMethod().name(),o.getPaymentStatus().name(),o.getOrderDate(),o.isInventoryApplied(),o.getVersion(),lines.stream().map(i->new OrderDtos.ItemResponse(i.getId(),i.getProductId(),i.getProductSkuSnapshot(),i.getProductNameSnapshot(),i.getUnitPrice(),i.getQuantity(),i.getDiscountAmount(),i.getTaxRate(),i.getTaxAmount(),i.getLineTotal(),i.getRefundedQuantity())).toList(),o.getCreatedAt(),o.getUpdatedAt());}
    private List<OrderItem> buildItems(UUID tenant,UUID orderId,OrderDtos.Request request){List<OrderItem> result=new ArrayList<>();for(OrderDtos.ItemRequest line:request.items()){Product p=products.findByIdAndTenantId(line.productId(),tenant).filter(Product::isEnabled).orElseThrow(()->new NotFoundException("Product"));if(!p.getCurrency().equalsIgnoreCase(request.currency()))throw new BusinessException("CURRENCY_MISMATCH","Product currency does not match order");BigDecimal unit=money(line.unitPrice()==null?p.getSalePrice():line.unitPrice());BigDecimal discount=money(line.discountAmount()==null?BigDecimal.ZERO:line.discountAmount());BigDecimal gross=unit.multiply(BigDecimal.valueOf(line.quantity()));if(discount.compareTo(gross)>0)throw new BusinessException("INVALID_DISCOUNT","Discount exceeds line amount");BigDecimal rate=line.taxRate()==null?BigDecimal.ZERO:line.taxRate();BigDecimal tax=money(gross.subtract(discount).multiply(rate).divide(HUNDRED,4,RoundingMode.HALF_UP));BigDecimal total=money(gross.subtract(discount).add(tax));result.add(new OrderItem(tenant,orderId,p.getId(),p.getSku(),p.getName(),unit,line.quantity(),discount,rate,tax,total));}return result;}
    private void setTotals(SalesOrder o,List<OrderItem> lines){Totals totals=totals(lines);o.setAmounts(totals.subtotal(),totals.discount(),totals.tax(),totals.total(),BigDecimal.ZERO.setScale(4));}
    private Totals totals(List<OrderItem> lines){BigDecimal subtotal=lines.stream().map(i->i.getUnitPrice().multiply(BigDecimal.valueOf(i.getQuantity()))).reduce(BigDecimal.ZERO,BigDecimal::add);BigDecimal discount=lines.stream().map(OrderItem::getDiscountAmount).reduce(BigDecimal.ZERO,BigDecimal::add);BigDecimal tax=lines.stream().map(OrderItem::getTaxAmount).reduce(BigDecimal.ZERO,BigDecimal::add);return new Totals(money(subtotal),money(discount),money(tax),money(subtotal.subtract(discount).add(tax)));}
    private void applyAggregated(UUID tenant,UUID user,SalesOrder order,List<OrderItem> lines,int multiplier,MovementType type){Map<UUID,Integer> quantities=lines.stream().collect(Collectors.groupingBy(OrderItem::getProductId,TreeMap::new,Collectors.summingInt(OrderItem::getQuantity)));quantities.forEach((product,qty)->inventory.apply(tenant,product,qty*multiplier,type,order.getId(),order.getImportBatchId(),order.getOrderNumber(),null,user));}
    private void applyQuantityDifference(UUID tenant,UUID user,SalesOrder order,List<OrderItem> old,List<OrderItem> replacement){Map<UUID,Integer>a=old.stream().collect(Collectors.groupingBy(OrderItem::getProductId,Collectors.summingInt(OrderItem::getQuantity)));Map<UUID,Integer>b=replacement.stream().collect(Collectors.groupingBy(OrderItem::getProductId,Collectors.summingInt(OrderItem::getQuantity)));TreeSet<UUID> ids=new TreeSet<>();ids.addAll(a.keySet());ids.addAll(b.keySet());for(UUID id:ids){int delta=a.getOrDefault(id,0)-b.getOrDefault(id,0);if(delta!=0)inventory.apply(tenant,id,delta,delta<0?MovementType.SALE:MovementType.RETURN,order.getId(),order.getImportBatchId(),order.getOrderNumber(),"Confirmed order edit",user);}}
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
