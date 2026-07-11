<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Download, Plus, RefreshRight, RefreshLeft } from '@element-plus/icons-vue'
import { api } from '@/services/api'
import { normalizePage } from '@/services/paging'
import { useApiFeedback } from '@/composables/useApiFeedback'
import { useFormatters } from '@/composables/useFormatters'
import type { ImportBatch, PageResponse } from '@/types/api'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import StatusPill from '@/components/StatusPill.vue'

const { t } = useI18n()
const router = useRouter()
const { showError } = useApiFeedback()
const { dateTime, number } = useFormatters()
const loading = ref(false)
const currentPage = ref(1)
const pageSize = ref(20)
const page = ref<PageResponse<ImportBatch>>(normalizePage([]))

async function load() {
  loading.value = true
  try {
    const { data } = await api.get<PageResponse<ImportBatch> | ImportBatch[]>('/imports/sumup', { params: { page: currentPage.value - 1, size: pageSize.value } })
    page.value = normalizePage(data, currentPage.value - 1, pageSize.value)
  } catch (error) { showError(error) } finally { loading.value = false }
}

async function downloadErrors(batch: ImportBatch) {
  try {
    const { data } = await api.get<Blob>(`/imports/sumup/${batch.id}/errors/export`, { responseType: 'blob' })
    const url = URL.createObjectURL(data)
    const link = document.createElement('a')
    link.href = url
    link.download = `sumup-errors-${batch.id}.csv`
    link.click()
    URL.revokeObjectURL(url)
  } catch (error) { showError(error) }
}

async function reverse(batch: ImportBatch) {
  try {
    await ElMessageBox.confirm(t('import.revertBody'), t('import.revertTitle'), { type: 'warning', confirmButtonText: t('import.revert'), cancelButtonText: t('common.cancel') })
    await api.post(`/imports/sumup/${batch.id}/reverse`)
    ElMessage.success(t('status.REVERSED'))
    await load()
  } catch (error) { if (error !== 'cancel' && error !== 'close') showError(error) }
}

onMounted(load)
</script>

<template>
  <div class="page-stack">
    <PageHeader :eyebrow="t('import.eyebrow')" :title="t('import.listTitle')" :subtitle="t('import.subtitle')">
      <template #actions><ElButton type="primary" :icon="Plus" @click="router.push('/imports/new')">{{ t('import.newImport') }}</ElButton></template>
    </PageHeader>
    <section class="panel data-panel">
      <div class="table-toolbar"><ElButton :icon="RefreshRight" @click="load">{{ t('common.refresh') }}</ElButton><span class="table-toolbar__count">{{ t('common.items', { count: page.totalElements }) }}</span></div>
      <ElTable v-if="page.items.length || loading" v-loading="loading" :data="page.items" row-key="id">
        <ElTableColumn :label="t('import.file')" prop="originalFilename" min-width="230"><template #default="scope"><div class="file-cell"><span class="file-extension">{{ (scope.row.originalFilename.split('.').pop() || '—').toUpperCase() }}</span><strong>{{ scope.row.originalFilename }}</strong></div></template></ElTableColumn>
        <ElTableColumn :label="t('import.type')" prop="importType" min-width="150" />
        <ElTableColumn :label="t('import.rows')" min-width="120" align="right"><template #default="scope">{{ number(scope.row.importedRows ?? scope.row.validRows ?? scope.row.totalRows) }} / {{ number(scope.row.totalRows) }}</template></ElTableColumn>
        <ElTableColumn :label="t('common.status')" min-width="190"><template #default="scope"><StatusPill :status="scope.row.status" /></template></ElTableColumn>
        <ElTableColumn :label="t('import.started')" min-width="170"><template #default="scope">{{ dateTime(scope.row.createdAt) }}</template></ElTableColumn>
        <ElTableColumn :label="t('common.actions')" width="210" fixed="right"><template #default="scope"><ElButton v-if="scope.row.errorRows" text :icon="Download" @click="downloadErrors(scope.row)">{{ t('common.download') }}</ElButton><ElButton v-if="['COMPLETED', 'COMPLETED_WITH_ERRORS'].includes(scope.row.status)" text type="danger" :icon="RefreshLeft" @click="reverse(scope.row)">{{ t('import.revert') }}</ElButton></template></ElTableColumn>
      </ElTable>
      <EmptyState v-else :title="t('import.emptyTitle')" :body="t('import.emptyBody')"><ElButton type="primary" :icon="Plus" @click="router.push('/imports/new')">{{ t('import.newImport') }}</ElButton></EmptyState>
      <ElPagination v-if="page.totalPages > 1" v-model:current-page="currentPage" class="table-pagination" background layout="prev, pager, next" :page-size="pageSize" :total="page.totalElements" @current-change="load" />
    </section>
  </div>
</template>
