import type { Product } from '@/types/api'

export function mergeProductSearch(
  current: Product[],
  found: Product[],
  selectedIds: Iterable<string>,
) {
  const selected = new Set(selectedIds)
  const retained = current.filter((product) => selected.has(product.id))
  return [
    ...retained,
    ...found.filter((product) => !retained.some((item) => item.id === product.id)),
  ]
}
