<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type { EChartsCoreOption } from 'echarts/core'
import { RefreshRight } from '@element-plus/icons-vue'
import { api } from '@/services/api'
import { normalizePage } from '@/services/paging'
import { useApiFeedback } from '@/composables/useApiFeedback'
import { useFormatters } from '@/composables/useFormatters'
import { useAuthStore } from '@/stores/auth'
import type { AdminTenant, PageResponse, ReportSummary } from '@/types/api'
import PageHeader from '@/components/PageHeader.vue'
import MetricCard from '@/components/MetricCard.vue'
import ChartCanvas from '@/components/ChartCanvas.vue'
import EmptyState from '@/components/EmptyState.vue'

interface BackendDashboard {
  timezone: string
  granularity: 'DAY' | 'HOUR'
  defaultCurrency: string
  currencies: Array<{ currency: string; grossSales: number; discounts: number; refunds: number; netSales: number; sumUpFees: number; afterFees: number; productCost: number; estimatedGrossProfit: number; unitsSold: number; orderCount: number; successfulPaymentCount: number; averageOrderValue: number }>
  dailyTrend: Array<{ date: string; currency: string; netSales: number; orders: number }>
  salesTrend: Array<{ bucket: string; currency: string; netSales: number; orders: number }>
  topProducts: Array<{ name: string; sku?: string; currency: string; quantity: number; revenue: number }>
  bySource: Breakdown[]
  byChannel: Breakdown[]
  byPaymentMethod: Breakdown[]
  byEvent: Breakdown[]
  lowStockProducts: number
  unallocatedTransactions: number
  importErrors: number
}
interface Breakdown { label: string; currency: string; netSales: number; orders: number }
interface InventorySalesMetric { currency: string; units: number; batches: number; attributedAmount: number; weightedAveragePrice: number; minimumPrice: number; maximumPrice: number }
interface InventorySalesGroup extends InventorySalesMetric { productId?: string; sku?: string; label: string }
interface InventorySalesReport { timezone: string; currencies: InventorySalesMetric[]; byProduct: InventorySalesGroup[]; byChannel: InventorySalesGroup[]; byEvent: InventorySalesGroup[] }

const { t, te } = useI18n()
const { showError } = useApiFeedback()
const { money, number, defaultCurrency } = useFormatters()
const auth = useAuthStore()
const loading = ref(false)
const start = new Date(); start.setDate(start.getDate() - 29); start.setHours(0, 0, 0, 0)
const range = ref<[Date, Date]>([start, new Date()])
const summaries = ref<ReportSummary[]>([])
const tenants = ref<AdminTenant[]>([])
const selectedTenantId = ref('')
const selectedCurrency = ref(defaultCurrency.value)
const granularity = ref<'DAY' | 'HOUR'>('DAY')
const reportTimezone = ref('UTC')
const inventoryReport = ref<InventorySalesReport>({ timezone: 'UTC', currencies: [], byProduct: [], byChannel: [], byEvent: [] })
const emptySummary = (): ReportSummary => ({ currency: selectedCurrency.value, revenue: 0, grossSales: 0, discounts: 0, refunds: 0, fees: 0, afterFees: 0, productCost: 0, orders: 0, unitsSold: 0, grossProfit: 0, averageOrderValue: 0, successfulPayments: 0, pendingAllocation: 0, importErrors: 0, lowStockProducts: 0, dailySales: [], topProducts: [], sources: [], channels: [], paymentMethods: [], events: [] })
const current = computed(() => summaries.value.find((summary) => summary.currency === selectedCurrency.value) || summaries.value[0] || emptySummary())
const currentInventory = computed(() => inventoryReport.value.currencies.find((metric) => metric.currency === selectedCurrency.value) || inventoryReport.value.currencies[0])
const inventoryProducts = computed(() => inventoryReport.value.byProduct.filter((item) => item.currency === (currentInventory.value?.currency || selectedCurrency.value)))
const inventoryChannels = computed(() => inventoryReport.value.byChannel.filter((item) => item.currency === (currentInventory.value?.currency || selectedCurrency.value)))
const inventoryEvents = computed(() => inventoryReport.value.byEvent.filter((item) => item.currency === (currentInventory.value?.currency || selectedCurrency.value)))
const granularityOptions = computed(() => [
  { label: t('reports.byDay'), value: 'DAY' },
  { label: t('reports.byHour'), value: 'HOUR', disabled: auth.isAdmin && !selectedTenantId.value },
])

