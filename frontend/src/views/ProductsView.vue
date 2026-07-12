<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import axios from 'axios'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox, type UploadFile } from 'element-plus'
import { Delete, Edit, Plus, Search } from '@element-plus/icons-vue'
import { api, resolveApiUrl } from '@/services/api'
import { normalizePage } from '@/services/paging'
import { useApiFeedback } from '@/composables/useApiFeedback'
import { useFormatters } from '@/composables/useFormatters'
import type { PageResponse, Product } from '@/types/api'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'

const { t } = useI18n()
const { showError } = useApiFeedback()
const { money, number, date } = useFormatters()
const loading = ref(false)
const saving = ref(false)
const dialogOpen = ref(false)
const search = ref('')
const currentPage = ref(1)
const pageSize = ref(20)
const page = ref<PageResponse<Product>>(normalizePage([]))
const imageFile = ref<File>()
const editingId = ref<string>()
const emptyForm = () => ({
  sku: '',
  name: '',
  category: '',
  artistName: '',
  description: '',
  salePrice: 0,
  costPrice: 0,
  currency: 'EUR',
  initialStock: 0,
  lowStockThreshold: 0,
  enabled: true,
})
const form = reactive(emptyForm())

async function load() {
  loading.value = true
  try {
    const { data } = await api.get<PageResponse<Product> | Product[]>('/products', {
      params: { page: currentPage.value - 1, size: pageSize.value, q: search.value || undefined },
    })
    page.value = normalizePage(data, currentPage.value - 1, pageSize.value)
  } catch (error) {
    showError(error)
  } finally {
    loading.value = false
  }
}

function reloadFirstPage() {
  currentPage.value = 1
  void load()
}

function openCreate() {
  editingId.value = undefined
  Object.assign(form, emptyForm())
  imageFile.value = undefined
  dialogOpen.value = true
}

function openEdit(product: Product) {
  editingId.value = product.id
  Object.assign(form, { ...emptyForm(), ...product, initialStock: 0 })
  imageFile.value = undefined
  dialogOpen.value = true
}

function selectImage(uploadFile: UploadFile) {
  imageFile.value = uploadFile.raw
}

async function uploadImage(productId: string, file: File) {
  const digest = await crypto.subtle.digest('SHA-256', await file.arrayBuffer())
  const checksumSha256 = Array.from(new Uint8Array(digest), (byte) =>
    byte.toString(16).padStart(2, '0'),
  ).join('')
  const { data } = await api.post<{
    uploadUrl: string
    fileId: string
    headers?: Record<string, string>
  }>('/files/presign', {
    originalFilename: file.name,
    contentType: file.type,
    size: file.size,
    checksumSha256,
    productId,
  })
  await axios.put(resolveApiUrl(data.uploadUrl), file, {
    headers: { 'Content-Type': file.type, ...data.headers },
  })
  await api.post(`/files/${data.fileId}/confirm`)
}

async function save() {
  if (!form.sku.trim() || !form.name.trim() || form.salePrice < 0) {
    ElMessage.warning(t('errors.validation'))
    return
  }
  saving.value = true
  try {
    const payload = {
      ...form,
      sku: form.sku.trim(),
      name: form.name.trim(),
      category: form.category || null,
      artistName: form.artistName || null,
      description: form.description || null,
    }
    const { data } = editingId.value
      ? await api.put<Product>(`/products/${editingId.value}`, payload)
      : await api.post<Product>('/products', payload)
    if (imageFile.value) await uploadImage(data.id, imageFile.value)
    ElMessage.success(t(editingId.value ? 'products.saved' : 'products.created'))
    dialogOpen.value = false
    await load()
  } catch (error) {
    showError(error)
  } finally {
    saving.value = false
  }
}

async function remove(product: Product) {
  try {
    await ElMessageBox.confirm(
      t('products.deleteBody', { name: product.name }),
      t('products.deleteTitle'),
      {
        confirmButtonText: t('common.delete'),
        cancelButtonText: t('common.cancel'),
        type: 'warning',
      },
    )
    await api.delete(`/products/${product.id}`)
    ElMessage.success(t('products.deleted'))
    await load()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') showError(error)
  }
}

onMounted(load)
</script>

