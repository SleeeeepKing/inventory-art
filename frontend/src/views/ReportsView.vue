<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type { EChartsCoreOption } from 'echarts/core'
import { RefreshRight } from '@element-plus/icons-vue'
import { api } from '@/services/api'
import { useApiFeedback } from '@/composables/useApiFeedback'
import { useFormatters } from '@/composables/useFormatters'
import type { ReportSummary } from '@/types/api'
import PageHeader from '@/components/PageHeader.vue'
import MetricCard from '@/components/MetricCard.vue'
import ChartCanvas from '@/components/ChartCanvas.vue'
import EmptyState from '@/components/EmptyState.vue'

interface BackendDashboard {
  defaultCurrency: string
  currencies: Array<{ currency: string; netSales: number; orderCount: number; afterFees: number }>
  dailyTrend: Array<{ date: string; currency: string; netSales: number; orders: number }>
  topProducts: Array<{ name: string; sku?: string; quantity: number; revenue: number }>
  bySource: Array<{ label: string; currency: string; netSales: number; orders: number }>
  unallocatedTransactions: number
}

const { t } = useI18n()
const { showError } = useApiFeedback()
const { money, number, date, defaultCurrency } = useFormatters()
const loading = ref(false)
const start = new Date(); start.setDate(start.getDate() - 29); start.setHours(0, 0, 0, 0)
const range = ref<[Date, Date]>([start, new Date()])
const summaries = ref<ReportSummary[]>([])
const selectedCurrency = ref(defaultCurrency.value)
const current = computed(() => summaries.value.find((summary) => summary.currency === selectedCurrency.value) || summaries.value[0] || { currency: selectedCurrency.value, revenue: 0, orders: 0, unitsSold: 0, grossProfit: 0, pendingAllocation: 0, dailySales: [], topProducts: [], sources: [] })

const salesOption = computed<EChartsCoreOption>(() => ({
  animationDuration: 500,
  grid: { left: 12, right: 12, top: 22, bottom: 8, containLabel: true },
  tooltip: { trigger: 'axis', valueFormatter: (value: unknown) => money(Number(value), current.value.currency) },
  xAxis: { type: 'category', data: current.value.dailySales.map((point) => date(point.date, { month: 'short', day: 'numeric' })), axisLine: { lineStyle: { color: '#d8dee7' } }, axisTick: { show: false }, axisLabel: { color: '#657181' } },
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

function formatQueryDate(value: Date) { return value.toISOString().slice(0, 10) }
async function load() {
  loading.value = true
  try {
    const { data } = await api.get<BackendDashboard>('/reports/dashboard', { params: { start: formatQueryDate(range.value[0]), end: formatQueryDate(range.value[1]) } })
    summaries.value = data.currencies.map((metric) => ({
      currency: metric.currency,
      revenue: Number(metric.netSales),
      orders: Number(metric.orderCount),
      unitsSold: metric.currency === data.defaultCurrency ? data.topProducts.reduce((sum, product) => sum + Number(product.quantity), 0) : 0,
      grossProfit: Number(metric.afterFees),
      pendingAllocation: 0,
      dailySales: data.dailyTrend.filter((point) => point.currency === metric.currency).map((point) => ({ date: point.date, revenue: Number(point.netSales), orders: Number(point.orders) })),
      topProducts: metric.currency === data.defaultCurrency ? data.topProducts : [],
      sources: data.bySource.filter((source) => source.currency === metric.currency).map((source) => ({ name: source.label, value: Number(source.netSales) })),
    }))
    if (!summaries.value.some((summary) => summary.currency === selectedCurrency.value) && summaries.value[0]) selectedCurrency.value = summaries.value[0].currency
  } catch (error) { showError(error) } finally { loading.value = false }
}
onMounted(load)
</script>

<template>
  <div v-loading="loading" class="page-stack">
    <PageHeader :eyebrow="t('reports.eyebrow')" :title="t('reports.title')" :subtitle="t('reports.subtitle')">
      <template #actions><ElDatePicker v-model="range" type="daterange" :range-separator="'—'" :start-placeholder="t('common.date')" :end-placeholder="t('common.date')" @change="load" /><ElButton :icon="RefreshRight" @click="load">{{ t('common.refresh') }}</ElButton></template>
    </PageHeader>
    <div class="report-controls"><ElSegmented v-if="summaries.length > 1" v-model="selectedCurrency" :options="summaries.map((summary) => summary.currency)" /><ElAlert :title="t('reports.noExchange')" type="info" show-icon :closable="false" /></div>
    <section class="metric-grid metric-grid--five">
      <MetricCard :label="t('reports.revenue')" :value="money(current.revenue, current.currency)" tone="accent" />
      <MetricCard :label="t('reports.orders')" :value="number(current.orders)" />
      <MetricCard :label="t('reports.unitsSold')" :value="number(current.unitsSold)" />
      <MetricCard :label="t('reports.grossProfit')" :value="money(current.grossProfit, current.currency)" />
      <MetricCard :label="t('reports.pending')" :value="money(current.pendingAllocation, current.currency)" :tone="current.pendingAllocation ? 'warning' : 'default'" />
    </section>
    <section class="reports-grid">
      <article class="panel chart-panel"><div class="panel-heading"><h2>{{ t('reports.dailySales') }}</h2></div><ChartCanvas v-if="current.dailySales.length" :option="salesOption" /><EmptyState v-else :title="t('reports.noData')" :body="t('reports.noExchange')" /></article>
      <article class="panel chart-panel"><div class="panel-heading"><h2>{{ t('reports.salesSources') }}</h2></div><ChartCanvas v-if="current.sources?.length" :option="sourceOption" /><EmptyState v-else :title="t('reports.noData')" :body="t('reports.noExchange')" /></article>
      <article class="panel data-panel top-products-panel"><div class="panel-heading"><h2>{{ t('reports.topProducts') }}</h2></div><ElTable :data="current.topProducts"><ElTableColumn type="index" width="56" /><ElTableColumn prop="name" :label="t('reports.product')" min-width="200"><template #default="scope"><div class="cell-stack"><strong>{{ scope.row.name }}</strong><code class="sku-code">{{ scope.row.sku }}</code></div></template></ElTableColumn><ElTableColumn :label="t('reports.units')" align="right"><template #default="scope">{{ number(scope.row.quantity) }}</template></ElTableColumn><ElTableColumn :label="t('reports.revenue')" align="right"><template #default="scope"><strong>{{ money(scope.row.revenue, current.currency) }}</strong></template></ElTableColumn></ElTable></article>
    </section>
  </div>
</template>
