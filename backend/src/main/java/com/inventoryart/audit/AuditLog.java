package com.inventoryart.audit;

import com.inventoryart.user.UserRole;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity @Table(name="audit_logs")
public class AuditLog {
    @Id private UUID id;
    @Column(name="tenant_id") private UUID tenantId;
    @Column(name="actor_user_id") private UUID actorUserId;
    @Enumerated(EnumType.STRING) @Column(name="actor_role") private UserRole actorRole;
    @Column(nullable=false) private String action;
    @Column(name="resource_type") private String resourceType;
    @Column(name="resource_id") private UUID resourceId;
    @Column(nullable=false) private String result;
    @Column(name="ip_address") private String ipAddress;
    @Column(name="user_agent") private String userAgent;
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition="jsonb",nullable=false) private Map<String,Object> metadata;
    @Column(name="created_at",nullable=false) private Instant createdAt;
    protected AuditLog(){}
    public AuditLog(UUID tenant,UUID actor,UserRole role,String action,String resourceType,UUID resourceId,String result,String ip,String agent,Map<String,Object> metadata){id=UUID.randomUUID();tenantId=tenant;actorUserId=actor;actorRole=role;this.action=action;this.resourceType=resourceType;this.resourceId=resourceId;this.result=result;ipAddress=ip;userAgent=agent;this.metadata=metadata;createdAt=Instant.now();}
    public UUID getId(){return id;}public UUID getTenantId(){return tenantId;}public UUID getActorUserId(){return actorUserId;}public UserRole getActorRole(){return actorRole;}public String getAction(){return action;}public String getResourceType(){return resourceType;}public UUID getResourceId(){return resourceId;}public String getResult(){return result;}public String getIpAddress(){return ipAddress;}public String getUserAgent(){return userAgent;}public Map<String,Object> getMetadata(){return metadata;}public Instant getCreatedAt(){return createdAt;}
}

