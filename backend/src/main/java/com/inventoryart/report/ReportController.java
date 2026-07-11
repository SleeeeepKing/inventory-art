package com.inventoryart.report;

import com.inventoryart.audit.AuditService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

@RestController
public class ReportController {
    private final ReportService reports;
    private final AuditService audit;

    public ReportController(ReportService reports, AuditService audit) { this.reports = reports; this.audit = audit; }

    @GetMapping("/api/v1/reports/dashboard")
    public ReportDtos.Dashboard dashboard(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {
        return reports.tenantDashboard(start, end);
    }

    @GetMapping("/api/v1/admin/reports/dashboard")
    public ReportDtos.Dashboard adminDashboard(
        @RequestParam(required = false) UUID tenantId,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {
        ReportDtos.Dashboard dashboard = reports.adminDashboard(tenantId, start, end);
        audit.record(tenantId, "ADMIN_REPORT_READ", "REPORT", null, "SUCCESS",
            java.util.Map.of("start", String.valueOf(start), "end", String.valueOf(end)));
        return dashboard;
    }
}
