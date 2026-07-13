<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import axios from 'axios'
import { useI18n } from 'vue-i18n'
import { ElMessage, type UploadFile } from 'element-plus'
import { Delete, Edit, Plus, Search } from '@element-plus/icons-vue'
import { api, resolveApiUrl } from '@/services/api'
import { createImagePreview, sha256 } from '@/services/imagePreview'
import { normalizePage } from '@/services/paging'
import { useApiFeedback } from '@/composables/useApiFeedback'
import { useFormatters } from '@/composables/useFormatters'
import type { PageResponse, ProductFamily } from '@/types/api'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import SecureImage from '@/components/SecureImage.vue'

interface VariantForm {
  id?: string
  variantName: string
  sku: string
  initialStock: number
  lowStockThreshold: number
  enabled: boolean
  version?: number
}

const { t } = useI18n()
const { showError } = useApiFeedback()
const { number, date } = useFormatters()
const loading = ref(false)
const saving = ref(false)
const dialogOpen = ref(false)
const search = ref('')
const lowStockFilter = ref<'ALL' | 'LOW' | 'ENOUGH'>('ALL')
const selectedCategories = ref<string[]>([])
const productCategories = ref<string[]>([])
const currentPage = ref(1)
const pageSize = ref(20)
const page = ref<PageResponse<ProductFamily>>(normalizePage([]))
const imageFile = ref<File>()
const editingFamilyId = ref<string>()

const emptyVariant = (): VariantForm => ({
  variantName: '',
  sku: '',
  initialStock: 999,
  lowStockThreshold: 5,
  enabled: true,
})
const emptyForm = () => ({
  name: '',
  category: '',
  artistName: '',
  description: '',
  version: 0,
  variants: [emptyVariant()] as VariantForm[],
})
const form = reactive(emptyForm())

const totalStock = (family: ProductFamily) =>
  family.variants.reduce((sum, variant) => sum + variant.currentStock, 0)
const totalSold = (family: ProductFamily) =>
  family.variants.reduce((sum, variant) => sum + (variant.totalUnitsSold || 0), 0)
const latestSale = (family: ProductFamily) =>
  family.variants
    .map((variant) => variant.lastSaleDate)
    .filter((value): value is string => Boolean(value))
    .sort()
    .at(-1)
const dialogTitle = computed(() =>
  t(editingFamilyId.value ? 'products.editFamily' : 'products.createFamily'),
)

async function load() {
  loading.value = true
  try {
    const params: Record<string, string | number | boolean | string[] | undefined> = {
      page: currentPage.value - 1,
      size: pageSize.value,
      q: search.value.trim() || undefined,
      lowStock: lowStockFilter.value === 'ALL' ? undefined : lowStockFilter.value === 'LOW',
      categories: selectedCategories.value.length ? selectedCategories.value : undefined,
    }
    const { data } = await api.get<PageResponse<ProductFamily>>('/product-families', { params })
    page.value = normalizePage(data, currentPage.value - 1, pageSize.value)
  } catch (error) {
    showError(error)
  } finally {
    loading.value = false
  }
}

async function loadCategories() {
  try {
    const { data } = await api.get<string[]>('/products/categories')
    productCategories.value = data
  } catch (error) {
    showError(error)
  }
}

function reloadFirstPage() {
  currentPage.value = 1
  void load()
}

function openCreate() {
  editingFamilyId.value = undefined
  Object.assign(form, emptyForm())
  imageFile.value = undefined
  dialogOpen.value = true
}

function openEdit(family: ProductFamily) {
  editingFamilyId.value = family.id
  Object.assign(form, {
    name: family.name,
    category: family.category || '',
    artistName: family.artistName || '',
    description: family.description || '',
    version: family.version,
    variants: family.variants.map((variant) => ({
      id: variant.id,
      variantName: variant.variantName || '',
      sku: variant.sku,
      initialStock: 0,
      lowStockThreshold: variant.lowStockThreshold,
      enabled: variant.enabled,
      version: variant.version,
    })),
  })
  imageFile.value = undefined
  dialogOpen.value = true
}

