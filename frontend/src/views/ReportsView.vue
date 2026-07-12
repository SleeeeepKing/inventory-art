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
import type { AdminTenant, PageResponse } from '@/types/api'
import PageHeader from '@/components/PageHeader.vue'
import MetricCard from '@/components/MetricCard.vue'
import ChartCanvas from '@/components/ChartCanvas.vue'
import EmptyState from '@/components/EmptyState.vue'

interface CurrencyMetric {
  currency: string
  totalSales: number
  transactionCount: number
  averageTransactionValue: number
}

interface TrendPoint {
  bucket: string
  currency: string
  totalSales: number
  transactions: number
}

interface Breakdown {
  label: string
  currency: string
  totalSales: number
  transactions: number
}

interface Dashboard {
  timezone: string
  defaultCurrency: string
  granularity: 'DAY' | 'HOUR'
  currencies: CurrencyMetric[]
  salesTrend: TrendPoint[]
  byEvent: Breakdown[]
}

interface InventoryGroup {
  productId?: string
  sku?: string
  label: string
  units: number
  batches: number
}

interface InventoryReport {
  timezone: string
  summary: { units: number; batches: number }
  byProduct: InventoryGroup[]
  byEvent: InventoryGroup[]
}

const { t, te } = useI18n()
const auth = useAuthStore()
const { showError } = useApiFeedback()
const { money, number } = useFormatters()
const loading = ref(false)
const dashboard = ref<Dashboard | null>(null)
const inventory = ref<InventoryReport | null>(null)
const tenants = ref<AdminTenant[]>([])
const tenantId = ref('')
const currency = ref('')
const granularity = ref<'DAY' | 'HOUR'>('DAY')
const today = new Date()
const start = new Date(today)
start.setDate(today.getDate() - 29)
const range = ref<[Date, Date]>([start, today])

const current = computed(
  () =>
    dashboard.value?.currencies.find((metric) => metric.currency === currency.value) ??
    dashboard.value?.currencies[0],
)
const eventRows = computed(() =>
  (dashboard.value?.byEvent ?? []).filter((row) => row.currency === current.value?.currency),
)
const localizedLabel = (label: string) =>
  te(`reports.labels.${label}`) ? t(`reports.labels.${label}`) : label || t('reports.unspecified')

const trendOption = computed<EChartsCoreOption>(() => ({
  tooltip: { trigger: 'axis' },
  legend: { bottom: 0 },
  grid: { left: 36, right: 20, top: 24, bottom: 56, containLabel: true },
  xAxis: {
    type: 'category',
    data: (dashboard.value?.salesTrend ?? [])
      .filter((point) => point.currency === current.value?.currency)
      .map((point) => point.bucket),
  },
  yAxis: [{ type: 'value' }, { type: 'value' }],
  series: [
    {
      name: t('reports.revenue'),
      type: 'line',
      smooth: true,
      areaStyle: { opacity: 0.12 },
      data: (dashboard.value?.salesTrend ?? [])
        .filter((point) => point.currency === current.value?.currency)
        .map((point) => Number(point.totalSales)),
    },
    {
      name: t('reports.orders'),
      type: 'bar',
      yAxisIndex: 1,
      data: (dashboard.value?.salesTrend ?? [])
        .filter((point) => point.currency === current.value?.currency)
        .map((point) => Number(point.transactions)),
    },
  ],
}))

