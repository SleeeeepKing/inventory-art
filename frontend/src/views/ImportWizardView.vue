<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { Check, Document, Download, UploadFilled } from '@element-plus/icons-vue'
import { api } from '@/services/api'
import { normalizePage } from '@/services/paging'
import { useApiFeedback } from '@/composables/useApiFeedback'
import { useFormatters } from '@/composables/useFormatters'
import type { ImportBatch, PageResponse, Product } from '@/types/api'
import PageHeader from '@/components/PageHeader.vue'
import StatusPill from '@/components/StatusPill.vue'

interface SourceColumn { sourceColumn: string; sample?: string; suggestedField?: string }
interface ProductMatch { sourceKey: string; sourceName: string; sourceSku?: string; productId?: string; occurrences?: number }
interface ImportPreview {
  analysisVersion: number
  columns?: SourceColumn[]
  mappings?: Record<string, string>
  unmatchedProducts?: ProductMatch[]
  impact?: { orders: number; transactions: number; errors: number }
  sampleRows?: Array<Record<string, unknown>>
}
interface AnalyzeResponse { batch: ImportBatch; sourceColumns: string[]; suggestedMappings: Record<string, string> }
interface BackendPreview {
  batch: ImportBatch
  rows: Array<{ linkedProductId?: string; normalized?: Record<string, unknown> }>
  estimatedNew: number
  estimatedDuplicates: number
  errors: number
  needsProductMapping: number
  createsOrders: boolean
}

const { t } = useI18n()
const router = useRouter()
const { showError } = useApiFeedback()
const { number } = useFormatters()
const step = ref(0)
const busy = ref(false)
const uploadProgress = ref(0)
const dragging = ref(false)
const fileInput = ref<HTMLInputElement>()
const selectedFile = ref<File>()
const batch = ref<ImportBatch>()
const preview = ref<ImportPreview>({ analysisVersion: 0 })
const products = ref<Product[]>([])
const columnMappings = ref<Record<string, string>>({})
const productMatches = ref<ProductMatch[]>([])
let pollTimer: ReturnType<typeof setTimeout> | undefined

const fieldOptions = computed(() => [
  { value: '', label: t('import.unmapped') },
  { value: 'transactionId', label: t('import.transactionId') },
  { value: 'transactionCode', label: t('import.orderNumber') },
  { value: 'date', label: t('import.transactionDate') },
  { value: 'amount', label: t('import.amount') },
  { value: 'currency', label: t('import.currency') },
  { value: 'productName', label: t('import.productName') },
  { value: 'sku', label: t('import.productSku') },
  { value: 'quantity', label: t('import.quantity') },
  { value: 'feeAmount', label: t('import.fee') },
])
const impact = computed(() => {
  return preview.value.impact || { orders: 0, transactions: 0, errors: 0 }
})

function chooseFile() { fileInput.value?.click() }
function assignFile(file?: File) {
  if (!file) return
  const extension = file.name.split('.').pop()?.toLowerCase()
  if (!['csv', 'xls', 'xlsx'].includes(extension || '')) { ElMessage.warning(t('validation.fileType')); return }
  if (file.size > 20 * 1024 * 1024) { ElMessage.warning(t('validation.fileSize')); return }
  selectedFile.value = file
}
function onDrop(event: DragEvent) { dragging.value = false; assignFile(event.dataTransfer?.files[0]) }
function formatPreviewRow(row: Record<string, unknown>) { return JSON.stringify(row, null, 2) }

async function fetchProducts() {
  const { data } = await api.get<PageResponse<Product> | Product[]>('/products', { params: { page: 0, size: 100, enabled: true } })
  products.value = normalizePage(data, 0, 500).items
}

