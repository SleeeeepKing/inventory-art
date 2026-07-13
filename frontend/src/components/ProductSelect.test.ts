import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'

const mocks = vi.hoisted(() => ({ get: vi.fn() }))

vi.mock('@/services/api', () => ({ api: { get: mocks.get } }))

import ProductSelect from './ProductSelect.vue'

const product = {
  id: 'product-1',
  sku: 'ART-001',
  name: 'Blue Horizon',
  currentStock: 7,
  enabled: true,
  imageUrl: '/files/file-1/preview',
}

describe('ProductSelect', () => {
  beforeEach(() => {
    mocks.get.mockResolvedValue({ data: new Blob(['preview'], { type: 'image/webp' }) })
    Object.defineProperty(URL, 'createObjectURL', {
      configurable: true,
      value: vi.fn(() => 'blob:secure-preview'),
    })
    Object.defineProperty(URL, 'revokeObjectURL', { configurable: true, value: vi.fn() })
  })

  afterEach(() => {
    document.body.innerHTML = ''
    vi.clearAllMocks()
  })

  it('shows a secure image in both the selected value and dropdown option', async () => {
    const wrapper = mount(ProductSelect, {
      attachTo: document.body,
      props: {
        modelValue: product.id,
        products: [product],
        placeholder: 'Select a product',
      },
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()

    const selected = wrapper.get('.product-option.is-compact')
    expect(selected.get('img').attributes('src')).toBe('blob:secure-preview')
    expect(selected.text()).toContain('Blue Horizon')

    await wrapper.get('.el-select__wrapper').trigger('click')
    await flushPromises()
    const option = document.querySelector('.el-select-dropdown__item .product-option')
    expect(option?.textContent).toContain('ART-001 · 7')
    expect(option?.querySelector('img')?.getAttribute('src')).toBe('blob:secure-preview')
  })

  it('uses the product initial when no image exists or authenticated loading fails', async () => {
    mocks.get.mockRejectedValueOnce(new Error('preview failed'))
    const wrapper = mount(ProductSelect, {
      props: {
        modelValue: product.id,
        products: [product],
        placeholder: 'Select a product',
      },
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()
    expect(wrapper.get('.product-option.is-compact').text()).toContain('B')
    expect(wrapper.find('img').exists()).toBe(false)

    await wrapper.setProps({
      products: [{ ...product, id: 'product-2', imageUrl: undefined, name: 'Ceramic Moon' }],
      modelValue: 'product-2',
    })
    await flushPromises()
    expect(wrapper.get('.product-option.is-compact').text()).toContain('C')
    expect(wrapper.find('img').exists()).toBe(false)
  })
})
