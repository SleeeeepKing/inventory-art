package com.inventoryart.common;

import com.inventoryart.audit.AuditService;
import com.inventoryart.event.SalesEventRepository;
import com.inventoryart.inventory.MovementType;
import com.inventoryart.order.*;
import com.inventoryart.product.Product;
import com.inventoryart.product.ProductController;
import com.inventoryart.product.ProductRepository;
import com.inventoryart.product.ProductSalesService;
import org.springframework.data.domain.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

@RestController @RequestMapping("/api/v1/admin")
public class AdminDataController {
    private final ProductRepository products;private final AuditService audit;private final ProductSalesService productSales;private final AdminDataQueryService data;private final SalesEventRepository events;
    public AdminDataController(ProductRepository products,AuditService audit,ProductSalesService productSales,AdminDataQueryService data,SalesEventRepository events){this.products=products;this.audit=audit;this.productSales=productSales;this.data=data;this.events=events;}
    @GetMapping("/products") public PageResponse<ProductController.Response> products(@RequestParam(required=false)UUID tenantId,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size){Pageable p=PageRequest.of(page,Math.min(size,50),Sort.by(Sort.Direction.DESC,"updatedAt"));Page<Product> result=tenantId==null?products.findAll(p):products.findAllByTenantId(tenantId,p);var summaries=productSales.summarize(result.getContent());audit.record(tenantId,"ADMIN_PRODUCT_LIST","PRODUCT",null,"SUCCESS",Map.of("page",page));return PageResponse.of(result.map(product->ProductController.Response.from(product,null,summaries.getOrDefault(product.getId(),ProductSalesService.Summary.EMPTY))));}
    @GetMapping("/orders") public PageResponse<AdminDataQueryService.AdminOrderRow> orders(@RequestParam(required=false)UUID tenantId,@RequestParam(required=false)UUID userId,@RequestParam(required=false)@DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME)Instant from,@RequestParam(required=false)@DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME)Instant to,@RequestParam(required=false)SalesChannel channel,@RequestParam(required=false)UUID eventId,@RequestParam(required=false)String status,@RequestParam(required=false)String q,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size){var result=data.orders(tenantId,userId,from,to,channel,eventId,status,q,page,size);audit.record(tenantId,"ADMIN_ORDER_LIST","ORDER",null,"SUCCESS",metadata(page,userId,channel,eventId,from,to));return result;}
    @GetMapping("/inventory/movements") public PageResponse<AdminDataQueryService.AdminInventoryRow> inventory(@RequestParam(required=false)UUID tenantId,@RequestParam(required=false)UUID userId,@RequestParam(required=false)@DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME)Instant from,@RequestParam(required=false)@DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME)Instant to,@RequestParam(required=false)MovementType type,@RequestParam(required=false)UUID productId,@RequestParam(required=false)SalesChannel channel,@RequestParam(required=false)UUID eventId,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size){var result=data.inventory(tenantId,userId,from,to,type,productId,channel,eventId,page,size);audit.record(tenantId,"ADMIN_INVENTORY_LIST","INVENTORY_MOVEMENT",null,"SUCCESS",metadata(page,userId,channel,eventId,from,to));return result;}
    @GetMapping("/sales-events") public List<AdminEventRow> salesEvents(@RequestParam UUID tenantId){return events.findAllByTenantIdOrderByNameAsc(tenantId).stream().map(event->new AdminEventRow(event.getId(),event.getName(),event.getStartDate(),event.getEndDate(),event.isEnabled())).toList();}
    private Map<String,Object> metadata(int page,UUID userId,SalesChannel channel,UUID eventId,Instant from,Instant to){Map<String,Object> result=new LinkedHashMap<>();result.put("page",page);if(userId!=null)result.put("userId",userId);if(channel!=null)result.put("channel",channel.name());if(eventId!=null)result.put("eventId",eventId);if(from!=null)result.put("from",from.toString());if(to!=null)result.put("to",to.toString());return result;}
    public record AdminEventRow(UUID id,String name,LocalDate startDate,LocalDate endDate,boolean enabled){}
}
