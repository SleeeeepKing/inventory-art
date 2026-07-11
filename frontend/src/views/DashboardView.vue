<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ArrowRight, Box, DocumentAdd, Refresh, UploadFilled } from '@element-plus/icons-vue'
import type { EChartsCoreOption } from 'echarts/core'
import { api } from '@/services/api'
import { normalizePage } from '@/services/paging'
import { useFormatters } from '@/composables/useFormatters'
import { useApiFeedback } from '@/composables/useApiFeedback'
import type { Order, PageResponse, Product } from '@/types/api'
import PageHeader from '@/components/PageHeader.vue'
import MetricCard from '@/components/MetricCard.vue'
import StatusPill from '@/components/StatusPill.vue'
import EmptyState from '@/components/EmptyState.vue'
import ChartCanvas from '@/components/ChartCanvas.vue'

interface DashboardData {
  revenue: number
  currency: string
  openOrders: number
  lowStockCount: number
  unitsOnHand: number
  dailySales: Array<{ date: string; revenue: number }>
  lowStockProducts: Product[]
  recentOrders: Order[]
}
interface BackendDashboard {
  defaultCurrency: string
  currencies: Array<{ currency: string; netSales: number; orderCount: number }>
  dailyTrend: Array<{ date: string; currency: string; netSales: number; orders: number }>
  lowStockProducts: number
}

const { t } = useI18n()
const router = useRouter()
const { money, number, date } = useFormatters()
const { showError } = useApiFeedback()
const loading = ref(true)
const data = ref<DashboardData>({ revenue: 0, currency: 'EUR', openOrders: 0, lowStockCount: 0, unitsOnHand: 0, dailySales: [], lowStockProducts: [], recentOrders: [] })

const chartOption = computed<EChartsCoreOption>(() => ({
  animationDuration: 500,
  grid: { left: 8, right: 8, top: 18, bottom: 8, containLabel: true },
  tooltip: { trigger: 'axis', valueFormatter: (value: unknown) => money(Number(value), data.value.currency) },
  xAxis: { type: 'category', data: data.value.dailySales.map((point) => date(point.date, { month: 'short', day: 'numeric' })), boundaryGap: false, axisLine: { lineStyle: { color: '#d8dee7' } }, axisTick: { show: false }, axisLabel: { color: '#657181' } },
  yAxis: { type: 'value', axisLine: { show: false }, splitLine: { lineStyle: { color: '#eef1f5' } }, axisLabel: { color: '#657181' } },
  series: [{ type: 'line', data: data.value.dailySales.map((point) => point.revenue), smooth: 0.32, symbolSize: 7, lineStyle: { width: 3, color: '#ec5b45' }, itemStyle: { color: '#ec5b45', borderColor: '#fff', borderWidth: 2 }, areaStyle: { color: { type: 'linear', x: 0, y: 0, x2: 0, y2: 1, colorStops: [{ offset: 0, color: 'rgba(236,91,69,.24)' }, { offset: 1, color: 'rgba(236,91,69,0)' }] } } }],
  aria: { enabled: true, decal: { show: true }, description: t('dashboard.salesTrend') },
}))

async function load() {
  loading.value = true
  try {
    const [reportResponse, lowStockResponse, productResponse, openOrderResponse, recentOrderResponse] = await Promise.all([
      api.get<BackendDashboard>('/reports/dashboard'),
      api.get<PageResponse<Product>>('/products', { params: { page: 0, size: 6, lowStock: true, enabled: true } }),
      api.get<PageResponse<Product>>('/products', { params: { page: 0, size: 100, enabled: true } }),
      api.get<PageResponse<Order>>('/orders', { params: { page: 0, size: 1, status: 'CONFIRMED' } }),
      api.get<PageResponse<Order>>('/orders', { params: { page: 0, size: 5 } }),
    ])
    const report = reportResponse.data
    const metrics = report.currencies.find((entry) => entry.currency === report.defaultCurrency) || report.currencies[0]
    data.value = {
      revenue: Number(metrics?.netSales || 0),
      currency: metrics?.currency || report.defaultCurrency || 'EUR',
      openOrders: openOrderResponse.data.totalElements || 0,
      lowStockCount: report.lowStockProducts || 0,
      unitsOnHand: normalizePage(productResponse.data).items.reduce((sum, product) => sum + Number(product.currentStock || 0), 0),
      dailySales: report.dailyTrend.filter((point) => point.currency === (metrics?.currency || report.defaultCurrency)).map((point) => ({ date: point.date, revenue: Number(point.netSales) })),
      lowStockProducts: normalizePage(lowStockResponse.data).items,
      recentOrders: normalizePage(recentOrderResponse.data).items,
    }
  } catch (error) { showError(error) } finally { loading.value = false }
}
onMounted(load)
</script>

