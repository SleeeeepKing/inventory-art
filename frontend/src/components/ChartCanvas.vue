<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts/core'
import { LineChart, BarChart, PieChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import type { EChartsCoreOption } from 'echarts/core'

echarts.use([
  LineChart,
  BarChart,
  PieChart,
  GridComponent,
  LegendComponent,
  TooltipComponent,
  CanvasRenderer,
])
const props = defineProps<{ option: EChartsCoreOption }>()
const host = ref<HTMLElement>()
let chart: ReturnType<typeof echarts.init> | undefined
let observer: ResizeObserver | undefined

onMounted(() => {
  if (!host.value) return
  chart = echarts.init(host.value)
  chart.setOption(props.option)
  observer = new ResizeObserver(() => chart?.resize())
  observer.observe(host.value)
})
watch(
  () => props.option,
  (option) => chart?.setOption(option, true),
  { deep: true },
)
onBeforeUnmount(() => {
  observer?.disconnect()
  chart?.dispose()
})
</script>

<template><div ref="host" class="chart-canvas" /></template>
