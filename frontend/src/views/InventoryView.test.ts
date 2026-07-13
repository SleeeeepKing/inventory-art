import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { ElMessageBox } from 'element-plus'
import { createPinia } from 'pinia'
import { readFileSync } from 'node:fs'

const mocks = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn() }))

vi.mock('@/services/api', () => ({
  api: { get: mocks.get, post: mocks.post, put: mocks.put },
  configureSessionHandlers: vi.fn(),
  refreshSession: vi.fn(),
  setAccessToken: vi.fn(),
  warmBackend: vi.fn(),
}))

import { i18n, setAppLocale } from '@/i18n'
import InventoryView from './InventoryView.vue'

describe('InventoryView product identity', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    setAppLocale('en')
    Object.defineProperty(URL, 'createObjectURL', {
      configurable: true,
      value: vi.fn(() => 'blob:secure-preview'),
    })
    Object.defineProperty(URL, 'revokeObjectURL', { configurable: true, value: vi.fn() })
    mocks.get.mockImplementation((url: string) => {
      if (url === '/inventory/operations') {
        return Promise.resolve({
          data: {
            items: [
              {
                id: 'movement-1',
                kind: 'MOVEMENT',
                type: 'INITIAL',
                quantity: 2,
                stockBefore: 0,
                stockAfter: 2,
                createdAt: '2026-07-13T10:00:00Z',
                updatedAt: '2026-07-13T10:00:00Z',
                version: 0,
                items: [
                  {
                    productId: 'product-1',
                    productName: 'Blue Horizon',
                    productSku: 'ART-001',
                    productImageUrl: '/files/file-1/preview',
                    currentStock: 2,
                    quantity: 2,
                  },
                ],
              },
            ],
            page: 0,
            size: 20,
            totalElements: 1,
            totalPages: 1,
          },
        })
      }
      if (url === '/products') {
        return Promise.resolve({
          data: { items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 },
        })
      }
      if (url === '/files/file-1/preview') {
        return Promise.resolve({ data: new Blob(['preview'], { type: 'image/webp' }) })
      }
      return Promise.resolve({ data: [] })
    })
    mocks.post.mockResolvedValue({ data: {} })
    mocks.put.mockResolvedValue({ data: {} })
  })

  afterEach(() => {
    document.body.innerHTML = ''
    vi.restoreAllMocks()
  })

  it('shows the movement product image, name, and SKU', async () => {
    const wrapper = mount(InventoryView, {
      global: {
        plugins: [createPinia(), i18n, ElementPlus],
        stubs: { PageHeader: true, EmptyState: true },
      },
    })
    await flushPromises()

    const product = wrapper.get('.inventory-product-cell')
    expect(product.get('img').attributes('src')).toBe('blob:secure-preview')
    expect(product.text()).toContain('Blue Horizon')
    expect(product.text()).toContain('ART-001')
    expect(wrapper.find('.el-table__expand-icon').exists()).toBe(false)
  })

  it('shows a named details action only for multi-product sales', async () => {
    mocks.get.mockImplementation((url: string) => {
      if (url === '/inventory/operations') {
        return Promise.resolve({
          data: {
            items: [
              {
                id: 'sale-multi',
                kind: 'SALE',
                type: 'SALE',
                quantity: -3,
                createdAt: '2026-07-13T10:00:00Z',
                updatedAt: '2026-07-13T10:00:00Z',
                version: 0,
                items: [
                  {
                    productId: 'product-1',
                    productName: 'Blue Horizon · A4',
                    productSku: 'ART-A4',
                    currentStock: 2,
                    quantity: 1,
                  },
                  {
                    productId: 'product-2',
                    productName: 'Blue Horizon · A3',
                    productSku: 'ART-A3',
                    currentStock: 1,
                    quantity: 2,
                  },
                ],
              },
            ],
            page: 0,
            size: 20,
            totalElements: 1,
            totalPages: 1,
          },
        })
      }
      if (url === '/products') {
        return Promise.resolve({
          data: { items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 },
        })
      }
      return Promise.resolve({ data: [] })
    })
    const wrapper = mount(InventoryView, {
      global: {
        plugins: [createPinia(), i18n, ElementPlus],
        stubs: { PageHeader: true, EmptyState: true },
      },
    })
    await flushPromises()

    expect(wrapper.get('.sale-items-trigger').text()).toBe('View 2 products')
    expect(wrapper.find('.el-table__expand-icon').exists()).toBe(false)
  })

  it('uses the same image-aware selector for stock-in, correction, and sold quantities', () => {
    const source = readFileSync('src/views/InventoryView.vue', 'utf8')
    expect(source.match(/<ProductSelect\b/g)).toHaveLength(3)
  })

  it('loads an editable sale and submits its final state, then can undo it by version', async () => {
    const saleOperation = {
      id: 'sale-1',
      kind: 'SALE',
      type: 'SALE',
      quantity: -2,
      eventId: 'event-1',
      eventName: 'Paris Expo',
      attributedDate: '2026-07-12',
      status: 'ACTIVE',
      createdAt: '2026-07-12T10:00:00Z',
      updatedAt: '2026-07-12T10:00:00Z',
      version: 3,
      items: [
        {
          productId: 'product-1',
          productName: 'Blue Horizon',
          productSku: 'ART-001',
          productImageUrl: '/files/file-1/preview',
          currentStock: 5,
          quantity: 2,
        },
      ],
    }
    mocks.get.mockImplementation((url: string) => {
      if (url === '/inventory/operations') {
        return Promise.resolve({
          data: { items: [saleOperation], page: 0, size: 20, totalElements: 1, totalPages: 1 },
        })
      }
      if (url === '/products') {
        return Promise.resolve({
          data: {
            items: [
              {
                id: 'product-1',
                name: 'Blue Horizon',
                sku: 'ART-001',
                currentStock: 5,
                enabled: false,
                imageUrl: '/files/file-1/preview',
              },
            ],
            page: 0,
            size: 20,
            totalElements: 1,
            totalPages: 1,
          },
        })
      }
      if (url === '/sales-events') {
        return Promise.resolve({
          data: [
            {
              id: 'event-1',
              name: 'Paris Expo',
              startDate: '2026-07-10',
              endDate: '2026-07-12',
              enabled: false,
            },
          ],
        })
      }
      if (url === '/products/categories') return Promise.resolve({ data: ['Print'] })
      if (url === '/inventory/sales/sale-1') {
        return Promise.resolve({
          data: {
            id: 'sale-1',
            eventId: 'event-1',
            eventName: 'Paris Expo',
            attributedDate: '2026-07-12',
            status: 'ACTIVE',
            version: 3,
            createdAt: '2026-07-12T10:00:00Z',
            updatedAt: '2026-07-12T10:00:00Z',
            items: saleOperation.items,
          },
        })
      }
      if (url === '/files/file-1/preview') {
        return Promise.resolve({ data: new Blob(['preview'], { type: 'image/webp' }) })
      }
      return Promise.resolve({ data: [] })
    })
    const wrapper = mount(InventoryView, {
      attachTo: document.body,
      global: {
        plugins: [createPinia(), i18n, ElementPlus],
        stubs: { PageHeader: true, EmptyState: true },
      },
    })
    await flushPromises()

    await wrapper.get('button[aria-label="Edit"]').trigger('click')
    await flushPromises()
    expect(mocks.get).toHaveBeenCalledWith('/inventory/sales/sale-1')
    expect(document.body.textContent).toContain('Edit sold quantities')
    expect(document.body.textContent).toContain('Blue Horizon')

    const setup = (
      wrapper.vm as unknown as { $: { setupState: Record<string, () => Promise<void>> } }
    ).$?.setupState
    await setup.recordSale()
    await flushPromises()
    expect(mocks.put).toHaveBeenCalledWith('/inventory/sales/sale-1', {
      eventId: 'event-1',
      items: [{ productId: 'product-1', quantity: 2 }],
      version: 3,
    })

    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    await wrapper.get('button[aria-label="Undo sale"]').trigger('click')
    await flushPromises()
    expect(mocks.post).toHaveBeenCalledWith('/inventory/sales/sale-1/cancel', { version: 3 })
  })
})
