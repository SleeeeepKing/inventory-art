package com.inventoryart.event;

import com.inventoryart.exception.BusinessException;
import com.inventoryart.exception.NotFoundException;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class SalesEventService {
  private final SalesEventRepository events;

  public SalesEventService(SalesEventRepository events) {
    this.events = events;
  }

  public SalesEvent requiredEnabled(UUID tenantId, UUID eventId) {
    SalesEvent event = required(tenantId, eventId);
    if (!event.isEnabled()) {
      throw new BusinessException("SALES_EVENT_DISABLED", "Sales event is disabled");
    }
    return event;
  }

  public SalesEvent required(UUID tenantId, UUID eventId) {
    return events
        .findByIdAndTenantId(eventId, tenantId)
        .orElseThrow(() -> new NotFoundException("Sales event"));
  }
}
