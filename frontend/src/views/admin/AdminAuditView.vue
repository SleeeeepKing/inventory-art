<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { RefreshRight } from '@element-plus/icons-vue'
import { api } from '@/services/api'
import { normalizePage } from '@/services/paging'
import { useApiFeedback } from '@/composables/useApiFeedback'
import { useFormatters } from '@/composables/useFormatters'
import type { AdminTenant, AuditLog, PageResponse } from '@/types/api'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import StatusPill from '@/components/StatusPill.vue'

const { t } = useI18n(); const { showError } = useApiFeedback(); const { dateTime } = useFormatters()
const page = ref<PageResponse<AuditLog>>(normalizePage([])); const tenants = ref<AdminTenant[]>([]); const loading = ref(false); const currentPage = ref(1); const tenantId = ref(''); const action = ref('')
function tenantName(id?: string) { return tenants.value.find((tenant) => tenant.id === id)?.name || id || '—' }
async function load() { loading.value = true; try { const [logs, tenantResponse] = await Promise.all([api.get<PageResponse<AuditLog>>('/admin/audit', { params: { page: currentPage.value - 1, size: 50, tenantId: tenantId.value || undefined, action: action.value || undefined } }), api.get<PageResponse<AdminTenant>>('/admin/tenants', { params: { page: 0, size: 100 } })]); page.value = normalizePage(logs.data); tenants.value = normalizePage(tenantResponse.data).items } catch (error) { showError(error) } finally { loading.value = false } }
onMounted(load)
</script>

<template><div class="page-stack"><PageHeader :eyebrow="t('nav.management')" :title="t('admin.auditTitle')" :subtitle="t('admin.auditSubtitle')" /><section class="panel data-panel"><div class="table-toolbar"><ElSelect v-model="tenantId" clearable :placeholder="t('common.tenant')" @change="currentPage = 1; load()"><ElOption v-for="tenant in tenants" :key="tenant.id" :label="tenant.name" :value="tenant.id" /></ElSelect><ElInput v-model="action" class="search-input" clearable :placeholder="t('admin.action')" @keyup.enter="currentPage = 1; load()" /><ElButton :icon="RefreshRight" @click="load">{{ t('common.refresh') }}</ElButton><span class="table-toolbar__count">{{ t('common.items', { count: page.totalElements }) }}</span></div><ElTable v-if="page.items.length || loading" v-loading="loading" :data="page.items"><ElTableColumn :label="t('admin.timestamp')" min-width="170"><template #default="scope">{{ dateTime(scope.row.createdAt) }}</template></ElTableColumn><ElTableColumn :label="t('common.tenant')" min-width="180"><template #default="scope">{{ tenantName(scope.row.tenantId) }}</template></ElTableColumn><ElTableColumn prop="action" :label="t('admin.action')" min-width="210"><template #default="scope"><code class="order-code">{{ scope.row.action }}</code></template></ElTableColumn><ElTableColumn :label="t('admin.entity')" min-width="190"><template #default="scope"><div class="cell-stack"><strong>{{ scope.row.resourceType || scope.row.entityType }}</strong><small>{{ scope.row.resourceId || scope.row.entityId }}</small></div></template></ElTableColumn><ElTableColumn :label="t('admin.actor')" min-width="180"><template #default="scope">{{ scope.row.actorEmail || scope.row.actorRole || scope.row.actorUserId || '—' }}</template></ElTableColumn><ElTableColumn :label="t('common.status')" width="130"><template #default="scope"><StatusPill :status="scope.row.result || 'COMPLETED'" /></template></ElTableColumn></ElTable><EmptyState v-else :title="t('common.noData')" :body="t('admin.auditSubtitle')" /><ElPagination v-if="page.totalPages > 1" v-model:current-page="currentPage" class="table-pagination" background layout="prev, pager, next" :page-size="50" :total="page.totalElements" @current-change="load" /></section></div></template>
