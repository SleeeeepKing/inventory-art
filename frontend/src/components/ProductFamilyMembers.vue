<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { useFormatters } from '@/composables/useFormatters'
import type { ProductVariant } from '@/types/api'

defineProps<{ variants: ProductVariant[] }>()

const { t } = useI18n()
const { number, date } = useFormatters()
</script>

<template>
  <div class="variant-ledger">
    <div class="variant-ledger__head">
      <span>{{ t('products.productIdentity') }}</span>
      <span>{{ t('products.stock') }}</span>
      <span>{{ t('products.salesHistory') }}</span>
    </div>
    <div
      v-for="variant in variants"
      :key="variant.id"
      class="variant-ledger__row"
      :data-active="variant.enabled"
    >
      <div class="variant-ledger__identity">
        <strong>{{ variant.variantName || t('products.legacyVariant') }}</strong>
        <code>{{ variant.sku }}</code>
      </div>
      <strong class="variant-ledger__stock">{{ number(variant.currentStock) }}</strong>
      <div class="variant-ledger__sales">
        <strong>{{
          t('products.unitsSoldValue', {
            count: number(variant.totalUnitsSold || 0),
          })
        }}</strong>
        <small>{{
          variant.lastSaleDate ? date(variant.lastSaleDate) : t('products.neverSold')
        }}</small>
      </div>
    </div>
  </div>
</template>
