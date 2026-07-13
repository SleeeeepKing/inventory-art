<script setup lang="ts">
import type { Product } from '@/types/api'
import ProductOptionContent from '@/components/ProductOptionContent.vue'

const props = withDefaults(
  defineProps<{
    modelValue: string
    products: Product[]
    placeholder: string
    disabledProductIds?: string[]
    remoteMethod?: (query: string) => void
  }>(),
  { disabledProductIds: () => [], remoteMethod: undefined },
)

const emit = defineEmits<{
  'update:modelValue': [value: string]
  change: [value: string]
}>()

function productFor(id: string) {
  return props.products.find((product) => product.id === id)
}
</script>

<template>
  <ElSelect
    :model-value="modelValue"
    filterable
    remote
    reserve-keyword
    :remote-method="remoteMethod"
    :placeholder="placeholder"
    @update:model-value="emit('update:modelValue', String($event))"
    @change="emit('change', String($event))"
  >
    <template #label="{ value }">
      <ProductOptionContent
        v-if="productFor(String(value))"
        :product="productFor(String(value))!"
        compact
      />
    </template>
    <ElOption
      v-for="product in products"
      :key="product.id"
      :label="`${product.name} · ${product.sku} (${product.currentStock})`"
      :value="product.id"
      :disabled="disabledProductIds.includes(product.id)"
    >
      <ProductOptionContent :product="product" />
    </ElOption>
  </ElSelect>
</template>
