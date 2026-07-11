package com.inventoryart.common;

import com.inventoryart.audit.AuditService;
import com.inventoryart.order.*;
import com.inventoryart.product.Product;
import com.inventoryart.product.ProductController;
import com.inventoryart.product.ProductRepository;
import org.springframework.data.domain.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api/v1/admin")
public class AdminDataController {
    private final ProductRepository products;private final OrderRepository orders;private final OrderService orderService;private final AuditService audit;
    public AdminDataController(ProductRepository products,OrderRepository orders,OrderService orderService,AuditService audit){this.products=products;this.orders=orders;this.orderService=orderService;this.audit=audit;}
    @GetMapping("/products") public PageResponse<ProductController.Response> products(@RequestParam(required=false)UUID tenantId,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size){Pageable p=PageRequest.of(page,Math.min(size,100),Sort.by(Sort.Direction.DESC,"updatedAt"));Page<Product> result=tenantId==null?products.findAll(p):products.findAllByTenantId(tenantId,p);audit.record(tenantId,"ADMIN_PRODUCT_LIST","PRODUCT",null,"SUCCESS",Map.of("page",page));return PageResponse.of(result.map(ProductController.Response::from));}
    @GetMapping("/orders") public PageResponse<OrderDtos.Response> orders(@RequestParam(required=false)UUID tenantId,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size){Pageable p=PageRequest.of(page,Math.min(size,100),Sort.by(Sort.Direction.DESC,"orderDate"));Page<SalesOrder> result=tenantId==null?orders.findAll(p):orders.findAllByTenantId(tenantId,p);audit.record(tenantId,"ADMIN_ORDER_LIST","ORDER",null,"SUCCESS",Map.of("page",page));return PageResponse.of(result.map(o->orderService.response(o,List.of())));}
}
