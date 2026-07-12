package com.inventoryart.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class ReportDtos {
    private ReportDtos() {}

    public record CurrencyMetrics(String currency, BigDecimal grossSales, BigDecimal discounts,
                                  BigDecimal refunds, BigDecimal netSales, BigDecimal sumUpFees,
                                  BigDecimal afterFees, BigDecimal productCost,
                                  BigDecimal estimatedGrossProfit, long unitsSold, long orderCount,
                                  long successfulPaymentCount, BigDecimal averageOrderValue) {}
    public record DailyTrend(LocalDate date, String currency, BigDecimal netSales, BigDecimal fees, long orders) {}
    public record TrendPoint(String bucket, String currency, BigDecimal netSales, BigDecimal fees, long orders) {}
    public record Breakdown(String label, String currency, BigDecimal netSales, long orders) {}
    public record ProductRank(UUID productId, String sku, String name, String currency,
                              long quantity, BigDecimal revenue) {}
    public record Dashboard(LocalDate startDate, LocalDate endDate, String timezone, String defaultCurrency,
                            String granularity, List<CurrencyMetrics> currencies, List<DailyTrend> dailyTrend,
                            List<TrendPoint> salesTrend,
                            List<ProductRank> topProducts, List<Breakdown> bySource,
                            List<Breakdown> byChannel, List<Breakdown> byPaymentMethod,
                            List<Breakdown> byEvent, long lowStockProducts, long unallocatedTransactions,
                            long importErrors) {}

    public record InventorySalesMetrics(String currency, long units, long batches, BigDecimal attributedAmount,
                                        BigDecimal weightedAveragePrice, BigDecimal minimumPrice,
                                        BigDecimal maximumPrice) {}
    public record InventorySalesGroup(UUID productId, String sku, String label, String currency, long units,
                                      long batches, BigDecimal attributedAmount, BigDecimal weightedAveragePrice,
                                      BigDecimal minimumPrice, BigDecimal maximumPrice) {}
    public record InventorySalesReport(LocalDate startDate, LocalDate endDate, String timezone,
                                       List<InventorySalesMetrics> currencies,
                                       List<InventorySalesGroup> byProduct,
                                       List<InventorySalesGroup> byChannel,
                                       List<InventorySalesGroup> byEvent) {}
}