const salesOption = computed<EChartsCoreOption>(() => ({
  animationDuration: 500,
  grid: { left: 12, right: 12, top: 22, bottom: 8, containLabel: true },
  tooltip: { trigger: 'axis', valueFormatter: (value: unknown) => money(Number(value), current.value.currency) },
  xAxis: { type: 'category', data: current.value.dailySales.map((point) => trendLabel(point.date)), axisLine: { lineStyle: { color: '#d8dee7' } }, axisTick: { show: false }, axisLabel: { color: '#657181' } },
  yAxis: { type: 'value', splitLine: { lineStyle: { color: '#eef1f5' } }, axisLabel: { color: '#657181' } },
  series: [{ type: 'bar', data: current.value.dailySales.map((point) => point.revenue), barMaxWidth: 34, itemStyle: { color: '#163a5f', borderRadius: [4, 4, 0, 0] } }],
  aria: { enabled: true, decal: { show: true }, description: t('reports.dailySales') },
}))
const sourceOption = computed<EChartsCoreOption>(() => ({
  tooltip: { trigger: 'item' },
  legend: { bottom: 0, icon: 'circle', textStyle: { color: '#657181' } },
  series: [{ type: 'pie', radius: ['48%', '72%'], center: ['50%', '43%'], avoidLabelOverlap: true, itemStyle: { borderColor: '#fff', borderWidth: 3 }, label: { show: false }, data: current.value.sources || [], color: ['#ec5b45', '#163a5f', '#2c8c83', '#e5ad4f', '#7f6ba8'] }],
  aria: { enabled: true, decal: { show: true }, description: t('reports.salesSources') },
}))

