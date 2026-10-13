import { registerPlugin } from '@capacitor/core'
import type { Song, PlayHistory, SmartPlaylistRules } from '../../src/types/music'
import type { AiProvider, AssetProvider, AssetCandidate, AssetKind } from '../../src/types/providers'

export interface MobileSong extends Song {
  offline: boolean
  downloading: boolean
  downloadProgress: number
  downloadError?: string
  downloadState?: string
  downloadBytes?: number
  downloadTotal?: number
}
export interface DownloadTask { songId: string; title: string; artist: string; state: 'pending' | 'running' | 'paused' | 'failed' | 'cancelled'; receivedBytes: number; totalBytes: number; error: string }
export interface DownloadPage { items: DownloadTask[]; total: number; active: number; offset: number; hasMore: boolean }
export interface RemoteEntry {
  href: string
  name: string
  directory: boolean
  size: number
  modified: string
  songId?: string
}
export interface PlaybackSnapshot {
  songId: string | null
  playing: boolean
  playWhenReady: boolean
  buffering: boolean
  position: number
  duration: number
  error: string | null
  errorCode?: number | null
  speed: number
  revision: number
}
export interface LibraryQuery { query?: string; offlineOnly?: boolean; offset?: number; limit?: number; artist?: string; album?: string; favoritesOnly?: boolean; genreTerms?: string[] }
export interface Collection { name: string; artist: string; count: number; coverPath: string | null }
export interface LibraryPage { songs: MobileSong[]; total: number; libraryTotal: number; offlineTotal: number; hasMore: boolean; offset: number }
export interface MobilePlaylist { id: string; name: string; description: string; smartRules: string | null; count: number }
export interface Appearance {
  mode: 'system' | 'light' | 'dark'; accent: string; dynamic: boolean; dynamicAvailable: boolean
  motion: boolean; lowPowerVisuals: boolean; autoLookup: boolean; dark: boolean; systemReducedMotion: boolean
  lyricsSize: number; showBackdrop: boolean
  powerSave: boolean; batteryOptimized: boolean; backgroundRestricted: boolean; palette: Record<string, string>
}
export interface PlaybackOptions {
  repeatMode: 'off' | 'one' | 'all'; shuffle: boolean; speed: number; volume: number; normalizeVolume: boolean; prefetch: boolean; crossfadeSeconds: number; sleepRemainingSeconds: number; rememberPlayback: boolean
}
export interface AudioOutputs {
  devices: { name: string; kind: 'speaker' | 'earpiece' | 'bluetooth' | 'wired' | 'usb' | 'otherOutput' }[]
  systemVolume: number; systemVolumeMax: number
}
export interface MobileMusicPlugin {
  aiProviders(): Promise<{ providers: AiProvider[] }>
  saveAiProviders(options: { providers: AiProvider[] }): Promise<{ providers: AiProvider[] }>
  requestAi(options: { stage: 'plan' | 'select'; system: string; user: string; timeoutMillis: number }): Promise<{ value: unknown; providerName: string; attempts: string[] }>
  testAiProvider(options: { id: string }): Promise<{ providerName: string }>
  cancelAi(): Promise<void>
  catalogPage(options: { cursor: number; ceiling?: number; offlineOnly: boolean }): Promise<{ songs: Song[]; cursor: number; ceiling: number; total: number; hasMore: boolean }>
  connect(options: { url: string; username: string; password: string }): Promise<{ url: string }>
  connection(): Promise<{ url: string; username: string }>
  browse(options: { path?: string }): Promise<{ entries: RemoteEntry[] }>
  scan(): Promise<{ added: number; failed: number }>
  library(options?: LibraryQuery): Promise<LibraryPage>
  collections(options: { kind: 'albums' | 'artists'; query?: string; offlineOnly: boolean; offset?: number; limit?: number }): Promise<{ items: Collection[]; total: number; offset: number; hasMore: boolean }>
  overview(): Promise<{ songs: MobileSong[]; history: PlayHistory[]; libraryTotal: number; offlineTotal: number }>
  song(options: { songId: string }): Promise<{ song: MobileSong | null }>
  play(options: { songIds?: string[]; playlistId?: string; libraryQuery?: LibraryQuery; startId: string; offlineOnly: boolean }): Promise<void>
  playlists(): Promise<{ playlists: MobilePlaylist[] }>
  savePlaylist(options: { id?: string; name: string; description?: string; rules?: SmartPlaylistRules; songIds?: string[] }): Promise<{ id: string }>
  deletePlaylist(options: { id: string }): Promise<void>
  playlistSong(options: { id: string; songId: string; remove?: boolean }): Promise<void>
  playlistSongs(options: { id: string; offlineOnly: boolean; offset?: number; limit?: number }): Promise<{ songs: MobileSong[]; total: number; offset: number; hasMore: boolean }>
  toggle(): Promise<void>
  next(): Promise<void>
  previous(): Promise<void>
  seek(options: { position: number }): Promise<void>
  playback(): Promise<PlaybackSnapshot>
  playbackOptions(): Promise<PlaybackOptions>
  setPlaybackOptions(options: Partial<Omit<PlaybackOptions, 'sleepRemainingSeconds'>> & { sleepMinutes?: number }): Promise<PlaybackOptions>
  audioOutputs(): Promise<AudioOutputs>
  download(options: { songId: string }): Promise<void>
  downloadTasks(options: { offset: number; limit: number }): Promise<DownloadPage>
  pauseDownload(options: { songId: string }): Promise<void>
  cancelDownload(options: { songId: string }): Promise<{ freedBytes: number }>
  removeDownload(options: { songId: string }): Promise<{ freedBytes: number }>
  downloadMany(options: { songIds: string[] }): Promise<void>
  favorite(options: { songId: string; favorite: boolean }): Promise<void>
  setWifiOnly(options: { enabled: boolean }): Promise<void>
  settings(): Promise<{ wifiOnly: boolean; storageBytes: number; partialBytes: number; freeBytes: number; streamBytes: number; streamLimitBytes: number; artworkBytes: number }>
  clearStreamCache(): Promise<{ freedBytes: number; retainedBytes: number }>
  appearance(): Promise<Appearance>
  setLanguage(options: { language: 'zh-CN' | 'zh-TW' | 'en' | 'fr' }): Promise<void>
  setAppearance(options: Pick<Appearance, 'mode' | 'accent' | 'dynamic' | 'motion' | 'lowPowerVisuals' | 'autoLookup' | 'lyricsSize' | 'showBackdrop'>): Promise<Appearance>
  lookupAssets(options: { songId: string; force?: boolean }): Promise<{ song: MobileSong }>
  assetProviders(): Promise<{ providers: AssetProvider[]; itunesEnabled: boolean }>
  saveAssetProviders(options: { providers: AssetProvider[]; itunesEnabled: boolean }): Promise<{ providers: AssetProvider[]; itunesEnabled: boolean }>
  searchAssets(options: { songId: string; kind: AssetKind; query: { title: string; artist: string; album: string } }): Promise<{ candidates: AssetCandidate[] }>
  previewAsset(options: { songId: string; kind: AssetKind; candidateId: string }): Promise<{ candidate: AssetCandidate }>
  applyAsset(options: { songId: string; kind: AssetKind; candidateId: string }): Promise<{ song: MobileSong }>
  clearAsset(options: { songId: string; kind: 'cover' | 'background' }): Promise<{ song: MobileSong }>
  cancelAssets(): Promise<void>
  saveLyrics(options: { songId: string; text: string }): Promise<void>
  pinWidget(options: { kind: 'lyrics' | 'music' }): Promise<{ requested: boolean }>
  launchState(): Promise<{ openPlayer: boolean }>
  openSystemSettings(options: { section: 'battery' | 'app' }): Promise<void>
  addListener(eventName: 'libraryChanged', listenerFunc: () => void): Promise<{ remove: () => Promise<void> }>
  addListener(eventName: 'playbackChanged', listenerFunc: (status: PlaybackSnapshot) => void): Promise<{ remove: () => Promise<void> }>
  addListener(eventName: 'appStateChanged', listenerFunc: (state: { active: boolean }) => void): Promise<{ remove: () => Promise<void> }>
  addListener(eventName: 'openPlayer', listenerFunc: () => void): Promise<{ remove: () => Promise<void> }>
}

export const mobileMusic = registerPlugin<MobileMusicPlugin>('AuroraMusic')
