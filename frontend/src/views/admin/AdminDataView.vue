<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { RefreshRight } from '@element-plus/icons-vue'
import { api } from '@/services/api'
import { normalizePage } from '@/services/paging'
import { useApiFeedback } from '@/composables/useApiFeedback'
import { useFormatters } from '@/composables/useFormatters'
import type { AdminTenant, PageResponse } from '@/types/api'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import StatusPill from '@/components/StatusPill.vue'

type Row = Record<string, unknown>
const { t } = useI18n(); const { showError } = useApiFeedback(); const { money, dateTime } = useFormatters()
const rows = ref<PageResponse<Row>>(normalizePage([])); const tenants = ref<AdminTenant[]>([]); const loading = ref(false); const currentPage = ref(1); const tenantId = ref(''); const dataType = ref<'products' | 'orders' | 'external-transactions' | 'imports'>('products')
const tabs = [{ value: 'products', key: 'admin.products' }, { value: 'orders', key: 'admin.orders' }, { value: 'external-transactions', key: 'admin.transactions' }, { value: 'imports', key: 'admin.imports' }] as const
function text(row: Row, key: string) { const value = row[key]; return value == null || value === '' ? '—' : String(value) }
function numeric(row: Row, key: string) { return Number(row[key] || 0) }
async function load() { loading.value = true; try { const [data, tenantResponse] = await Promise.all([api.get<PageResponse<Row>>(`/admin/${dataType.value}`, { params: { page: currentPage.value - 1, size: 20, tenantId: tenantId.value || undefined } }), api.get<PageResponse<AdminTenant>>('/admin/tenants', { params: { page: 0, size: 100 } })]); rows.value = normalizePage(data.data); tenants.value = normalizePage(tenantResponse.data).items } catch (error) { rows.value = normalizePage([]); showError(error) } finally { loading.value = false } }
function changeType() { currentPage.value = 1; void load() }
onMounted(load)
</script>

<template>
  <div class="page-stack">
    <PageHeader :eyebrow="t('nav.management')" :title="t('admin.globalTitle')" :subtitle="t('admin.globalSubtitle')" />
    <ElAlert :title="t('admin.crossTenantNotice')" type="warning" show-icon :closable="false" />
    <section class="panel data-panel">
      <div class="data-tabs">
        <button v-for="tab in tabs" :key="tab.value" type="button" :class="{ active: dataType === tab.value }" @click="dataType = tab.value; changeType()">
          {{ t(tab.key) }}
        </button>
      </div>
      <div class="table-toolbar">
        <ElSelect v-model="tenantId" clearable :placeholder="t('common.tenant')" @change="currentPage = 1; load()">
          <ElOption v-for="tenant in tenants" :key="tenant.id" :label="tenant.name" :value="tenant.id" />
        </ElSelect>
        <ElButton :icon="RefreshRight" @click="load">
          {{ t('common.refresh') }}
        </ElButton>
        <span class="table-toolbar__count">{{ t('common.items', { count: rows.totalElements }) }}</span>
      </div>
      <ElTable v-if="rows.items.length || loading" v-loading="loading" :data="rows.items">
        <template v-if="dataType === 'products'">
          <ElTableColumn prop="sku" :label="t('products.sku')" min-width="130" />
          <ElTableColumn prop="name" :label="t('products.productName')" min-width="240" />
          <ElTableColumn :label="t('products.stock')" align="right">
            <template #default="scope">
              {{ numeric(scope.row, 'currentStock') }}
            </template>
          </ElTableColumn>
          <ElTableColumn :label="t('products.price')" align="right">
            <template #default="scope">
              {{ money(numeric(scope.row, 'salePrice'), text(scope.row, 'currency')) }}
            </template>
          </ElTableColumn>
        </template>
        <template v-else-if="dataType === 'orders'">
          <ElTableColumn prop="orderNumber" :label="t('orders.orderNumber')" min-width="160" />
          <ElTableColumn prop="customerName" :label="t('orders.customer')" min-width="200" />
          <ElTableColumn :label="t('common.status')">
            <template #default="scope">
              <StatusPill :status="text(scope.row, 'status')" />
            </template>
          </ElTableColumn>
          <ElTableColumn :label="t('orders.total')" align="right">
            <template #default="scope">
              {{ money(numeric(scope.row, 'totalAmount'), text(scope.row, 'currency')) }}
            </template>
          </ElTableColumn>
          <ElTableColumn :label="t('common.date')">
            <template #default="scope">
              {{ dateTime(text(scope.row, 'orderDate')) }}
            </template>
          </ElTableColumn>
        </template>
        <template v-else>
          <ElTableColumn prop="id" :label="t('common.details')" min-width="230" />
          <ElTableColumn :label="t('common.status')">
            <template #default="scope">
              <StatusPill :status="text(scope.row, 'status')" />
            </template>
          </ElTableColumn>
          <ElTableColumn :label="t('common.created')">
            <template #default="scope">
              {{ dateTime(text(scope.row, 'createdAt')) }}
            </template>
          </ElTableColumn>
        </template>
      </ElTable>
      <EmptyState v-else :title="t('common.noData')" :body="t('admin.globalSubtitle')" />
      <ElPagination
        v-if="rows.totalPages > 1"
        v-model:current-page="currentPage"
        class="table-pagination"
        background
        layout="prev, pager, next"
        :page-size="20"
        :total="rows.totalElements"
        @current-change="load"
      />
    </section>
  </div>
</template>