function formatQueryDate(value: Date) {
  const year = value.getFullYear()
  const month = String(value.getMonth() + 1).padStart(2, '0')
  const day = String(value.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}
function localizedLabel(value: string) {
  if (!value) return t('reports.unspecified')
  const key = `reports.labels.${value}`
  return te(key) ? t(key) : value
}
function trendLabel(value: string) {
  return granularity.value === 'HOUR' ? value.slice(5, 16).replace('T', ' ') : value.slice(5, 10)
}
function changeGranularity(value: string | number | boolean) {
  granularity.value = value === 'HOUR' ? 'HOUR' : 'DAY'
  if (value === 'HOUR') {
    const end = new Date()
    const start = new Date(); start.setDate(end.getDate() - 6); start.setHours(0, 0, 0, 0)
    range.value = [start, end]
  }
  void load()
}
function changeReportTenant() {
  if (!selectedTenantId.value && granularity.value === 'HOUR') granularity.value = 'DAY'
  void load()
}
function breakdown(items: Breakdown[], currency: string) {
  return items.filter((item) => item.currency === currency && item.label).map((item) => ({ name: localizedLabel(item.label), value: Number(item.netSales) }))
}
async function load() {
  loading.value = true
  try {
    const endpoint = auth.isAdmin ? '/admin/reports/dashboard' : '/reports/dashboard'
    const inventoryEndpoint = auth.isAdmin ? '/admin/reports/inventory-sales' : '/reports/inventory-sales'
    const params = { start: formatQueryDate(range.value[0]), end: formatQueryDate(range.value[1]), tenantId: auth.isAdmin && selectedTenantId.value ? selectedTenantId.value : undefined }
    const [{ data }, { data: stockSales }] = await Promise.all([
      api.get<BackendDashboard>(endpoint, { params: { ...params, granularity: granularity.value } }),
      api.get<InventorySalesReport>(inventoryEndpoint, { params }),
    ])
    reportTimezone.value = data.timezone
    inventoryReport.value = stockSales
    summaries.value = data.currencies.map((metric) => ({
      currency: metric.currency,
      revenue: Number(metric.netSales),
      grossSales: Number(metric.grossSales),
      discounts: Number(metric.discounts),
      refunds: Number(metric.refunds),
      fees: Number(metric.sumUpFees),
      afterFees: Number(metric.afterFees),
      productCost: Number(metric.productCost),
      orders: Number(metric.orderCount),
      unitsSold: Number(metric.unitsSold),
      grossProfit: Number(metric.estimatedGrossProfit),
      averageOrderValue: Number(metric.averageOrderValue),
      successfulPayments: Number(metric.successfulPaymentCount),
      pendingAllocation: data.unallocatedTransactions,
      importErrors: data.importErrors,
      lowStockProducts: data.lowStockProducts,
      dailySales: (data.salesTrend || data.dailyTrend.map((point) => ({ ...point, bucket: point.date }))).filter((point) => point.currency === metric.currency).map((point) => ({ date: point.bucket, revenue: Number(point.netSales), orders: Number(point.orders) })),
      topProducts: data.topProducts.filter((product) => product.currency === metric.currency),
      sources: breakdown(data.bySource, metric.currency),
      channels: breakdown(data.byChannel, metric.currency),
      paymentMethods: breakdown(data.byPaymentMethod, metric.currency),
      events: breakdown(data.byEvent, metric.currency),
    }))
    if (!summaries.value.some((summary) => summary.currency === selectedCurrency.value) && summaries.value[0]) selectedCurrency.value = summaries.value[0].currency
  } catch (error) { showError(error) } finally { loading.value = false }
}
async function initialize() {
  if (auth.isAdmin) {
    try {
      const { data } = await api.get<PageResponse<AdminTenant>>('/admin/tenants', { params: { page: 0, size: 100 } })
      tenants.value = normalizePage(data).items
    } catch (error) { showError(error) }
  }
  await load()
}
onMounted(initialize)
</script>

<template>
  <div v-loading="loading" class="page-stack">
    <PageHeader :eyebrow="t('reports.eyebrow')" :title="t('reports.title')" :subtitle="t('reports.subtitle')">
      <template #actions><ElSelect v-if="auth.isAdmin" v-model="selectedTenantId" clearable :placeholder="t('reports.allTenants')" @change="changeReportTenant"><ElOption v-for="tenant in tenants" :key="tenant.id" :label="tenant.name" :value="tenant.id" /></ElSelect><ElDatePicker v-model="range" type="daterange" :clearable="false" :range-separator="'—'" :start-placeholder="t('common.date')" :end-placeholder="t('common.date')" @change="load" /><ElButton :icon="RefreshRight" @click="load">{{ t('common.refresh') }}</ElButton></template>
    </PageHeader>
    <div class="report-controls"><ElSegmented :model-value="granularity" :options="granularityOptions" @change="changeGranularity" /><ElSegmented v-if="summaries.length > 1" v-model="selectedCurrency" :options="summaries.map((summary) => summary.currency)" /><ElAlert :title="t('reports.timezoneHint', { timezone: reportTimezone })" type="info" show-icon :closable="false" /></div>
    <section class="metric-grid metric-grid--five">
      <MetricCard :label="t('reports.revenue')" :value="money(current.revenue, current.currency)" tone="accent" />
      <MetricCard :label="t('reports.orders')" :value="number(current.orders)" />
      <MetricCard :label="t('reports.unitsSold')" :value="number(current.unitsSold)" />
      <MetricCard :label="t('reports.grossProfit')" :value="money(current.grossProfit, current.currency)" :note="t('reports.estimatedGrossProfitHint')" />
      <MetricCard :label="t('reports.pending')" :value="number(current.pendingAllocation)" :tone="current.pendingAllocation ? 'warning' : 'default'" />
    </section>
    <section class="financial-ledger" :aria-label="t('reports.financialDetails')">
      <div><span>{{ t('reports.grossSales') }}</span><strong>{{ money(current.grossSales, current.currency) }}</strong></div>
      <div><span>{{ t('reports.discounts') }}</span><strong>−{{ money(current.discounts, current.currency) }}</strong></div>
      <div><span>{{ t('reports.refunds') }}</span><strong>−{{ money(current.refunds, current.currency) }}</strong></div>
      <div><span>{{ t('reports.fees') }}</span><strong>−{{ money(current.fees, current.currency) }}</strong></div>
      <div><span>{{ t('reports.afterFees') }}</span><strong>{{ money(current.afterFees, current.currency) }}</strong></div>
      <div><span>{{ t('reports.productCost') }}</span><strong>{{ money(current.productCost, current.currency) }}</strong></div>
      <div><span>{{ t('reports.averageOrderValue') }}</span><strong>{{ money(current.averageOrderValue, current.currency) }}</strong></div>
      <div><span>{{ t('reports.successfulPayments') }}</span><strong>{{ number(current.successfulPayments) }}</strong></div>
    </section>
    <section class="reports-grid">
      <article class="panel chart-panel"><div class="panel-heading"><h2>{{ granularity === 'HOUR' ? t('reports.hourlySales') : t('reports.dailySales') }}</h2></div><ChartCanvas v-if="current.dailySales.length" :option="salesOption" /><EmptyState v-else :title="t('reports.noData')" :body="t('reports.noExchange')" /></article>
      <article class="panel chart-panel"><div class="panel-heading"><h2>{{ t('reports.salesSources') }}</h2></div><ChartCanvas v-if="current.sources?.length" :option="sourceOption" /><EmptyState v-else :title="t('reports.noData')" :body="t('reports.noExchange')" /></article>
      <article class="panel data-panel top-products-panel"><div class="panel-heading"><h2>{{ t('reports.topProducts') }}</h2></div><ElTable :data="current.topProducts"><ElTableColumn type="index" width="56" /><ElTableColumn prop="name" :label="t('reports.product')" min-width="200"><template #default="scope"><div class="cell-stack"><strong>{{ scope.row.name }}</strong><code class="sku-code">{{ scope.row.sku }}</code></div></template></ElTableColumn><ElTableColumn :label="t('reports.units')" align="right"><template #default="scope">{{ number(scope.row.quantity) }}</template></ElTableColumn><ElTableColumn :label="t('reports.revenue')" align="right"><template #default="scope"><strong>{{ money(scope.row.revenue, current.currency) }}</strong></template></ElTableColumn></ElTable></article>
    </section>
    <section class="breakdown-grid">
      <article v-for="group in [{ key: 'reports.channels', items: current.channels }, { key: 'reports.paymentMethods', items: current.paymentMethods }, { key: 'reports.events', items: current.events }]" :key="group.key" class="panel breakdown-panel">
        <div class="panel-heading"><h2>{{ t(group.key) }}</h2></div>
        <div v-if="group.items?.length" class="breakdown-list"><div v-for="item in group.items" :key="item.name"><span>{{ item.name }}</span><strong>{{ money(item.value, current.currency) }}</strong></div></div>
        <EmptyState v-else :title="t('common.noData')" :body="t('reports.noData')" />
      </article>
    </section>
    <section class="panel inventory-analysis">
      <div class="panel-heading"><div><h2>{{ t('reports.inventorySalesTitle') }}</h2><p>{{ t('reports.inventorySalesHint') }}</p></div></div>
      <div v-if="currentInventory" class="inventory-analysis__metrics">
        <div><span>{{ t('reports.unitsSold') }}</span><strong>{{ number(currentInventory.units) }}</strong></div>
        <div><span>{{ t('reports.saleBatches') }}</span><strong>{{ number(currentInventory.batches) }}</strong></div>
        <div><span>{{ t('reports.attributedValue') }}</span><strong>{{ money(currentInventory.attributedAmount, currentInventory.currency) }}</strong></div>
        <div><span>{{ t('reports.weightedPrice') }}</span><strong>{{ money(currentInventory.weightedAveragePrice, currentInventory.currency) }}</strong><small>{{ money(currentInventory.minimumPrice, currentInventory.currency) }} — {{ money(currentInventory.maximumPrice, currentInventory.currency) }}</small></div>
      </div>
      <ElTable v-if="inventoryProducts.length" :data="inventoryProducts">
        <ElTableColumn :label="t('reports.product')" min-width="230"><template #default="scope"><div class="cell-stack"><strong>{{ scope.row.label }}</strong><code class="sku-code">{{ scope.row.sku }}</code></div></template></ElTableColumn>
        <ElTableColumn prop="units" :label="t('reports.units')" align="right" />
        <ElTableColumn :label="t('reports.weightedPrice')" align="right" min-width="140"><template #default="scope">{{ money(scope.row.weightedAveragePrice, scope.row.currency) }}</template></ElTableColumn>
        <ElTableColumn :label="t('reports.priceRange')" align="right" min-width="190"><template #default="scope">{{ money(scope.row.minimumPrice, scope.row.currency) }} — {{ money(scope.row.maximumPrice, scope.row.currency) }}</template></ElTableColumn>
        <ElTableColumn :label="t('reports.attributedValue')" align="right" min-width="150"><template #default="scope">{{ money(scope.row.attributedAmount, scope.row.currency) }}</template></ElTableColumn>
      </ElTable>
      <div class="inventory-analysis__groups">
        <article><h3>{{ t('reports.channels') }}</h3><div v-for="item in inventoryChannels" :key="`${item.label}-${item.currency}`"><span>{{ localizedLabel(item.label) }}</span><strong>{{ number(item.units) }}</strong></div></article>
        <article><h3>{{ t('reports.events') }}</h3><div v-for="item in inventoryEvents" :key="`${item.label}-${item.currency}`"><span>{{ item.label || t('reports.unspecified') }}</span><strong>{{ number(item.units) }}</strong></div></article>
      </div>
    </section>
  </div>
</template>
