import type { PageResponse } from '@/types/api'

export function normalizePage<T>(
  value: PageResponse<T> | T[],
  page = 0,
  size = 20,
): PageResponse<T> {
  if (Array.isArray(value)) {
    return {
      items: value,
      page,
      size,
      totalElements: value.length,
      totalPages: value.length ? 1 : 0,
    }
  }
  return {
    items: value.items || [],
    page: value.page ?? page,
    size: value.size ?? size,
    totalElements: value.totalElements ?? value.items?.length ?? 0,
    totalPages: value.totalPages ?? 0,
    sort: value.sort,
  }
}
