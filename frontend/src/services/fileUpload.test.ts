import { AxiosError, type AxiosAdapter, type AxiosResponse } from 'axios'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from './api'
import { uploadPendingFile } from './fileUpload'

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
    originalApiAdapter = api.defaults.adapter as AxiosAdapter | undefined
  })

  afterEach(() => {
    api.defaults.adapter = originalApiAdapter
    vi.restoreAllMocks()
  })

  it('uploads through the authenticated backend without depending on object-storage CORS', async () => {
    const backendAdapter = vi.fn<AxiosAdapter>(async (config) => response(config))
    api.defaults.adapter = backendAdapter

    await uploadPendingFile(upload, file, 'browser-checksum')

    expect(backendAdapter).toHaveBeenCalledOnce()
    const backendRequest = backendAdapter.mock.calls[0]![0]
    expect(backendRequest.url).toBe(`/files/${upload.fileId}/content`)
    expect(backendRequest.headers.get('X-Content-Sha256')).toBe('browser-checksum')
  })

  it('does not hide a backend upload error', async () => {
    const backendAdapter = vi.fn<AxiosAdapter>(async (config) => {
      throw new AxiosError('Signature rejected', AxiosError.ERR_BAD_REQUEST, config, undefined, {
        ...response(config),
        status: 403,
        statusText: 'Forbidden',
      })
    })
    api.defaults.adapter = backendAdapter

    await expect(uploadPendingFile(upload, file, 'browser-checksum')).rejects.toThrow(
      'Signature rejected',
    )
    expect(backendAdapter).toHaveBeenCalledOnce()
  })
})