async function fetchPreview() {
  if (!batch.value) return
  const { data } = await api.get<BackendPreview>(`/imports/sumup/${batch.value.id}/preview`)
  batch.value = data.batch
  const matches = new Map<string, ProductMatch>()
  for (const row of data.rows || []) {
    if (row.linkedProductId) continue
    const normalized = row.normalized || {}
    const sourceSku = String(normalized.productReference || normalized.productSku || normalized.sku || '')
    const sourceName = String(normalized.productName || normalized.itemName || '')
    if (!sourceSku && !sourceName) continue
    const sourceKey = sourceSku || sourceName
    const existing = matches.get(sourceKey)
    if (existing) existing.occurrences = (existing.occurrences || 1) + 1
    else matches.set(sourceKey, { sourceKey, sourceSku: sourceSku || undefined, sourceName: sourceName || sourceSku, occurrences: 1 })
  }
  preview.value = {
    ...preview.value,
    analysisVersion: data.batch.analysisVersion || 0,
    unmatchedProducts: [...matches.values()],
    impact: { orders: data.createsOrders ? data.estimatedNew : 0, transactions: data.estimatedNew, errors: data.errors },
    sampleRows: data.rows.map((row) => row.normalized || {}),
  }
  productMatches.value = [...matches.values()]
}

async function pollBatch(terminal: string[], onReady: () => Promise<void>) {
  if (!batch.value) return
  const { data } = await api.get<ImportBatch>(`/imports/sumup/${batch.value.id}`)
  batch.value = data
  if (terminal.includes(data.status)) { await onReady(); return }
  if (data.status === 'FAILED') { busy.value = false; ElMessage.error(t('status.FAILED')); return }
  pollTimer = setTimeout(() => { void pollBatch(terminal, onReady) }, 1000)
}

async function uploadAndAnalyze() {
  if (!selectedFile.value) { ElMessage.warning(t('import.noFile')); return }
  busy.value = true
  uploadProgress.value = 0
  try {
    const body = new FormData()
    body.append('file', selectedFile.value)
    const { data } = await api.post<ImportBatch>('/imports/sumup/upload', body, { headers: { 'Content-Type': 'multipart/form-data' }, onUploadProgress: (event) => { uploadProgress.value = event.total ? Math.round((event.loaded / event.total) * 100) : 0 } })
    batch.value = data
    const { data: analysis } = await api.post<AnalyzeResponse>(`/imports/sumup/${data.id}/analyze`)
    batch.value = analysis.batch
    preview.value = {
      analysisVersion: analysis.batch.analysisVersion || 0,
      columns: analysis.sourceColumns.map((sourceColumn) => ({ sourceColumn, suggestedField: analysis.suggestedMappings[sourceColumn] || '' })),
      mappings: analysis.suggestedMappings,
    }
    columnMappings.value = { ...analysis.suggestedMappings }
    await fetchProducts()
    step.value = 1
    busy.value = false
  } catch (error) { busy.value = false; showError(error) }
}

async function saveColumns() {
  if (!batch.value) return
  busy.value = true
  try {
    const mappings = Object.fromEntries(Object.entries(columnMappings.value).filter(([, target]) => Boolean(target)))
    await api.put(`/imports/sumup/${batch.value.id}/column-mapping`, { expectedAnalysisVersion: preview.value.analysisVersion, mappings })
    await fetchPreview()
    productMatches.value = (preview.value.unmatchedProducts || []).map((match) => ({ ...match }))
    step.value = 2
  } catch (error) { showError(error) } finally { busy.value = false }
}

async function saveProducts() {
  if (!batch.value) return
  busy.value = true
  try {
    const selected = productMatches.value.filter((match) => match.productId)
    if (selected.length) {
      await api.put(`/imports/sumup/${batch.value.id}/product-mappings`, {
        expectedAnalysisVersion: preview.value.analysisVersion,
        mappings: selected.map((match) => ({ externalProductReference: match.sourceSku || match.sourceKey, externalProductName: match.sourceName, productId: match.productId, remember: true })),
      })
      await fetchPreview()
    }
    step.value = 3
  } catch (error) { showError(error) } finally { busy.value = false }
}

