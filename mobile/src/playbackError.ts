import { t, type Message } from './i18n'

// Do not render arbitrary native exception text: it can contain private URLs or credentials.
const messages: Record<string, Message> = {
  AUDIO_SOURCE: 'audioSource', AUDIO_NETWORK: 'audioNetwork', AUDIO_TIMEOUT: 'audioTimeout', AUDIO_TLS: 'audioTls',
  AUDIO_FILE: 'audioMissing', AUDIO_SCOPE: 'audioScope', AUDIO_REDIRECT_LOOP: 'audioRedirect', AUDIO_REDIRECT_INVALID: 'audioRedirect',
  AUDIO_REDIRECT_UNSAFE: 'audioUnsafe', AUDIO_NOT_AUDIO: 'audioNotAudio', AUDIO_RANGE: 'audioRange',
  AUDIO_EMPTY: 'audioIncomplete', AUDIO_INCOMPLETE: 'audioIncomplete', AUDIO_ENCODING: 'audioIncomplete',
  AUDIO_FORMAT: 'audioFormat', AUDIO_DECODER: 'audioDecoder', AUDIO_OUTPUT: 'audioOutput', AUDIO_CANCELLED: 'audioSource',
}
export function playbackError(error: string, mediaCode?: number | null): string {
  const http = /^AUDIO_HTTP_([1-5]\d{2})$/.exec(error)
  const safe = http || Object.prototype.hasOwnProperty.call(messages, error) ? error : 'AUDIO_SOURCE'
  const key = http ? ['401', '403'].includes(http[1]) ? 'audioAuth' : http[1] === '404' ? 'audioMissing' : 'audioHttp'
    : messages[safe]
  const code = Number.isSafeInteger(mediaCode) && mediaCode !== null && mediaCode !== undefined ? ` · Media3 ${mediaCode}` : ''
  return `${t(key)} (${safe}${code})`
}