<template>
  <div class="page-stack">
    <PageHeader
      :eyebrow="t('products.eyebrow')"
      :title="t('products.title')"
      :subtitle="t('products.subtitle')"
    >
      <template #actions
        ><ElButton type="primary" :icon="Plus" @click="openCreate">{{
          t('products.addProduct')
        }}</ElButton></template
      >
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
      >
        <ElTableColumn width="72"
          ><template #default="scope"
            ><div class="product-thumb">
              <img v-if="scope.row.imageUrl" :src="scope.row.imageUrl" :alt="scope.row.name" /><span
                v-else
                >{{ scope.row.name.slice(0, 1).toUpperCase() }}</span
              >
            </div></template
          ></ElTableColumn
        >
        <ElTableColumn prop="sku" :label="t('products.sku')" min-width="120"
          ><template #default="scope"
            ><code class="sku-code">{{ scope.row.sku }}</code></template
          ></ElTableColumn
        >
        <ElTableColumn prop="name" :label="t('products.productName')" min-width="220" />
        <ElTableColumn :label="t('products.price')" align="right" min-width="130"
          ><template #default="scope">{{
            money(scope.row.salePrice, scope.row.currency)
          }}</template></ElTableColumn
        >
        <ElTableColumn :label="t('products.stock')" align="right" min-width="110"
          ><template #default="scope"
            ><strong
              :class="{ 'stock-low': scope.row.currentStock <= (scope.row.lowStockThreshold || 0) }"
              >{{ number(scope.row.currentStock) }}</strong
            ></template
          ></ElTableColumn
        >
        <ElTableColumn :label="t('products.salesHistory')" min-width="190"
          ><template #default="scope"
            ><div class="cell-stack">
              <strong>{{
                t('products.unitsSoldValue', { count: number(scope.row.totalUnitsSold || 0) })
              }}</strong
              ><small
                >{{ money(scope.row.totalSalesRevenue || 0, scope.row.currency) }} ·
                {{
                  scope.row.lastSaleAt ? date(scope.row.lastSaleAt) : t('products.neverSold')
                }}</small
              >
            </div></template
          ></ElTableColumn
        >
        <ElTableColumn :label="t('products.activity')" min-width="110"
          ><template #default="scope"
            ><span class="availability" :data-active="scope.row.enabled"
              ><i />{{ t(scope.row.enabled ? 'common.active' : 'common.inactive') }}</span
            ></template
          ></ElTableColumn
        >
        <ElTableColumn :label="t('common.actions')" width="112" fixed="right"
          ><template #default="scope"
            ><ElButton
              text
              :icon="Edit"
              :aria-label="t('common.edit')"
              @click="openEdit(scope.row)" /><ElButton
              text
              type="danger"
              :icon="Delete"
              :aria-label="t('common.delete')"
              @click="remove(scope.row)" /></template
        ></ElTableColumn>
      </ElTable>
      <EmptyState v-else :title="t('products.emptyTitle')" :body="t('products.emptyBody')"
        ><ElButton type="primary" :icon="Plus" @click="openCreate">{{
          t('products.addProduct')
        }}</ElButton></EmptyState
      >
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
      :title="t(editingId ? 'products.editProduct' : 'products.createProduct')"
      width="min(680px, 94vw)"
      destroy-on-close
    >
      <ElForm label-position="top" class="form-grid">
        <ElFormItem :label="t('products.sku')" required><ElInput v-model="form.sku" /></ElFormItem>
        <ElFormItem :label="t('products.productName')" required
          ><ElInput v-model="form.name"
        /></ElFormItem>
        <ElFormItem :label="t('products.category')"><ElInput v-model="form.category" /></ElFormItem>
        <ElFormItem :label="t('products.artistName')"
          ><ElInput v-model="form.artistName"
        /></ElFormItem>
        <ElFormItem class="span-2" :label="t('products.description')"
          ><ElInput v-model="form.description" type="textarea" :rows="3"
        /></ElFormItem>
        <ElFormItem :label="t('products.price')" required
          ><ElInputNumber
            v-model="form.salePrice"
            :min="0"
            :precision="2"
            controls-position="right"
        /></ElFormItem>
        <ElFormItem :label="t('products.costPrice')"
          ><ElInputNumber
            v-model="form.costPrice"
            :min="0"
            :precision="2"
            controls-position="right"
        /></ElFormItem>
        <ElFormItem :label="t('products.currency')"
          ><ElSelect v-model="form.currency"
            ><ElOption label="EUR" value="EUR" /><ElOption label="USD" value="USD" /><ElOption
              label="GBP"
              value="GBP" /></ElSelect
        ></ElFormItem>
        <ElFormItem :label="t('products.threshold')"
          ><ElInputNumber
            v-model="form.lowStockThreshold"
            :min="0"
            :precision="0"
            controls-position="right"
        /></ElFormItem>
        <ElFormItem v-if="!editingId" :label="t('products.stock')"
          ><ElInputNumber
            v-model="form.initialStock"
            :min="0"
            :precision="0"
            controls-position="right"
        /></ElFormItem>
        <ElFormItem :label="t('products.activity')"><ElSwitch v-model="form.enabled" /></ElFormItem>
        <ElFormItem class="span-2" :label="t('products.image')"
          ><ElUpload
            :auto-upload="false"
            :limit="1"
            accept="image/jpeg,image/png,image/webp"
            :on-change="selectImage"
            ><ElButton>{{ t('common.upload') }}</ElButton
            ><template #tip
              ><div class="el-upload__tip">{{ t('products.imageHint') }}</div></template
            ></ElUpload
          ></ElFormItem
        >
      </ElForm>
      <template #footer
        ><ElButton @click="dialogOpen = false">{{ t('common.cancel') }}</ElButton
        ><ElButton type="primary" :loading="saving" @click="save">{{
          saving ? t('common.saving') : t('common.save')
        }}</ElButton></template
      >
    </ElDialog>
  </div>
</template>