async function confirmImport() {
  if (!batch.value) return
  busy.value = true
  try {
    await api.post(`/imports/sumup/${batch.value.id}/confirm`, { expectedAnalysisVersion: preview.value.analysisVersion })
    await pollBatch(['COMPLETED', 'COMPLETED_WITH_ERRORS'], async () => { step.value = 4; busy.value = false })
  } catch (error) { busy.value = false; showError(error) }
}

async function downloadErrors() {
  if (!batch.value) return
  try {
    const { data } = await api.get<Blob>(`/imports/sumup/${batch.value.id}/errors/export`, { responseType: 'blob' })
    const url = URL.createObjectURL(data)
    const link = document.createElement('a'); link.href = url; link.download = `sumup-errors-${batch.value.id}.csv`; link.click(); URL.revokeObjectURL(url)
  } catch (error) { showError(error) }
}

onBeforeUnmount(() => { if (pollTimer) clearTimeout(pollTimer) })
</script>

<template>
  <div class="page-stack import-wizard-page">
    <PageHeader :eyebrow="t('import.eyebrow')" :title="t('import.title')" :subtitle="t('import.subtitle')" />
    <section class="panel wizard-panel">
      <ElSteps :active="step" finish-status="success" align-center>
        <ElStep :title="t('import.stepUpload')" /><ElStep :title="t('import.stepColumns')" /><ElStep :title="t('import.stepProducts')" /><ElStep :title="t('import.stepReview')" /><ElStep :title="t('import.stepResult')" />
      </ElSteps>

      <div v-if="step === 0" class="wizard-stage upload-stage">
        <input ref="fileInput" class="visually-hidden" type="file" accept=".csv,.xls,.xlsx" @change="assignFile(($event.target as HTMLInputElement).files?.[0])" />
        <button class="file-drop" :class="{ 'is-dragging': dragging }" type="button" @click="chooseFile" @dragenter.prevent="dragging = true" @dragover.prevent @dragleave.prevent="dragging = false" @drop.prevent="onDrop">
          <UploadFilled /><strong>{{ t('import.dropTitle') }}</strong><span>{{ t('import.dropBody') }}</span><em>{{ t('import.chooseFile') }}</em>
        </button>
        <div v-if="selectedFile" class="selected-file"><Document /><span><small>{{ t('import.selectedFile') }}</small><strong>{{ selectedFile.name }}</strong></span><b>{{ (selectedFile.size / 1024).toFixed(1) }} KB</b></div>
        <ElProgress v-if="busy && uploadProgress" class="upload-progress" :percentage="uploadProgress" :stroke-width="8" />
        <div class="wizard-actions"><ElButton @click="router.push('/imports')">{{ t('common.cancel') }}</ElButton><ElButton type="primary" :loading="busy" @click="uploadAndAnalyze">{{ busy ? t('import.analyzing') : t('import.uploadAndAnalyze') }}</ElButton></div>
      </div>

      <div v-else-if="step === 1" class="wizard-stage">
        <div class="stage-heading"><h2>{{ t('import.stepColumns') }}</h2><p>{{ t('import.mappingHint') }}</p></div>
        <ElTable :data="preview.columns || []">
          <ElTableColumn prop="sourceColumn" :label="t('import.sourceColumn')" min-width="180" />
          <ElTableColumn prop="sample" :label="t('import.sample')" min-width="220" show-overflow-tooltip />
          <ElTableColumn :label="t('import.targetField')" min-width="240"><template #default="scope"><ElSelect v-model="columnMappings[scope.row.sourceColumn]" class="full-width"><ElOption v-for="option in fieldOptions" :key="option.value" :label="option.label" :value="option.value" /></ElSelect></template></ElTableColumn>
        </ElTable>
        <div class="wizard-actions"><ElButton @click="step = 0">{{ t('common.back') }}</ElButton><ElButton type="primary" :loading="busy" @click="saveColumns">{{ t('common.continue') }}</ElButton></div>
      </div>

      <div v-else-if="step === 2" class="wizard-stage">
        <div class="stage-heading"><h2>{{ t('import.stepProducts') }}</h2><p>{{ t('import.productMatchHint') }}</p></div>
        <ElTable :data="productMatches">
          <ElTableColumn :label="t('import.sourceProduct')" min-width="240"><template #default="scope"><div class="cell-stack"><strong>{{ scope.row.sourceName }}</strong><code class="sku-code">{{ scope.row.sourceSku }}</code></div></template></ElTableColumn>
          <ElTableColumn :label="t('import.rows')" prop="occurrences" width="100" align="right" />
          <ElTableColumn :label="t('import.matchedProduct')" min-width="280"><template #default="scope"><ElSelect v-model="scope.row.productId" clearable filterable class="full-width" :placeholder="t('import.keepUnallocated')"><ElOption v-for="product in products" :key="product.id" :label="`${product.name} · ${product.sku}`" :value="product.id" /></ElSelect></template></ElTableColumn>
        </ElTable>
        <div class="wizard-actions"><ElButton @click="step = 1">{{ t('common.back') }}</ElButton><ElButton type="primary" :loading="busy" @click="saveProducts">{{ t('common.continue') }}</ElButton></div>
      </div>

      <div v-else-if="step === 3" class="wizard-stage">
        <div class="stage-heading"><h2>{{ t('import.impactTitle') }}</h2><p>{{ t('import.reviewWarning') }}</p></div>
        <div class="impact-grid"><div><span>{{ t('import.impactOrders') }}</span><strong>{{ number(impact.orders) }}</strong></div><div><span>{{ t('import.impactTransactions') }}</span><strong>{{ number(impact.transactions) }}</strong></div><div data-warning><span>{{ t('import.impactErrors') }}</span><strong>{{ number(impact.errors) }}</strong></div></div>
        <ElTable v-if="preview.sampleRows?.length" class="preview-table" :data="preview.sampleRows" max-height="360"><ElTableColumn type="index" width="58" :label="t('import.row')" /><ElTableColumn :label="t('import.normalizedPreview')"><template #default="scope"><pre class="preview-json">{{ formatPreviewRow(scope.row) }}</pre></template></ElTableColumn></ElTable>
        <ElAlert :title="t('import.reviewWarning')" type="warning" show-icon :closable="false" />
        <div class="wizard-actions"><ElButton @click="step = 2">{{ t('common.back') }}</ElButton><ElButton type="primary" :loading="busy" @click="confirmImport">{{ busy ? t('import.importing') : t('import.confirmImport') }}</ElButton></div>
      </div>

      <div v-else class="wizard-stage result-stage">
        <div class="result-mark"><Check /></div><h2>{{ t('import.completedTitle') }}</h2><p>{{ t('import.completedBody', { count: batch?.importedRows || 0 }) }}</p><StatusPill v-if="batch" :status="batch.status" />
        <div class="impact-grid"><div><span>{{ t('import.importedRows') }}</span><strong>{{ number(batch?.importedRows) }}</strong></div><div><span>{{ t('import.updatedRows') }}</span><strong>{{ number(batch?.updatedRows) }}</strong></div><div><span>{{ t('import.duplicateRows') }}</span><strong>{{ number(batch?.duplicateRows) }}</strong></div><div><span>{{ t('import.skippedRows') }}</span><strong>{{ number(batch?.skippedRows) }}</strong></div><div data-warning><span>{{ t('import.impactErrors') }}</span><strong>{{ number(batch?.errorRows) }}</strong></div></div>
        <div class="wizard-actions wizard-actions--center"><ElButton v-if="batch?.errorRows" :icon="Download" @click="downloadErrors">{{ t('import.downloadErrors') }}</ElButton><ElButton type="primary" @click="router.push('/imports')">{{ t('import.viewImports') }}</ElButton></div>
      </div>
    </section>
  </div>
</template>
