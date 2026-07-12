/// <reference lib="webworker" />

import { clientsClaim } from 'workbox-core'
import {
  cleanupOutdatedCaches,
  createHandlerBoundToURL,
  precacheAndRoute,
} from 'workbox-precaching'
import { NavigationRoute, registerRoute } from 'workbox-routing'
import { NetworkOnly } from 'workbox-strategies'
import { isNetworkOnlyRequest, navigationFallbackDenylist } from './pwa/cachePolicy'

declare let self: ServiceWorkerGlobalScope

precacheAndRoute(self.__WB_MANIFEST)
cleanupOutdatedCaches()

registerRoute(({ url }) => isNetworkOnlyRequest(url, self.location.origin), new NetworkOnly())

registerRoute(
  new NavigationRoute(createHandlerBoundToURL('/index.html'), {
    denylist: navigationFallbackDenylist,
  }),
)

self.addEventListener('message', (event) => {
  if (event.data?.type === 'SKIP_WAITING') void self.skipWaiting()
})

clientsClaim()
