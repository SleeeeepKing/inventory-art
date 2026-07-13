package com.inventoryart.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class ReportDtos {
  private ReportDtos() {}

  public record CurrencyMetrics(
      String currency,
      BigDecimal totalSales,
      BigDecimal totalExpenses,
      BigDecimal balance,
      long transactionCount,
      BigDecimal averageTransactionValue) {}

  public record TrendPoint(
      String bucket, String currency, BigDecimal totalSales, long transactions) {}

  public record Breakdown(
      UUID eventId,
      String label,
      String currency,
      BigDecimal totalSales,
      BigDecimal totalExpenses,
      BigDecimal balance,
      long transactions,
      long expenseCount) {}

  public record ExpenseCategoryBreakdown(
      UUID categoryId,
      String label,
      String currency,
      BigDecimal totalExpenses,
      long expenseCount) {}

  public record Dashboard(
      LocalDate startDate,
      LocalDate endDate,
      String timezone,
      String defaultCurrency,
      String granularity,
      List<CurrencyMetrics> currencies,
      List<TrendPoint> salesTrend,
      List<Breakdown> byEvent,
      List<ExpenseCategoryBreakdown> expensesByCategory) {}

  public record InventorySalesMetrics(long units, long batches) {}

  public record InventorySalesGroup(
      UUID productId, String sku, String label, String productImageUrl, long units, long batches) {}

  public record InventorySalesReport(
      LocalDate startDate,
      LocalDate endDate,
      String timezone,
      InventorySalesMetrics summary,
      List<InventorySalesGroup> byProduct,
      List<InventorySalesGroup> byEvent) {}
}
