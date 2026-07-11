<script setup lang="ts">
defineProps<{ label: string; value: string; note?: string; tone?: 'default' | 'accent' | 'warning' }>()
</script>

<template>
  <article class="metric-card" :data-tone="tone || 'default'">
    <p>{{ label }}</p>
    <strong>{{ value }}</strong>
    <small v-if="note">{{ note }}</small>
  </article>
</template>
