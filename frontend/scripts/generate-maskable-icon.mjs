import { resolve } from 'node:path'
import sharp from 'sharp'

await sharp(resolve('public/pwa-icon.svg'))
  .resize(512, 512)
  .png()
  .toFile(resolve('public/maskable-icon-512x512.png'))
