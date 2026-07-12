import { api } from '@/services/api'

const uploadTimeout = 120_000

export interface PendingUpload {
  fileId: string
}

export async function uploadPendingFile(upload: PendingUpload, file: File, checksumSha256: string) {
  await api.put(`/files/${upload.fileId}/content`, file, {
    headers: {
      'Content-Type': file.type,
      'X-Content-Sha256': checksumSha256,
    },
    timeout: uploadTimeout,
  })
}