<template>
  <div v-loading="loading" class="page-stack">
    <PageHeader :eyebrow="t('dashboard.eyebrow')" :title="t('dashboard.title')" :subtitle="t('dashboard.subtitle')">
      <template #actions><ElButton :icon="Refresh" @click="load">{{ t('common.refresh') }}</ElButton></template>
    </PageHeader>
    <section class="metric-grid">
      <MetricCard :label="t('dashboard.revenue')" :value="money(data.revenue, data.currency)" tone="accent" />
      <MetricCard :label="t('dashboard.openOrders')" :value="number(data.openOrders)" />
      <MetricCard :label="t('dashboard.lowStock')" :value="number(data.lowStockCount)" :note="t('dashboard.stockAttentionHint')" :tone="data.lowStockCount ? 'warning' : 'default'" />
      <MetricCard :label="t('dashboard.unitsOnHand')" :value="number(data.unitsOnHand)" />
    </section>
    <section class="dashboard-grid">
      <article class="panel chart-panel">
        <div class="panel-heading"><div><p class="eyebrow">{{ t('dashboard.lastSevenDays') }}</p><h2>{{ t('dashboard.salesTrend') }}</h2></div></div>
        <ChartCanvas v-if="data.dailySales.length" :option="chartOption" />
        <EmptyState v-else :title="t('reports.noData')" :body="t('reports.noExchange')" />
      </article>
      <article class="panel stock-panel">
        <div class="panel-heading"><div><p class="eyebrow">{{ t('dashboard.lowStock') }}</p><h2>{{ t('dashboard.stockAttention') }}</h2></div><ElButton text :icon="ArrowRight" @click="router.push('/products')">{{ t('common.view') }}</ElButton></div>
        <div v-if="data.lowStockProducts.length" class="stock-list">
          <div v-for="product in data.lowStockProducts.slice(0, 6)" :key="product.id" class="stock-row">
            <span class="stock-row__mark"><Box /></span><span><strong>{{ product.name }}</strong><small>{{ product.sku }}</small></span><b>{{ number(product.currentStock) }}</b>
          </div>
        </div>
        <EmptyState v-else :title="t('dashboard.healthy')" :body="t('dashboard.stockAttentionHint')" />
      </article>
      <article class="panel recent-panel">
        <div class="panel-heading"><div><p class="eyebrow">{{ t('orders.title') }}</p><h2>{{ t('dashboard.recentOrders') }}</h2></div><ElButton text :icon="ArrowRight" @click="router.push('/orders')">{{ t('common.view') }}</ElButton></div>
        <ElTable v-if="data.recentOrders.length" :data="data.recentOrders.slice(0, 5)" table-layout="auto">
          <ElTableColumn prop="orderNumber" :label="t('orders.orderNumber')" />
          <ElTableColumn prop="customerName" :label="t('orders.customer')" />
          <ElTableColumn :label="t('common.status')"><template #default="scope"><StatusPill :status="scope.row.status" /></template></ElTableColumn>
          <ElTableColumn :label="t('orders.total')" align="right"><template #default="scope">{{ money(scope.row.totalAmount, scope.row.currency) }}</template></ElTableColumn>
        </ElTable>
        <EmptyState v-else :title="t('orders.emptyTitle')" :body="t('orders.emptyBody')" />
      </article>
      <article class="panel quick-panel">
        <div class="panel-heading"><div><p class="eyebrow">{{ t('common.actions') }}</p><h2>{{ t('dashboard.quickActions') }}</h2></div></div>
        <button type="button" @click="router.push('/orders')"><DocumentAdd /><span>{{ t('dashboard.newOrder') }}</span><ArrowRight /></button>
        <button type="button" @click="router.push('/inventory')"><Box /><span>{{ t('dashboard.adjustStock') }}</span><ArrowRight /></button>
        <button type="button" @click="router.push('/imports/new')"><UploadFilled /><span>{{ t('dashboard.importSumUp') }}</span><ArrowRight /></button>
      </article>
    </section>
  </div>
</template>
