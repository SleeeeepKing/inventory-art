import { beforeEach, describe, expect, it, vi } from 'vitest'

const mocks = vi.hoisted(() => ({ error: vi.fn() }))

vi.mock('element-plus', () => ({ ElMessage: { error: mocks.error } }))
vi.mock('vue-i18n', () => ({
  useI18n: () => ({
    t: (key: string, params?: Record<string, string>) =>
      params?.traceId ? `${key}:${params.traceId}` : key,
  }),
}))

import { useApiFeedback } from './useApiFeedback'

function apiError(status: number, data: Record<string, unknown>) {
  return { isAxiosError: true, response: { status, data } }
}

describe('API error feedback', () => {
  beforeEach(() => vi.clearAllMocks())

  it('localizes known error codes', () => {
    const { showError } = useApiFeedback()
    const fields = showError(
      apiError(400, { code: 'INSUFFICIENT_STOCK', message: 'internal message' }),
    )
    expect(mocks.error).toHaveBeenCalledWith('errors.insufficientStock')
    expect(fields).toEqual({})
  })

  it('shows invalid credentials instead of session expired for login failures', () => {
    const { showError } = useApiFeedback()
    showError(apiError(401, { code: 'INVALID_CREDENTIALS', message: 'internal message' }))
    expect(mocks.error).toHaveBeenCalledWith('auth.loginFailed')
  })

  it('shows and returns field-level validation details', () => {
    const { showError } = useApiFeedback()
    const fields = showError(
      apiError(400, { code: 'VALIDATION_ERROR', fieldErrors: { quantity: 'must be positive' } }),
    )
    expect(mocks.error).toHaveBeenCalledWith('errors.validation must be positive')
    expect(fields).toEqual({ quantity: 'must be positive' })
  })

  it('shows a specific backend message for an unmapped client error', () => {
    const { showError } = useApiFeedback()
    showError(
      apiError(422, { code: 'NEW_BUSINESS_RULE', message: 'This order cannot be processed today' }),
    )
    expect(mocks.error).toHaveBeenCalledWith('This order cannot be processed today')
  })

  it('hides server details and includes the trace id for server errors', () => {
    const { showError } = useApiFeedback()
    showError(
      apiError(500, {
        code: 'INTERNAL_ERROR',
        message: 'database password leaked',
        traceId: 'trace-1',
      }),
    )
    expect(mocks.error).toHaveBeenCalledWith('errors.generic errors.traceId:trace-1')
  })

  it('distinguishes network failures from server responses', () => {
    const { showError } = useApiFeedback()
    showError({ isAxiosError: true, request: {} })
    expect(mocks.error).toHaveBeenCalledWith('errors.network')
  })

  it('warns that a disconnected write may already have completed', () => {
    const { showError } = useApiFeedback()
    showError({ isAxiosError: true, request: {}, config: { method: 'post' } })
    expect(mocks.error).toHaveBeenCalledWith('errors.writeOutcomeUnknown')
  })
})
