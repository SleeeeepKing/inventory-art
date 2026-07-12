package com.inventoryart.report;

import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReportController {
  private final ReportService reports;
  private final InventorySalesReportService inventorySales;

  public ReportController(ReportService reports, InventorySalesReportService inventorySales) {
    this.reports = reports;
    this.inventorySales = inventorySales;
  }

  @GetMapping("/api/v1/reports/dashboard")
  public ReportDtos.Dashboard dashboard(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate start,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end,
      @RequestParam(defaultValue = "DAY") ReportGranularity granularity) {
    return reports.tenantDashboard(start, end, granularity);
  }

  @GetMapping("/api/v1/reports/inventory-sales")
  public ReportDtos.InventorySalesReport inventorySales(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate start,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate end) {
    return inventorySales.tenantReport(start, end);
  }
}
