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
    SalesEvent event =
        events
            .findByIdAndTenantId(eventId, tenantId)
            .orElseThrow(() -> new NotFoundException("Sales event"));
    if (!event.isEnabled()) {
      throw new BusinessException("SALES_EVENT_DISABLED", "Sales event is disabled");
    }
    return event;
  }
}
