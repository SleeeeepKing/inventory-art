package com.inventoryart.config;

import com.inventoryart.inventory.InventoryService;
import com.inventoryart.inventory.MovementType;
import com.inventoryart.order.*;
import com.inventoryart.product.Product;
import com.inventoryart.product.ProductRepository;
import com.inventoryart.tenant.Tenant;
import com.inventoryart.tenant.TenantRepository;
import com.inventoryart.user.*;
import com.inventoryart.storage.StorageService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.util.HexFormat;
import java.util.Map;

@Component @Order(10)
public class DataInitializer implements ApplicationRunner {
    private final AppProperties props;private final TenantRepository tenants;private final UserRepository users;private final ProductRepository products;private final PasswordEncoder passwords;private final InventoryService inventory;private final OrderService orderService;private final OrderRepository orderRepository;private final JdbcTemplate jdbc;private final StorageService storage;
    @Value("${ADMIN_BOOTSTRAP_USERNAME:}") private String bootstrapUsername;
    @Value("${ADMIN_BOOTSTRAP_PASSWORD:}") private String bootstrapPassword;
    public DataInitializer(AppProperties props,TenantRepository tenants,UserRepository users,ProductRepository products,PasswordEncoder passwords,InventoryService inventory,OrderService orderService,OrderRepository orderRepository,JdbcTemplate jdbc,StorageService storage){this.props=props;this.tenants=tenants;this.users=users;this.products=products;this.passwords=passwords;this.inventory=inventory;this.orderService=orderService;this.orderRepository=orderRepository;this.jdbc=jdbc;this.storage=storage;}
    @Override @Transactional public void run(ApplicationArguments args){
        if(props.getSeed().isEnabled())seedDevelopment();else bootstrapAdmin();
    }
    private void bootstrapAdmin(){if(bootstrapUsername.isBlank()||bootstrapPassword.isBlank()||users.existsByRole(UserRole.ADMIN))return;if(bootstrapPassword.length()<12)throw new IllegalStateException("ADMIN_BOOTSTRAP_PASSWORD must contain at least 12 characters");users.save(new User(UUID.randomUUID(),null,bootstrapUsername,bootstrapUsername.contains("@")?bootstrapUsername:"admin@example.invalid",passwords.encode(bootstrapPassword),"Administrator",UserRole.ADMIN));}
    private void seedDevelopment(){
        if(!users.existsByUsernameIgnoreCase("admin"))users.save(new User(UUID.randomUUID(),null,"admin","admin@inventory-art.local",passwords.encode("Admin123!"),"Administrator",UserRole.ADMIN));
        seedTenant("creator-one","Creator One","user1","user1@inventory-art.local","Art Print A","ART-001",12,new BigDecimal("15.00"));
        seedTenant("creator-two","Creator Two","user2","user2@inventory-art.local","Keychain B","KEY-001",20,new BigDecimal("8.50"));
    }
    private void seedTenant(String slug,String tenantName,String username,String email,String productName,String sku,int stock,BigDecimal price){
        Tenant tenant=tenants.findBySlug(slug).orElseGet(()->tenants.save(new Tenant(UUID.randomUUID(),tenantName,slug,"EUR","Europe/Paris","zh-CN")));
        User user=users.findByUsernameIgnoreCase(username).orElseGet(()->users.save(new User(UUID.randomUUID(),tenant.getId(),username,email,passwords.encode("User123!"),tenantName+" Owner",UserRole.USER)));
        Product product=products.findByTenantIdAndSkuIgnoreCase(tenant.getId(),sku).orElseGet(()->products.save(new Product(UUID.randomUUID(),tenant.getId(),sku,productName,"Demo","Demo Artist","Development seed product",new BigDecimal("3.00"),price,"EUR",3)));
        if(product.getCurrentStock()==0)inventory.apply(tenant.getId(),product.getId(),stock,MovementType.INITIAL,null,null,"Development seed",null,user.getId());
        UUID eventId=ensureDemoEvent(tenant,slug);
        if(orderServiceOrderMissing(tenant.getId())){OrderDtos.Request req=new OrderDtos.Request(List.of(new OrderDtos.ItemRequest(product.getId(),1,null,BigDecimal.ZERO,BigDecimal.ZERO)),price,SalesChannel.EXHIBITION,eventId,"Demo Expo","Demo Customer",null,null,"EUR",PaymentMethod.CARD,PaymentStatus.PAID,Instant.now().minusSeconds(86400));orderService.create(tenant.getId(),user.getId(),req);}
        seedSumUp(tenant,user,slug,price);
    }
    private boolean orderServiceOrderMissing(UUID tenantId){return !orderRepository.existsByTenantId(tenantId);}
    private void seedSumUp(Tenant tenant,User user,String slug,BigDecimal amount){
        Integer existing=jdbc.queryForObject("select count(*) from import_batches where tenant_id=?",Integer.class,tenant.getId());if(existing!=null&&existing>0)return;
        try{
            UUID batch=UUID.nameUUIDFromBytes((slug+"-sumup-batch").getBytes(StandardCharsets.UTF_8));UUID order=UUID.nameUUIDFromBytes((slug+"-sumup-order").getBytes(StandardCharsets.UTF_8));UUID transaction=UUID.nameUUIDFromBytes((slug+"-sumup-transaction").getBytes(StandardCharsets.UTF_8));
            UUID eventId=ensureDemoEvent(tenant,slug);
            String transactionId="DEMO-SUMUP-"+slug.toUpperCase();String source="Transaction ID;Date;Status;Type;Amount;Currency\n"+transactionId+";2026-07-10;Successful;Payment;"+amount+";EUR\n";byte[] bytes=source.getBytes(StandardCharsets.UTF_8);String checksum=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));String key="tenants/"+tenant.getId()+"/imports/"+batch+"/source.csv";
            storage.put(key,new ByteArrayInputStream(bytes),bytes.length,"text/csv",Map.of("sha256",checksum));Instant occurred=Instant.now().minusSeconds(43200);
            jdbc.update("""
                insert into import_batches(id,tenant_id,source_provider,import_type,original_filename,stored_object_key,file_checksum,file_size,detected_encoding,detected_delimiter,analysis_version,status,total_rows,valid_rows,imported_rows,order_count,event_id,event_name,created_by,created_at,started_at,completed_at)
                values(?,?,'SUMUP','TRANSACTION_HISTORY','demo-sumup.csv',?,?,?,'UTF-8',';',1,'COMPLETED',1,1,1,1,?,'Demo Expo',?,now(),now(),now())
                """,batch,tenant.getId(),key,checksum,bytes.length,eventId,user.getId());
            jdbc.update("""
                insert into orders(id,tenant_id,order_number,source,external_provider,external_transaction_id,status,allocation_status,sales_channel,event_id,event_name,currency,subtotal,discount_amount,tax_amount,refund_amount,total_amount,unallocated_amount,payment_method,payment_status,order_date,inventory_applied,manually_modified_after_import,import_batch_id,created_by,created_at,updated_at,version)
                values(?,?,?,'SUMUP_IMPORT','SUMUP',?,'COMPLETED','UNALLOCATED','EXHIBITION',?,'Demo Expo','EUR',?,0,0,0,?,?,'SUMUP','PAID',?,false,false,?,?,now(),now(),0)
                """,order,tenant.getId(),"SUMUP-DEMO-"+slug.toUpperCase(),transactionId,eventId,amount,amount,amount,Timestamp.from(occurred),batch,user.getId());
            String fingerprint=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((tenant.getId()+"|"+transactionId).getBytes(StandardCharsets.UTF_8)));
            jdbc.update("""
                insert into external_transactions(id,tenant_id,provider,provider_transaction_id,transaction_type,transaction_status,occurred_at,amount,currency,fee_amount,net_amount,refund_amount,payment_method,linked_order_id,import_batch_id,fingerprint,raw_data,active,created_at,updated_at)
                values(?,?,'SUMUP',?,'PAYMENT','SUCCESSFUL',?,?,'EUR',0,?,0,'SUMUP',?,?,?,cast(? as jsonb),true,now(),now())
                """,transaction,tenant.getId(),transactionId,Timestamp.from(occurred),amount,amount,order,batch,fingerprint,"{\"source\":\"development-seed\"}");
            jdbc.update("""
                insert into import_rows(id,tenant_id,import_batch_id,row_number,row_type,processing_status,external_transaction_id,fingerprint,normalized_data,sanitized_raw_data,validation_errors,linked_order_id,created_at)
                values(?,?,?,2,'TRANSACTION_HISTORY','IMPORTED',?,?,cast(? as jsonb),cast(? as jsonb),'[]'::jsonb,?,now())
                """,UUID.randomUUID(),tenant.getId(),batch,transactionId,fingerprint,"{\"transactionId\":\""+transactionId+"\",\"amount\":"+amount+",\"currency\":\"EUR\"}","{\"Transaction ID\":\""+transactionId+"\"}",order);
        }catch(Exception exception){throw new IllegalStateException("Unable to create development SumUp seed",exception);}
    }
    private UUID ensureDemoEvent(Tenant tenant,String slug){
        UUID eventId=UUID.nameUUIDFromBytes((slug+"-demo-event").getBytes(StandardCharsets.UTF_8));LocalDate end=LocalDate.now(ZoneId.of(tenant.getTimezone()));LocalDate start=end.minusDays(3);
        jdbc.update("""
            insert into sales_events(id,tenant_id,name,start_date,end_date,enabled,created_at,updated_at)
            values(?,?,'Demo Expo',?,?,true,now(),now())
            on conflict(tenant_id,name) do update set start_date=excluded.start_date,end_date=excluded.end_date,updated_at=now()
            """,eventId,tenant.getId(),start,end);
        return jdbc.queryForObject("select id from sales_events where tenant_id=? and name='Demo Expo'",UUID.class,tenant.getId());
    }
}
