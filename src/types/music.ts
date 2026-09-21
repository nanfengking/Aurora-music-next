// ===== 音乐播放器核心类型 =====

export interface Song {
  id: string
  title: string
  artist: string
  album: string
  year: number | null
  genre: string
  duration: number // 秒
  filePath: string
  format: string
  coverPath: string | null
  customArtistImage: string | null
  customLyrics: string | null // JSON 字符串
  sourceType: 'local' | 'webdav'
  remotePath?: string | null
  remoteSize?: number
  remoteModified?: string | null
  localCachePath?: string | null
  cacheSize?: number
  lastCachedAt?: string | null
  lastAccessedAt?: string | null
  isFavorite?: number
  playCount?: number
  skipCount?: number
  lastPlayedAt?: number | null
  createdAt: string
}

export interface Playlist {
  id: string
  name: string
  description: string | null
  songIds: string // JSON 数组
  createdAt: string
  updatedAt: string
}

export interface PlayHistory {
  id: number
  songId: string
  action: 'start' | 'complete' | 'skip' | 'pause'
  timestamp: number
  progress: number // 0~1
}

export interface Setting {
  key: string
  value: string
}

export interface ScanResult {
  added: number
  total: number
}

export interface DeepSeekConfigInput {
  apiKey: string
  baseUrl: string
  model: string
}

export interface DeepSeekConfigStatus {
  configured: boolean
  baseUrl: string
  model: string
}

export interface AiPlaylistResult {
  name: string
  description: string
  songIds: string[]
  providerName?: string
  attempts?: string[]
  source?: 'local' | 'hybrid'
  diagnostics?: {
    librarySize: number
    eligibleSize: number
    candidateSize: number
    targetSeconds: number | null
    durationSeconds: number
    estimatedTracks: number
    warnings: string[]
  }
}

export interface TasteFacet {
  name: string
  score: number
  evidenceTracks: number
  availableTracks: number
}

export interface ListeningProfile {
  confidence: 'new' | 'learning' | 'established'
  historySessions: number
  completedSessions: number
  skippedSessions: number
  genres: TasteFacet[]
  artists: TasteFacet[]
  suggestedGenres: Array<{ name: string; reason: string; songIds: string[] }>
  genreCoverage: number
  librarySize: number
  summary: string
}

export interface RecommendationFeed {
  songs: Song[]
  reasons: Record<string, string>
  profile: ListeningProfile
}

export interface SceneIntent {
  scene: 'any' | 'run' | 'focus' | 'sleep' | 'relax'
  durationSeconds: number | null
  count: number | null
  artists: string[]
  genres: string[]
  keywords: string[]
  exclude: string[]
  language: 'any' | 'chinese' | 'english' | 'japanese' | 'korean'
}

/* IPC 统一返回格式 */
export interface IpcResponse<T = unknown> {
  success: boolean
  data?: T
  error?: string
}
