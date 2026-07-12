const CHANNEL_NAME = 'inventory-art-session'
const SESSION_EVENT = 'session-changed'

let channel: BroadcastChannel | undefined

function sessionChannel() {
  if (channel || typeof BroadcastChannel === 'undefined') return channel
  channel = new BroadcastChannel(CHANNEL_NAME)
  return channel
}

export function broadcastSessionChange() {
  sessionChannel()?.postMessage({ type: SESSION_EVENT })
}

export function onRemoteSessionChange(handler: () => void) {
  const useChannel = sessionChannel()
  if (!useChannel) return () => undefined
  const listener = (event: MessageEvent<{ type?: string }>) => {
    if (event.data?.type === SESSION_EVENT) handler()
  }
  useChannel.addEventListener('message', listener)
  return () => useChannel.removeEventListener('message', listener)
}
