import axios from 'axios'
import { api, resolveApiUrl } from '@/services/api'

const uploadTimeout = 120_000

export interface PresignedUpload {
  uploadUrl: string
  fileId: string
  headers?: Record<string, string>
}

export async function uploadPresignedFile(
  upload: PresignedUpload,
  file: File,
  checksumSha256: string,
) {
  try {
    await axios.put(resolveApiUrl(upload.uploadUrl), file, {
      headers: { 'Content-Type': file.type, ...upload.headers },
      timeout: uploadTimeout,
    })
  } catch (error) {
    if (!axios.isAxiosError(error) || error.response || axios.isCancel(error)) throw error

    await api.put(`/files/${upload.fileId}/content`, file, {
      headers: {
        'Content-Type': file.type,
        'X-Content-Sha256': checksumSha256,
      },
      timeout: uploadTimeout,
    })
  }
}
