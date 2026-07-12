package com.inventoryart.sumup;

import static com.inventoryart.common.QueryTimeBounds.from;
import static com.inventoryart.common.QueryTimeBounds.to;

import com.inventoryart.audit.AuditService;
import com.inventoryart.common.PageResponse;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminSumUpController {
  private final ImportBatchRepository batches;
  private final ExternalTransactionRepository transactions;
  private final AuditService audit;

  public AdminSumUpController(
      ImportBatchRepository batches,
      ExternalTransactionRepository transactions,
      AuditService audit) {
    this.batches = batches;
    this.transactions = transactions;
    this.audit = audit;
  }

  @GetMapping("/imports")
  public PageResponse<SumUpDtos.BatchResponse> imports(
      @RequestParam(required = false) UUID tenantId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    var result =
        batches
            .adminSearch(
                tenantId,
                PageRequest.of(
                    Math.max(0, page), pageSize(size), Sort.by(Sort.Direction.DESC, "createdAt")))
            .map(SumUpDtos.BatchResponse::from);
    audit.record(
        tenantId, "ADMIN_IMPORT_LIST", "IMPORT_BATCH", null, "SUCCESS", Map.of("page", page));
    return PageResponse.of(result);
  }

  @GetMapping("/external-transactions")
  public PageResponse<ExternalTransactionDtos.Response> transactions(
      @RequestParam(required = false) UUID tenantId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant to,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    var result =
        transactions
            .adminSearch(
                tenantId,
                status,
                from(from),
                to(to),
                PageRequest.of(
                    Math.max(0, page), pageSize(size), Sort.by(Sort.Direction.DESC, "occurredAt")))
            .map(ExternalTransactionDtos.Response::from);
    audit.record(
        tenantId,
        "ADMIN_EXTERNAL_TRANSACTION_LIST",
        "EXTERNAL_TRANSACTION",
        null,
        "SUCCESS",
        Map.of("page", page));
    return PageResponse.of(result);
  }

  private static int pageSize(int requested) {
    return Math.max(1, Math.min(requested, 100));
  }
}
