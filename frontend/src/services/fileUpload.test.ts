import axios, { AxiosError, type AxiosAdapter, type AxiosResponse } from 'axios'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from './api'
import { uploadPresignedFile } from './fileUpload'

let originalAxiosAdapter: AxiosAdapter | undefined
let originalApiAdapter: AxiosAdapter | undefined

function response(config: Parameters<AxiosAdapter>[0]): AxiosResponse {
  return { data: undefined, status: 204, statusText: 'No Content', headers: {}, config }
}

describe('presigned file upload', () => {
  const file = new File(['image-bytes'], 'art.png', { type: 'image/png' })
  const upload = {
    uploadUrl: 'https://objects.example.test/signed-image',
    fileId: '44444444-4444-4444-8444-444444444444',
    headers: { 'x-amz-meta-sha256': 'signed-checksum' },
  }

  beforeEach(() => {
    originalAxiosAdapter = axios.defaults.adapter as AxiosAdapter | undefined
    originalApiAdapter = api.defaults.adapter as AxiosAdapter | undefined
  })

  afterEach(() => {
    axios.defaults.adapter = originalAxiosAdapter
    api.defaults.adapter = originalApiAdapter
    vi.restoreAllMocks()
  })

  it('uses the authenticated backend fallback when object storage returns no HTTP response', async () => {
    const directAdapter = vi.fn<AxiosAdapter>(async (config) => {
      throw new AxiosError('CORS blocked', AxiosError.ERR_NETWORK, config)
    })
    const fallbackAdapter = vi.fn<AxiosAdapter>(async (config) => response(config))
    axios.defaults.adapter = directAdapter
    api.defaults.adapter = fallbackAdapter

    await uploadPresignedFile(upload, file, 'browser-checksum')

    expect(directAdapter).toHaveBeenCalledOnce()
    expect(fallbackAdapter).toHaveBeenCalledOnce()
    const fallbackRequest = fallbackAdapter.mock.calls[0]![0]
    expect(fallbackRequest.url).toBe(`/files/${upload.fileId}/content`)
    expect(fallbackRequest.headers.get('X-Content-Sha256')).toBe('browser-checksum')
  })

  it('does not hide an object storage HTTP error', async () => {
    const directAdapter = vi.fn<AxiosAdapter>(async (config) => {
      throw new AxiosError('Signature rejected', AxiosError.ERR_BAD_REQUEST, config, undefined, {
        ...response(config),
        status: 403,
        statusText: 'Forbidden',
      })
    })
    const fallbackAdapter = vi.fn<AxiosAdapter>(async (config) => response(config))
    axios.defaults.adapter = directAdapter
    api.defaults.adapter = fallbackAdapter

    await expect(uploadPresignedFile(upload, file, 'browser-checksum')).rejects.toThrow(
      'Signature rejected',
    )
    expect(fallbackAdapter).not.toHaveBeenCalled()
  })
})
