import { AxiosError, type AxiosAdapter, type AxiosResponse } from 'axios'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { resetConnectivityState } from './connectivity'
import { api, coldStartDelayMs, shouldRetryColdStart } from './api'

let originalAdapter: AxiosAdapter | undefined

function response(config: Parameters<AxiosAdapter>[0]): AxiosResponse {
  return { data: { ok: true }, status: 200, statusText: 'OK', headers: {}, config }
}

describe('Railway cold-start retry policy', () => {
  beforeEach(() => {
    originalAdapter = api.defaults.adapter as AxiosAdapter | undefined
    resetConnectivityState()
    vi.useFakeTimers()
  })

  afterEach(() => {
    api.defaults.adapter = originalAdapter
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('retries a GET twice with bounded backoff and then succeeds', async () => {
    let calls = 0
    const adapter = vi.fn<AxiosAdapter>(async (config) => {
      calls += 1
      if (calls < 3) throw new AxiosError('network down', AxiosError.ERR_NETWORK, config)
      return response(config)
    })
    api.defaults.adapter = adapter

    const request = api.get('/safe-read')
    await vi.runAllTimersAsync()

    await expect(request).resolves.toMatchObject({ status: 200 })
    expect(adapter).toHaveBeenCalledTimes(3)
    expect(coldStartDelayMs(0)).toBe(750)
    expect(coldStartDelayMs(1)).toBe(1_500)
  })

  it.each(['post', 'put', 'patch', 'delete'])('never retries %s requests', async (method) => {
    const adapter = vi.fn<AxiosAdapter>(async (config) => {
      throw new AxiosError('network down', AxiosError.ERR_NETWORK, config)
    })
    api.defaults.adapter = adapter

    await expect(api.request({ method, url: '/unsafe-write', data: {} })).rejects.toThrow(
      'network down',
    )
    expect(adapter).toHaveBeenCalledTimes(1)
  })

  it('only accepts safe methods, online state, and transient gateway failures', () => {
    expect(
      shouldRetryColdStart({
        method: 'head',
        attempt: 0,
        status: 503,
        networkError: false,
        canceled: false,
        online: true,
      }),
    ).toBe(true)
    expect(
      shouldRetryColdStart({
        method: 'get',
        attempt: 2,
        status: 503,
        networkError: false,
        canceled: false,
        online: true,
      }),
    ).toBe(false)
    expect(
      shouldRetryColdStart({
        method: 'get',
        attempt: 0,
        status: 500,
        networkError: false,
        canceled: false,
        online: true,
      }),
    ).toBe(false)
    expect(
      shouldRetryColdStart({
        method: 'get',
        attempt: 0,
        networkError: true,
        canceled: false,
        online: false,
      }),
    ).toBe(false)
  })
})
