const MAX_PREVIEW_DIMENSION = 480
const MAX_PREVIEW_SIZE = 512 * 1024

export function fitPreview(width: number, height: number) {
  if (width <= 0 || height <= 0) throw new Error('Invalid image dimensions')
  const ratio = Math.min(1, MAX_PREVIEW_DIMENSION / width, MAX_PREVIEW_DIMENSION / height)
  return {
    width: Math.max(1, Math.round(width * ratio)),
    height: Math.max(1, Math.round(height * ratio)),
  }
}

export async function createImagePreview(file: File): Promise<Blob> {
  const decoded = await decode(file)
  try {
    const dimensions = fitPreview(decoded.width, decoded.height)
    const canvas = document.createElement('canvas')
    canvas.width = dimensions.width
    canvas.height = dimensions.height
    const context = canvas.getContext('2d')
    if (!context) throw new Error('Image preview is unavailable')
    context.drawImage(decoded.source, 0, 0, dimensions.width, dimensions.height)

    for (const quality of [0.65, 0.5, 0.35]) {
      const preview = await canvasBlob(canvas, quality)
      if (preview.type === 'image/webp' && preview.size <= MAX_PREVIEW_SIZE) return preview
    }
    throw new Error('Image preview is too large')
  } finally {
    decoded.close()
  }
}

export async function sha256(value: Blob): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', await value.arrayBuffer())
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, '0')).join('')
}

async function decode(file: File): Promise<{
  source: CanvasImageSource
  width: number
  height: number
  close: () => void
}> {
  if (typeof createImageBitmap === 'function') {
    const bitmap = await createImageBitmap(file, { imageOrientation: 'from-image' })
    return {
      source: bitmap,
      width: bitmap.width,
      height: bitmap.height,
      close: () => bitmap.close(),
    }
  }

  const url = URL.createObjectURL(file)
  try {
    const image = new Image()
    image.src = url
    await image.decode()
    return {
      source: image,
      width: image.naturalWidth,
      height: image.naturalHeight,
      close: () => URL.revokeObjectURL(url),
    }
  } catch (error) {
    URL.revokeObjectURL(url)
    throw error
  }
}

function canvasBlob(canvas: HTMLCanvasElement, quality: number): Promise<Blob> {
  return new Promise((resolve, reject) => {
    canvas.toBlob(
      (blob) => (blob ? resolve(blob) : reject(new Error('Image preview is unavailable'))),
      'image/webp',
      quality,
    )
  })
}