function addVariant() {
  if (form.variants.length < 50) form.variants.push(emptyVariant())
}

function removeVariant(index: number) {
  if (form.variants.length > 1 && !form.variants[index].id) form.variants.splice(index, 1)
}

function selectImage(uploadFile: UploadFile) {
  imageFile.value = uploadFile.raw
}

async function uploadImage(productFamilyId: string, file: File) {
  const preview = await createImagePreview(file)
  const [checksumSha256, previewChecksumSha256] = await Promise.all([sha256(file), sha256(preview)])
  const { data } = await api.post<{
    uploadUrl: string
    fileId: string
    headers?: Record<string, string>
    previewUploadUrl: string
    previewHeaders?: Record<string, string>
  }>('/files/presign', {
    originalFilename: file.name,
    contentType: file.type,
    size: file.size,
    checksumSha256,
    previewSize: preview.size,
    previewChecksumSha256,
    productFamilyId,
  })
  await Promise.all([
    axios.put(resolveApiUrl(data.uploadUrl), file, {
      headers: { 'Content-Type': file.type, ...data.headers },
    }),
    axios.put(resolveApiUrl(data.previewUploadUrl), preview, {
      headers: { 'Content-Type': 'image/webp', ...data.previewHeaders },
    }),
  ])
  await api.post(`/files/${data.fileId}/confirm`)
}

function validForm() {
  const normalizedSkus = form.variants.map((variant) => variant.sku.trim().toUpperCase())
  return (
    form.name.trim() &&
    form.variants.length > 0 &&
    form.variants.every(
      (variant) => (variant.id || variant.variantName.trim()) && variant.sku.trim(),
    ) &&
    new Set(normalizedSkus).size === normalizedSkus.length
  )
}

function familyPayload() {
  return {
    name: form.name.trim(),
    category: form.category.trim() || null,
    artistName: form.artistName.trim() || null,
    description: form.description.trim() || null,
  }
}

function variantPayload(variant: VariantForm) {
  return {
    variantName: variant.variantName.trim(),
    sku: variant.sku.trim(),
    initialStock: variant.initialStock,
    lowStockThreshold: variant.lowStockThreshold,
    enabled: variant.enabled,
  }
}

async function save() {
  if (!validForm()) {
    ElMessage.warning(t('errors.validation'))
    return
  }
  saving.value = true
  const wasEditing = Boolean(editingFamilyId.value)
  try {
    let saved: ProductFamily
    if (editingFamilyId.value) {
      const familyResponse = await api.put<ProductFamily>(
        `/product-families/${editingFamilyId.value}`,
        { ...familyPayload(), version: form.version },
      )
      const existing = form.variants.filter((variant) => variant.id)
      for (const variant of existing) {
        await api.put(`/products/${variant.id}`, {
          sku: variant.sku.trim(),
          variantName: variant.variantName.trim(),
          lowStockThreshold: variant.lowStockThreshold,
          enabled: variant.enabled,
          version: variant.version,
        })
      }
      const additions = form.variants.filter((variant) => !variant.id).map(variantPayload)
      if (additions.length) {
        saved = (
          await api.post<ProductFamily>(`/product-families/${editingFamilyId.value}/variants`, {
            variants: additions,
          })
        ).data
      } else {
        saved = familyResponse.data
      }
    } else {
      saved = (
        await api.post<ProductFamily>('/product-families', {
          ...familyPayload(),
          variants: form.variants.map(variantPayload),
        })
      ).data
      editingFamilyId.value = saved.id
    }
    if (imageFile.value) {
      try {
        await uploadImage(saved.id, imageFile.value)
      } catch (error) {
        showError(error)
        ElMessage.warning(t('products.imageRetry'))
        await load()
        return
      }
    }
    ElMessage.success(t(wasEditing ? 'products.saved' : 'products.created'))
    dialogOpen.value = false
    await load()
  } catch (error) {
    showError(error)
  } finally {
    saving.value = false
  }
}

onMounted(() => {
  void load()
  void loadCategories()
})
</script>

