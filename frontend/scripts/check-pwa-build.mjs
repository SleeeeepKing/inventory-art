import { access, readFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import process from 'node:process'

const dist = resolve('dist')
const requiredFiles = [
  'sw.js',
  'manifest.webmanifest',
  'index.html',
  'logo.png',
  'favicon.ico',
  'apple-touch-icon-180x180.png',
  'pwa-192x192.png',
  'pwa-512x512.png',
  'maskable-icon-512x512.png',
  '_headers',
  '_redirects',
]

await Promise.all(requiredFiles.map((file) => access(resolve(dist, file))))

const manifest = JSON.parse(await readFile(resolve(dist, 'manifest.webmanifest'), 'utf8'))
const expectedManifest = {
  id: '/',
  start_url: '/',
  scope: '/',
  display: 'standalone',
  theme_color: '#10263d',
  background_color: '#f6f8fa',
}

for (const [key, value] of Object.entries(expectedManifest)) {
  if (manifest[key] !== value) throw new Error(`Manifest ${key} must be ${value}`)
}

for (const size of ['192x192', '512x512']) {
  if (!manifest.icons?.some((icon) => icon.sizes === size && icon.purpose === 'any')) {
    throw new Error(`Manifest is missing the ${size} install icon`)
  }
}
if (!manifest.icons?.some((icon) => icon.sizes === '512x512' && icon.purpose === 'maskable')) {
  throw new Error('Manifest is missing the 512x512 maskable icon')
}

for (const [file, expectedSize] of [
  ['apple-touch-icon-180x180.png', 180],
  ['pwa-192x192.png', 192],
  ['pwa-512x512.png', 512],
  ['maskable-icon-512x512.png', 512],
]) {
  const png = await readFile(resolve(dist, file))
  const width = png.readUInt32BE(16)
  const height = png.readUInt32BE(20)
  if (width !== expectedSize || height !== expectedSize) {
    throw new Error(`${file} must be ${expectedSize}x${expectedSize}`)
  }
}

const worker = await readFile(resolve(dist, 'sw.js'), 'utf8')
for (const marker of ['/api/', '/actuator/', 'SKIP_WAITING', 'index.html']) {
  if (!worker.includes(marker)) throw new Error(`Service worker is missing ${marker}`)
}
if (/"url":"(?:https?:|\/?api\/|\/?actuator\/)/.test(worker)) {
  throw new Error('Service worker precache contains a network-only URL')
}
if (worker.includes('"url":"logo.png"')) {
  throw new Error('Service worker must not precache the full-resolution logo source')
}

const headers = await readFile(resolve(dist, '_headers'), 'utf8')
if (!headers.includes('/sw.js') || !headers.includes('no-store')) {
  throw new Error('Service worker cache headers are missing')
}

process.stdout.write(
  `PWA build verified: ${requiredFiles.length} artifacts and strict network-only boundaries\n`,
)
