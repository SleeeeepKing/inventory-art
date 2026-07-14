import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
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
        enabled: true,
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

  it('shows stock and sales for each product without family totals', async () => {
    mocks.get.mockImplementation((path: string) =>
      Promise.resolve({
        data:
          path === '/products/categories'
            ? []
            : {
                items: [
                  {
                    id: 'family-1',
                    name: 'Blue Garden',
                    version: 0,
                    createdAt: '2026-07-13T10:00:00Z',
                    updatedAt: '2026-07-13T10:00:00Z',
                    variants: [
                      {
                        id: 'variant-a4',
                        variantName: 'A4',
                        sku: 'BLUE-A4',
                        currentStock: 7,
                        lowStockThreshold: 5,
                        enabled: true,
                        totalUnitsSold: 3,
                        lastSaleDate: '2026-07-12',
                        version: 0,
                        createdAt: '2026-07-13T10:00:00Z',
                        updatedAt: '2026-07-13T10:00:00Z',
                      },
                      {
                        id: 'variant-a5',
                        variantName: 'A5',
                        sku: 'BLUE-A5',
                        currentStock: 11,
                        lowStockThreshold: 5,
                        enabled: true,
                        totalUnitsSold: 5,
                        lastSaleDate: '2026-07-11',
                        version: 0,
                        createdAt: '2026-07-13T10:00:00Z',
                        updatedAt: '2026-07-13T10:00:00Z',
                      },
                    ],
                  },
                ],
                page: 0,
                size: 20,
                totalElements: 1,
                totalPages: 1,
              },
      }),
    )

    const wrapper = mount(ProductsView, {
      global: { plugins: [createPinia(), i18n, ElementPlus], stubs: { EmptyState: true } },
    })
    await flushPromises()

    const memberRows = wrapper.findAll('.family-table .variant-ledger__row')
    const mobileMemberRows = wrapper.findAll('.family-card-list .variant-ledger__row')
    expect(memberRows).toHaveLength(2)
    expect(mobileMemberRows).toHaveLength(2)
    expect(memberRows[0].text()).toContain('A4')
    expect(memberRows[0].text()).toContain('BLUE-A4')
    expect(memberRows[0].find('.variant-ledger__stock').text()).toBe('7')
    expect(memberRows[0].find('.variant-ledger__sales strong').text()).toBe('3 sold')
    expect(memberRows[1].text()).toContain('A5')
    expect(memberRows[1].text()).toContain('BLUE-A5')
    expect(memberRows[1].find('.variant-ledger__stock').text()).toBe('11')
    expect(memberRows[1].find('.variant-ledger__sales strong').text()).toBe('5 sold')
    expect(wrapper.text()).not.toContain('18')
    expect(wrapper.text()).not.toContain('8 sold')
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

  it('removes a whole artwork from the active catalogue after confirmation', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue({ action: 'confirm' } as never)
    mocks.delete.mockResolvedValue({})
    let familyLoads = 0
    mocks.get.mockImplementation((path: string) => {
      if (path === '/products/categories') return Promise.resolve({ data: [] })
      familyLoads += 1
      return Promise.resolve({
        data:
          familyLoads === 1
            ? {
                items: [
                  {
                    id: 'family-1',
                    name: 'Blue Garden',
                    version: 0,
                    createdAt: '2026-07-13T10:00:00Z',
                    updatedAt: '2026-07-13T10:00:00Z',
                    variants: [
                      {
                        id: 'variant-1',
                        variantName: 'A4',
                        sku: 'BLUE-A4',
                        currentStock: 999,
                        lowStockThreshold: 5,
                        enabled: true,
                        totalUnitsSold: 0,
                        version: 0,
                        createdAt: '2026-07-13T10:00:00Z',
                        updatedAt: '2026-07-13T10:00:00Z',
                      },
                    ],
                  },
                ],
                page: 0,
                size: 20,
                totalElements: 1,
                totalPages: 1,
              }
            : { items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 },
      })
    })
    const wrapper = mount(ProductsView, {
      attachTo: document.body,
      global: { plugins: [createPinia(), i18n, ElementPlus], stubs: { EmptyState: true } },
    })
    await flushPromises()

    await wrapper.find('button[aria-label="Delete"]').trigger('click')
    await flushPromises()

    expect(ElMessageBox.confirm).toHaveBeenCalledWith(
      expect.stringContaining('Blue Garden'),
      'Remove artwork?',
      expect.objectContaining({ confirmButtonText: 'Delete' }),
    )
    expect(mocks.delete).toHaveBeenCalledWith('/product-families/family-1')
  })
})
