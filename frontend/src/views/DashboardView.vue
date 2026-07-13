<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ArrowRight, Box, DocumentAdd, Refresh } from '@element-plus/icons-vue'
import type { EChartsCoreOption } from 'echarts/core'
import { api } from '@/services/api'
import { normalizePage } from '@/services/paging'
import { useFormatters } from '@/composables/useFormatters'
import { useApiFeedback } from '@/composables/useApiFeedback'
import type { Order, PageResponse, Product } from '@/types/api'
import PageHeader from '@/components/PageHeader.vue'
import MetricCard from '@/components/MetricCard.vue'
import EmptyState from '@/components/EmptyState.vue'
import ChartCanvas from '@/components/ChartCanvas.vue'
import SecureImage from '@/components/SecureImage.vue'

interface DashboardResponse {
  defaultCurrency: string
  currencies: Array<{
    currency: string
    totalSales: number
    transactionCount: number
    averageTransactionValue: number
  }>
  salesTrend: Array<{
    bucket: string
    currency: string
    totalSales: number
    transactions: number
  }>
}

const router = useRouter()
const { t } = useI18n()
const { money, number, dateTime } = useFormatters()
const { showError } = useApiFeedback()
const loading = ref(false)
const dashboard = ref<DashboardResponse | null>(null)
const recentOrders = ref<Order[]>([])
const lowStock = ref<Product[]>([])
const lowStockCount = ref(0)
const current = computed(
  () =>
    dashboard.value?.currencies.find(
      (metric) => metric.currency === dashboard.value?.defaultCurrency,
    ) ?? dashboard.value?.currencies[0],
)

const salesOption = computed<EChartsCoreOption>(() => ({
  tooltip: { trigger: 'axis' },
  grid: { left: 30, right: 16, top: 20, bottom: 28, containLabel: true },
  xAxis: {
    type: 'category',
    data: (dashboard.value?.salesTrend ?? [])
      .filter((point) => point.currency === current.value?.currency)
      .map((point) => point.bucket),
  },
  yAxis: { type: 'value' },
  series: [
    {
      type: 'line',
      smooth: true,
      areaStyle: { opacity: 0.14 },
      data: (dashboard.value?.salesTrend ?? [])
        .filter((point) => point.currency === current.value?.currency)
        .map((point) => Number(point.totalSales)),
    },
  ],
}))

async function load() {
  loading.value = true
  try {
    const [reportResponse, ordersResponse, productsResponse] = await Promise.all([
      api.get<DashboardResponse>('/reports/dashboard'),
      api.get<PageResponse<Order> | Order[]>('/orders', { params: { page: 0, size: 5 } }),
      api.get<PageResponse<Product> | Product[]>('/products', {
        params: { page: 0, size: 5, enabled: true, lowStock: true },
      }),
    ])
    dashboard.value = reportResponse.data
    recentOrders.value = normalizePage(ordersResponse.data, 0, 5).items
    const lowStockPage = normalizePage(productsResponse.data, 0, 5)
    lowStock.value = lowStockPage.items
    lowStockCount.value = lowStockPage.totalElements
  } catch (error) {
    showError(error)
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page-stack">
    <PageHeader
      :eyebrow="t('dashboard.eyebrow')"
      :title="t('dashboard.title')"
      :subtitle="t('dashboard.subtitle')"
    >
      <template #actions>
        <ElButton :icon="Refresh" :loading="loading" @click="load">{{
          t('common.refresh')
        }}</ElButton>
      </template>
    </PageHeader>

    <section class="metric-grid">
      <MetricCard
        :label="t('dashboard.revenue')"
        :value="money(current?.totalSales || 0, current?.currency)"
      />
      <MetricCard :label="t('dashboard.orders')" :value="number(current?.transactionCount || 0)" />
      <MetricCard
        :label="t('reports.averageOrderValue')"
        :value="money(current?.averageTransactionValue || 0, current?.currency)"
      />
      <MetricCard :label="t('dashboard.lowStock')" :value="number(lowStockCount)" />
    </section>

    <section class="dashboard-grid">
      <article class="panel chart-panel">
        <div class="panel-heading">
          <h2>{{ t('reports.dailySales') }}</h2>
        </div>
        <ChartCanvas v-if="current" :option="salesOption" />
        <EmptyState v-else :title="t('reports.noData')" :body="t('reports.noExchange')" />
      </article>

      <article class="panel quick-actions">
        <div class="panel-heading">
          <h2>{{ t('dashboard.quickActions') }}</h2>
        </div>
        <div class="quick-actions__list">
          <button class="quick-action" type="button" @click="router.push('/orders')">
            <span class="quick-action__icon"><DocumentAdd /></span>
            <span>{{ t('orders.batchRecord') }}</span>
            <ArrowRight />
          </button>
          <button class="quick-action" type="button" @click="router.push('/inventory')">
            <span class="quick-action__icon" data-tone="teal"><Box /></span>
            <span>{{ t('dashboard.adjustStock') }}</span>
            <ArrowRight />
          </button>
        </div>
      </article>
    </section>

    <section class="dashboard-grid">
      <article class="panel data-panel">
        <div class="panel-heading">
          <h2>{{ t('dashboard.recentOrders') }}</h2>
        </div>
        <ElTable v-if="recentOrders.length" :data="recentOrders">
          <ElTableColumn prop="eventName" :label="t('orders.event')" min-width="170" />
          <ElTableColumn :label="t('orders.orderedAt')" min-width="165">
            <template #default="scope">{{ dateTime(scope.row.orderDate) }}</template>
          </ElTableColumn>
          <ElTableColumn :label="t('orders.total')" align="right">
            <template #default="scope">{{
              money(scope.row.totalAmount, scope.row.currency)
            }}</template>
          </ElTableColumn>
        </ElTable>
        <EmptyState v-else :title="t('orders.emptyTitle')" :body="t('orders.emptyBody')" />
      </article>

      <article class="panel data-panel">
        <div class="panel-heading">
          <h2>{{ t('dashboard.lowStock') }}</h2>
        </div>
        <ElTable v-if="lowStock.length" :data="lowStock">
          <ElTableColumn :label="t('inventory.product')" min-width="210">
            <template #default="scope">
              <div class="inventory-product-cell">
                <div class="product-thumb" aria-hidden="true">
                  <SecureImage :src="scope.row.imageUrl" alt=""
                    ><span>{{
                      scope.row.name.trim().charAt(0).toUpperCase() || '?'
                    }}</span></SecureImage
                  >
                </div>
                <div class="cell-stack">
                  <strong>{{ scope.row.name }}</strong>
                </div>
              </div>
            </template>
          </ElTableColumn>
          <ElTableColumn prop="sku" label="SKU" />
          <ElTableColumn :label="t('inventory.currentStock')" align="right">
            <template #default="scope">{{ number(scope.row.currentStock) }}</template>
          </ElTableColumn>
        </ElTable>
        <EmptyState v-else :title="t('dashboard.healthy')" :body="t('dashboard.subtitle')" />
      </article>
    </section>
  </div>
</template>
