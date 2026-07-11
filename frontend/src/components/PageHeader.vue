<script setup lang="ts">
defineProps<{ eyebrow: string; title: string; subtitle: string }>()
</script>

<template>
  <header class="page-header">
    <div class="page-header__copy">
      <p class="eyebrow">{{ eyebrow }}</p>
      <h1>{{ title }}</h1>
      <p class="page-header__subtitle">{{ subtitle }}</p>
    </div>
    <div v-if="$slots.actions" class="page-header__actions"><slot name="actions" /></div>
  </header>
</template>
