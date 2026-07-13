<script setup lang="ts">
import type { Product } from '@/types/api'
import SecureImage from '@/components/SecureImage.vue'

defineProps<{ product: Product; compact?: boolean }>()

function displayName(product: Product) {
  return product.variantName ? `${product.name} · ${product.variantName}` : product.name
}
</script>

<template>
  <span class="product-option" :class="{ 'is-compact': compact }">
    <span class="product-option__thumb" aria-hidden="true">
      <SecureImage :src="product.imageUrl" alt="">
        <span>{{ displayName(product).trim().slice(0, 1).toUpperCase() }}</span>
      </SecureImage>
    </span>
    <span class="product-option__copy">
      <strong>{{ displayName(product) }}</strong>
      <small>{{ product.sku }} · {{ product.currentStock }}</small>
    </span>
  </span>
</template>