function dateParam(date: Date) {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

async function loadTenants() {
  if (!auth.isAdmin) return
  const { data } = await api.get<PageResponse<AdminTenant> | AdminTenant[]>('/admin/tenants', {
    params: { page: 0, size: 100 },
  })
  tenants.value = normalizePage(data, 0, 100).items
}

async function load() {
  if (!range.value?.length) return
  loading.value = true
  try {
    const params = {
      start: dateParam(range.value[0]),
      end: dateParam(range.value[1]),
      granularity: granularity.value,
      tenantId: auth.isAdmin ? tenantId.value || undefined : undefined,
    }
    const base = auth.isAdmin ? '/admin/reports' : '/reports'
    const [financialResponse, inventoryResponse] = await Promise.all([
      api.get<Dashboard>(`${base}/dashboard`, { params }),
      api.get<InventoryReport>(`${base}/inventory-sales`, { params }),
    ])
    dashboard.value = financialResponse.data
    inventory.value = inventoryResponse.data
    if (!financialResponse.data.currencies.some((metric) => metric.currency === currency.value)) {
      currency.value = financialResponse.data.currencies[0]?.currency ?? ''
    }
  } catch (error) {
    showError(error)
  } finally {
    loading.value = false
  }
}

async function initialize() {
  try {
    await loadTenants()
    await load()
  } catch (error) {
    showError(error)
  }
}

onMounted(initialize)
</script>

<template>
  <div class="page-stack">
    <PageHeader
      :eyebrow="t('reports.eyebrow')"
      :title="t('reports.title')"
      :subtitle="t('reports.subtitle')"
    >
      <template #actions>
        <ElButton :icon="RefreshRight" :loading="loading" @click="load">{{
          t('common.refresh')
        }}</ElButton>
      </template>
    </PageHeader>

    <section class="panel report-filters">
      <ElSelect
        v-if="auth.isAdmin"
        v-model="tenantId"
        clearable
        filterable
        :placeholder="t('reports.allTenants')"
        @change="load"
      >
        <ElOption
          v-for="tenant in tenants"
          :key="tenant.id"
          :label="tenant.name"
          :value="tenant.id"
        />
      </ElSelect>
      <ElDatePicker
        v-model="range"
        type="daterange"
        range-separator="—"
        :start-placeholder="t('admin.fromDate')"
        :end-placeholder="t('admin.toDate')"
        @change="load"
      />
      <ElSelect v-model="granularity" @change="load">
        <ElOption :label="t('reports.byDay')" value="DAY" />
        <ElOption :label="t('reports.byHour')" value="HOUR" :disabled="auth.isAdmin && !tenantId" />
      </ElSelect>
      <ElSelect v-if="dashboard?.currencies.length" v-model="currency">
        <ElOption
          v-for="metric in dashboard.currencies"
          :key="metric.currency"
          :label="metric.currency"
          :value="metric.currency"
        />
      </ElSelect>
    </section>

    <section v-if="current" class="metric-grid">
      <MetricCard
        :label="t('reports.revenue')"
        :value="money(current.totalSales, current.currency)"
      />
      <MetricCard :label="t('reports.orders')" :value="number(current.transactionCount)" />
      <MetricCard
        :label="t('reports.averageOrderValue')"
        :value="money(current.averageTransactionValue, current.currency)"
      />
    </section>

    <section class="report-grid">
      <article class="panel chart-panel">
        <div class="panel-heading">
          <h2>{{ granularity === 'HOUR' ? t('reports.hourlySales') : t('reports.dailySales') }}</h2>
          <small v-if="dashboard">{{
            t('reports.timezoneHint', { timezone: dashboard.timezone })
          }}</small>
        </div>
        <ChartCanvas v-if="current" :option="trendOption" />
        <EmptyState v-else :title="t('reports.noData')" :body="t('reports.noExchange')" />
      </article>

      <article class="panel data-panel">
        <div class="panel-heading">
          <h2>{{ t('reports.events') }}</h2>
        </div>
        <ElTable v-if="eventRows.length" :data="eventRows">
          <ElTableColumn prop="label" :label="t('orders.event')" min-width="190" />
          <ElTableColumn :label="t('reports.orders')" align="right">
            <template #default="scope">{{ number(scope.row.transactions) }}</template>
          </ElTableColumn>
          <ElTableColumn :label="t('reports.revenue')" align="right">
            <template #default="scope">{{
              money(scope.row.totalSales, scope.row.currency)
            }}</template>
          </ElTableColumn>
        </ElTable>
        <EmptyState v-else :title="t('reports.noData')" :body="t('reports.noExchange')" />
      </article>
    </section>

    <section class="metric-grid">
      <MetricCard :label="t('reports.units')" :value="number(inventory?.summary.units || 0)" />
      <MetricCard
        :label="t('reports.saleBatches')"
        :value="number(inventory?.summary.batches || 0)"
      />
    </section>

    <section class="report-grid">
      <article class="panel data-panel">
        <div class="panel-heading">
          <h2>{{ t('reports.topProducts') }}</h2>
        </div>
        <ElTable v-if="inventory?.byProduct.length" :data="inventory.byProduct">
          <ElTableColumn :label="t('reports.product')" min-width="200">
            <template #default="scope">
              <div class="cell-stack">
                <strong>{{ scope.row.label }}</strong
                ><code>{{ scope.row.sku }}</code>
              </div>
            </template>
          </ElTableColumn>
          <ElTableColumn :label="t('reports.units')" align="right">
            <template #default="scope">{{ number(scope.row.units) }}</template>
          </ElTableColumn>
          <ElTableColumn :label="t('reports.saleBatches')" align="right">
            <template #default="scope">{{ number(scope.row.batches) }}</template>
          </ElTableColumn>
        </ElTable>
        <EmptyState v-else :title="t('reports.noData')" :body="t('reports.noExchange')" />
      </article>

      <article class="panel data-panel">
        <div class="panel-heading">
          <h2>{{ t('reports.events') }}</h2>
        </div>
        <ElTable v-if="inventory?.byEvent.length" :data="inventory.byEvent">
          <ElTableColumn :label="t('orders.event')" min-width="200">
            <template #default="scope">{{ localizedLabel(scope.row.label) }}</template>
          </ElTableColumn>
          <ElTableColumn :label="t('reports.units')" align="right">
            <template #default="scope">{{ number(scope.row.units) }}</template>
          </ElTableColumn>
          <ElTableColumn :label="t('reports.saleBatches')" align="right">
            <template #default="scope">{{ number(scope.row.batches) }}</template>
          </ElTableColumn>
        </ElTable>
        <EmptyState v-else :title="t('reports.noData')" :body="t('reports.noExchange')" />
      </article>
    </section>
  </div>
</template>
