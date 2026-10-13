export type PlayerLayout = 'mini' | 'lyrics' | 'full'
export type WindowAction = 'mini' | 'lyrics' | 'full' | 'minimize' | 'maximize' | 'close'

export function playerLayout(width: number, height: number): PlayerLayout {
  if (width < 400 || height < 280) return 'mini'
  return width < 980 || height < 600 ? 'lyrics' : 'full'
}

export interface PlayerPreferences {
  rememberPlayback: boolean
  alwaysOnTop: boolean
  prefetch: boolean
  normalizeVolume: boolean
  crossfadeSeconds: number
  lyricsSize: number
  showBackdrop: boolean
  accent: 'rose' | 'blue' | 'green' | 'violet'
}

export const DEFAULT_PLAYER_PREFERENCES: PlayerPreferences = {
  rememberPlayback: true,
  alwaysOnTop: false,
  prefetch: false,
  normalizeVolume: false,
  crossfadeSeconds: 0,
  lyricsSize: 24,
  showBackdrop: true,
  accent: 'rose',
}