<template>
  <div class="page-stack">
    <PageHeader
      :eyebrow="t('products.eyebrow')"
      :title="t('products.title')"
      :subtitle="t('products.subtitle')"
    >
      <template #actions>
        <ElButton type="primary" :icon="Plus" @click="openCreate">{{
          t('products.addFamily')
        }}</ElButton>
      </template>
    </PageHeader>

    <section class="panel data-panel">
      <div class="table-toolbar">
        <ElInput
          v-model="search"
          class="search-input"
          clearable
          :placeholder="t('products.searchPlaceholder')"
          :prefix-icon="Search"
          @keyup.enter="reloadFirstPage"
          @clear="reloadFirstPage"
        />
        <ElSelect
          v-model="lowStockFilter"
          :placeholder="t('products.stockStatus')"
          @change="reloadFirstPage"
        >
          <ElOption :label="t('common.all')" value="ALL" />
          <ElOption :label="t('products.lowStockOnly')" value="LOW" />
          <ElOption :label="t('products.sufficientStock')" value="ENOUGH" />
        </ElSelect>
        <ElSelect
          v-model="selectedCategories"
          multiple
          collapse-tags
          collapse-tags-tooltip
          clearable
          filterable
          :placeholder="t('products.categoryFilter')"
          @change="reloadFirstPage"
        >
          <ElOption v-for="category in productCategories" :key="category" :value="category" />
        </ElSelect>
        <ElButton @click="reloadFirstPage">{{ t('common.search') }}</ElButton>
        <span class="table-toolbar__count">{{
          t('common.items', { count: page.totalElements })
        }}</span>
      </div>

      <ElTable
        v-if="page.items.length || loading"
        v-loading="loading"
        :data="page.items"
        row-key="id"
        class="family-table"
      >
        <ElTableColumn width="76">
          <template #default="scope">
            <div class="product-thumb">
              <SecureImage :src="scope.row.imageUrl" :alt="scope.row.name">
                <span>{{ scope.row.name.slice(0, 1).toUpperCase() }}</span>
              </SecureImage>
            </div>
          </template>
        </ElTableColumn>
        <ElTableColumn :label="t('products.artwork')" min-width="230">
          <template #default="scope">
            <div class="cell-stack">
              <strong>{{ scope.row.name }}</strong>
              <small>{{
                [scope.row.artistName, scope.row.category].filter(Boolean).join(' · ') || '—'
              }}</small>
            </div>
          </template>
        </ElTableColumn>
        <ElTableColumn :label="t('products.variants')" min-width="320">
          <template #default="scope">
            <div class="variant-ribbon">
              <span
                v-for="variant in scope.row.variants"
                :key="variant.id"
                class="variant-ticket"
                :data-active="variant.enabled"
              >
                <strong>{{ variant.variantName || t('products.legacyVariant') }}</strong>
                <code>{{ variant.sku }}</code>
                <small>{{ number(variant.currentStock) }}</small>
              </span>
            </div>
          </template>
        </ElTableColumn>
        <ElTableColumn :label="t('products.stock')" align="right" min-width="100">
          <template #default="scope">{{ number(totalStock(scope.row)) }}</template>
        </ElTableColumn>
        <ElTableColumn :label="t('products.salesHistory')" min-width="170">
          <template #default="scope">
            <div class="cell-stack">
              <strong>{{
                t('products.unitsSoldValue', { count: number(totalSold(scope.row)) })
              }}</strong>
              <small>{{
                latestSale(scope.row) ? date(latestSale(scope.row)!) : t('products.neverSold')
              }}</small>
            </div>
          </template>
        </ElTableColumn>
        <ElTableColumn :label="t('common.actions')" width="76" fixed="right">
          <template #default="scope">
            <ElButton
              text
              :icon="Edit"
              :aria-label="t('common.edit')"
              @click="openEdit(scope.row)"
            />
          </template>
        </ElTableColumn>
      </ElTable>

      <EmptyState v-else :title="t('products.emptyTitle')" :body="t('products.emptyBody')">
        <ElButton type="primary" :icon="Plus" @click="openCreate">{{
          t('products.addFamily')
        }}</ElButton>
      </EmptyState>
      <ElPagination
        v-if="page.totalPages > 1"
        v-model:current-page="currentPage"
        v-model:page-size="pageSize"
        class="table-pagination"
        background
        layout="prev, pager, next"
        :total="page.totalElements"
        @current-change="load"
      />
    </section>

    <ElDialog
      v-model="dialogOpen"
      class="family-dialog"
      :title="dialogTitle"
      width="min(840px, 96vw)"
      destroy-on-close
    >
      <ElForm label-position="top" class="family-editor">
        <section class="family-editor__common">
          <div class="editor-section-heading">
            <div>
              <span>{{ t('products.commonInformation') }}</span>
              <h3>{{ t('products.artworkDetails') }}</h3>
            </div>
            <small v-if="editingFamilyId">{{ t('products.sharedChangeHint') }}</small>
          </div>
          <div class="form-grid">
            <ElFormItem class="span-2" :label="t('products.productName')" required>
              <ElInput v-model="form.name" />
            </ElFormItem>
            <ElFormItem :label="t('products.category')"
              ><ElInput v-model="form.category"
            /></ElFormItem>
            <ElFormItem :label="t('products.artistName')"
              ><ElInput v-model="form.artistName"
            /></ElFormItem>
            <ElFormItem class="span-2" :label="t('products.description')">
              <ElInput v-model="form.description" type="textarea" :rows="3" />
            </ElFormItem>
            <ElFormItem class="span-2" :label="t('products.image')">
              <ElUpload
                :auto-upload="false"
                :limit="1"
                accept="image/jpeg,image/png,image/webp"
                :on-change="selectImage"
              >
                <ElButton>{{ t('common.upload') }}</ElButton>
                <template #tip
                  ><div class="el-upload__tip">{{ t('products.imageHint') }}</div></template
                >
              </ElUpload>
            </ElFormItem>
          </div>
        </section>

        <section class="family-editor__variants">
          <div class="editor-section-heading">
            <div>
              <span>{{ t('products.stockIdentity') }}</span>
              <h3>{{ t('products.variants') }}</h3>
            </div>
            <ElButton :icon="Plus" @click="addVariant">{{ t('products.addVariant') }}</ElButton>
          </div>
          <div class="variant-editor-list">
            <article
              v-for="(variant, index) in form.variants"
              :key="variant.id || index"
              class="variant-editor-row"
            >
              <div class="variant-editor-row__index">{{ String(index + 1).padStart(2, '0') }}</div>
              <ElFormItem :label="t('products.variantName')" required>
                <ElInput
                  v-model="variant.variantName"
                  :placeholder="t('products.variantExample')"
                />
              </ElFormItem>
              <ElFormItem :label="t('products.sku')" required
                ><ElInput v-model="variant.sku"
              /></ElFormItem>
              <ElFormItem v-if="!variant.id" :label="t('products.initialStock')">
                <ElInputNumber
                  v-model="variant.initialStock"
                  :min="0"
                  :precision="0"
                  controls-position="right"
                />
              </ElFormItem>
              <ElFormItem :label="t('products.threshold')">
                <ElInputNumber
                  v-model="variant.lowStockThreshold"
                  :min="0"
                  :precision="0"
                  controls-position="right"
                />
              </ElFormItem>
              <ElFormItem v-if="variant.id" :label="t('products.activity')">
                <ElSwitch v-model="variant.enabled" />
              </ElFormItem>
              <ElButton
                v-if="!variant.id && form.variants.length > 1"
                class="variant-editor-row__remove"
                text
                type="danger"
                :icon="Delete"
                :aria-label="t('common.delete')"
                @click="removeVariant(index)"
              />
            </article>
          </div>
        </section>
      </ElForm>
      <template #footer>
        <ElButton @click="dialogOpen = false">{{ t('common.cancel') }}</ElButton>
        <ElButton type="primary" :loading="saving" @click="save">{{
          saving ? t('common.saving') : t('common.save')
        }}</ElButton>
      </template>
    </ElDialog>
  </div>
</template>
