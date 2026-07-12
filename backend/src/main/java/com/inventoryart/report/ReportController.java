package com.inventoryart.report;

import com.inventoryart.audit.AuditService;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReportController {
  private final ReportService reports;
  private final InventorySalesReportService inventorySales;
  private final AuditService audit;

  public ReportController(
      ReportService reports, InventorySalesReportService inventorySales, AuditService audit) {
    this.reports = reports;
    this.inventorySales = inventorySales;
    this.audit = audit;
  }

  @GetMapping("/api/v1/reports/dashboard")
  public ReportDtos.Dashboard dashboard(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate start,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end,
      @RequestParam(defaultValue = "DAY") ReportGranularity granularity) {
    return reports.tenantDashboard(start, end, granularity);
  }

  @GetMapping("/api/v1/admin/reports/dashboard")
  public ReportDtos.Dashboard adminDashboard(
      @RequestParam(required = false) UUID tenantId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate start,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end,
      @RequestParam(defaultValue = "DAY") ReportGranularity granularity) {
    ReportDtos.Dashboard dashboard = reports.adminDashboard(tenantId, start, end, granularity);
    audit.record(
        tenantId,
        "ADMIN_REPORT_READ",
        "REPORT",
        null,
        "SUCCESS",
        java.util.Map.of("start", String.valueOf(start), "end", String.valueOf(end)));
    return dashboard;
  }

  @GetMapping("/api/v1/reports/inventory-sales")
  public ReportDtos.InventorySalesReport inventorySales(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate start,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate end) {
    return inventorySales.tenantReport(start, end);
  }

  @GetMapping("/api/v1/admin/reports/inventory-sales")
  public ReportDtos.InventorySalesReport adminInventorySales(
      @RequestParam(required = false) UUID tenantId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate start,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate end) {
    ReportDtos.InventorySalesReport report = inventorySales.adminReport(tenantId, start, end);
    audit.record(
        tenantId,
        "ADMIN_INVENTORY_REPORT_READ",
        "REPORT",
        null,
        "SUCCESS",
        java.util.Map.of("start", String.valueOf(start), "end", String.valueOf(end)));
    return report;
  }
}
