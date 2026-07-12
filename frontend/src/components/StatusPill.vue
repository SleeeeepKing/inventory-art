<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

const props = defineProps<{ status: string }>()
const { t, te } = useI18n()
const label = computed(() =>
  te(`status.${props.status}`) ? t(`status.${props.status}`) : props.status.replaceAll('_', ' '),
)
const tone = computed(() => {
  if (['COMPLETED', 'ENABLED', 'CONFIRMED', 'READY'].includes(props.status)) return 'success'
  if (['FAILED', 'CANCELLED', 'DISABLED'].includes(props.status)) return 'danger'
  if (['ANALYZING', 'IMPORTING', 'UPLOADED', 'DRAFT'].includes(props.status)) return 'info'
  if (['PARTIALLY_REFUNDED', 'COMPLETED_WITH_ERRORS', 'UNALLOCATED'].includes(props.status))
    return 'warning'
  return 'neutral'
})
</script>

<template>
  <span class="status-pill" :data-tone="tone"><i />{{ label }}</span>
</template>
