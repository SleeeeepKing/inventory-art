package com.inventoryart.tenant;

import com.inventoryart.audit.AuditService;
import com.inventoryart.common.PageResponse;
import com.inventoryart.exception.BusinessException;
import com.inventoryart.exception.NotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;import java.util.Map;import java.util.UUID;

@RestController @RequestMapping("/api/v1/admin/tenants")
public class AdminTenantController {
    private final TenantRepository tenants;private final AuditService audit;
    public AdminTenantController(TenantRepository tenants,AuditService audit){this.tenants=tenants;this.audit=audit;}
    @GetMapping public PageResponse<Response> list(@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size){Page<Response> result=tenants.findAll(PageRequest.of(page,Math.min(size,100),Sort.by("name"))).map(Response::from);audit.record(null,"ADMIN_TENANT_LIST","TENANT",null,"SUCCESS",Map.of("page",page));return PageResponse.of(result);}
    @GetMapping("/{id}") public Response get(@PathVariable UUID id){Tenant t=tenants.findById(id).orElseThrow(()->new NotFoundException("Tenant"));audit.record(id,"ADMIN_TENANT_READ","TENANT",id,"SUCCESS",Map.of());return Response.from(t);}
    @PostMapping @ResponseStatus(HttpStatus.CREATED) @Transactional public Response create(@Valid @RequestBody Request r){if(tenants.findBySlug(r.slug()).isPresent())throw new BusinessException("DUPLICATE_TENANT_SLUG","Tenant slug already exists");Tenant t=tenants.save(new Tenant(UUID.randomUUID(),r.name(),r.slug(),r.defaultCurrency().toUpperCase(),r.timezone(),r.locale()));audit.record(t.getId(),"TENANT_CREATE","TENANT",t.getId(),"SUCCESS",Map.of());return Response.from(t);}
    @PutMapping("/{id}") @Transactional public Response update(@PathVariable UUID id,@Valid @RequestBody Request r){Tenant t=tenants.findById(id).orElseThrow(()->new NotFoundException("Tenant"));t.update(r.name(),r.defaultCurrency().toUpperCase(),r.timezone(),r.locale());audit.record(id,"TENANT_UPDATE","TENANT",id,"SUCCESS",Map.of());return Response.from(t);}
    @PostMapping("/{id}/enabled") @Transactional public Response enabled(@PathVariable UUID id,@RequestBody Enabled r){Tenant t=tenants.findById(id).orElseThrow(()->new NotFoundException("Tenant"));t.setEnabled(r.enabled());audit.record(id,r.enabled()?"TENANT_ENABLE":"TENANT_DISABLE","TENANT",id,"SUCCESS",Map.of());return Response.from(t);}
    public record Request(@NotBlank @Size(max=160)String name,@NotBlank @Pattern(regexp="[a-z0-9-]{2,100}")String slug,@NotBlank @Pattern(regexp="[A-Za-z]{3}")String defaultCurrency,@NotBlank String timezone,@NotBlank String locale){}
    public record Enabled(boolean enabled){} public record Response(UUID id,String name,String slug,String defaultCurrency,String timezone,String locale,boolean enabled,Instant createdAt,Instant updatedAt){static Response from(Tenant t){return new Response(t.getId(),t.getName(),t.getSlug(),t.getDefaultCurrency(),t.getTimezone(),t.getLocale(),t.isEnabled(),t.getCreatedAt(),t.getUpdatedAt());}}
}

