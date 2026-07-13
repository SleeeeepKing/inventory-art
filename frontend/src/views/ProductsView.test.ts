import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
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

describe('ProductsView families', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    setAppLocale('en')
    mocks.get.mockImplementation((path: string) =>
      Promise.resolve({
        data:
          path === '/products/categories'
            ? []
            : { items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 },
      }),
    )
    mocks.post.mockResolvedValue({
      data: {
        id: 'family-1',
        name: 'Blue Garden',
        version: 0,
        createdAt: '2026-07-13T10:00:00Z',
        updatedAt: '2026-07-13T10:00:00Z',
        variants: [],
      },
    })
  })

  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('loads the grouped family catalogue', async () => {
    mount(ProductsView, {
      global: { plugins: [createPinia(), i18n, ElementPlus], stubs: { EmptyState: true } },
    })
    await flushPromises()

    expect(mocks.get).toHaveBeenCalledWith('/product-families', {
      params: {
        page: 0,
        size: 20,
        q: undefined,
        lowStock: undefined,
        categories: undefined,
      },
    })
  })

  it('combines stock status and categories in the family request', async () => {
    const wrapper = mount(ProductsView, {
      global: { plugins: [createPinia(), i18n, ElementPlus], stubs: { EmptyState: true } },
    })
    await flushPromises()

    const filters = wrapper.findAllComponents({ name: 'ElSelect' })
    expect(filters).toHaveLength(2)
    filters[0].vm.$emit('update:modelValue', 'LOW')
    filters[0].vm.$emit('change', 'LOW')
    filters[1].vm.$emit('update:modelValue', ['Print', 'Sculpture'])
    filters[1].vm.$emit('change', ['Print', 'Sculpture'])
    await flushPromises()

    const familyCalls = mocks.get.mock.calls.filter(([url]) => url === '/product-families')
    expect(familyCalls.at(-1)?.[1].params).toMatchObject({
      lowStock: true,
      categories: ['Print', 'Sculpture'],
    })
  })

  it('creates an artwork with server-aligned stock defaults in one request', async () => {
    const wrapper = mount(ProductsView, {
      attachTo: document.body,
      global: { plugins: [createPinia(), i18n, ElementPlus], stubs: { EmptyState: true } },
    })
    await flushPromises()

    await wrapper
      .findAll('button')
      .find((button) => button.text().includes('Add artwork'))!
      .trigger('click')
    await flushPromises()

    const numberInputs = wrapper.findAllComponents({ name: 'ElInputNumber' })
    expect(numberInputs[0].props('modelValue')).toBe(999)
    expect(numberInputs[1].props('modelValue')).toBe(5)

    const inputs = wrapper.findAllComponents({ name: 'ElInput' })
    inputs[1].vm.$emit('update:modelValue', 'Blue Garden')
    inputs[5].vm.$emit('update:modelValue', 'A4')
    inputs[6].vm.$emit('update:modelValue', 'BLUE-A4')
    await flushPromises()

    const saveButton = wrapper
      .findAllComponents({ name: 'ElButton' })
      .find((button) => button.text().includes('Save'))
    expect(saveButton).toBeTruthy()
    await saveButton!.trigger('click')
    await flushPromises()

    expect(mocks.post).toHaveBeenCalledTimes(1)
    expect(mocks.post).toHaveBeenCalledWith('/product-families', {
      name: 'Blue Garden',
      category: null,
      artistName: null,
      description: null,
      variants: [
        {
          variantName: 'A4',
          sku: 'BLUE-A4',
          initialStock: 999,
          lowStockThreshold: 5,
          enabled: true,
        },
      ],
    })
  })
})
