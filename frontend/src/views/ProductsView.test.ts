import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia } from 'pinia'

const mocks = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  delete: vi.fn(),
}))

vi.mock('@/services/api', () => ({
  api: mocks,
  resolveApiUrl: (path: string) => path,
  configureSessionHandlers: vi.fn(),
  refreshSession: vi.fn(),
  setAccessToken: vi.fn(),
  warmBackend: vi.fn(),
}))

import { i18n, setAppLocale } from '@/i18n'
import ProductsView from './ProductsView.vue'

describe('ProductsView filters', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    setAppLocale('en')
    mocks.get.mockImplementation((url: string) => {
      if (url === '/products/categories') return Promise.resolve({ data: ['Print', 'Sculpture'] })
      return Promise.resolve({
        data: { items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 },
      })
    })
  })

  it('combines stock status and multiple categories in the product request', async () => {
    const wrapper = mount(ProductsView, {
      global: {
        plugins: [createPinia(), i18n, ElementPlus],
        stubs: { PageHeader: true, EmptyState: true },
      },
    })
    await flushPromises()

    const filters = wrapper.findAllComponents({ name: 'ElSelect' })
    expect(filters).toHaveLength(2)
    filters[0].vm.$emit('update:modelValue', 'LOW')
    filters[0].vm.$emit('change', 'LOW')
    filters[1].vm.$emit('update:modelValue', ['Print', 'Sculpture'])
    filters[1].vm.$emit('change', ['Print', 'Sculpture'])
    await flushPromises()

    const productCalls = mocks.get.mock.calls.filter(([url]) => url === '/products')
    const params = productCalls.at(-1)?.[1].params as URLSearchParams
    expect(params.get('lowStock')).toBe('true')
    expect(params.getAll('categories')).toEqual(['Print', 'Sculpture'])
  })
})
