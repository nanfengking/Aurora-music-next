export type AiProtocol = 'openai' | 'anthropic' | 'gemini'
export interface AiProvider { id: string; name: string; protocol: AiProtocol; baseUrl: string; model: string; priority: number; enabled: boolean; configured: boolean; apiKey?: string }
export type AssetKind = 'lyrics' | 'cover' | 'background'
export interface AssetConfig { lyricsUrl: string; coverUrl: string; backgroundUrl: string; apiKey?: string; configured: boolean; audioDbKey?: string; audioDbConfigured: boolean }
export interface AssetCandidate { id: string; title: string; artist: string; album: string; text?: string; imagePath?: string; source: string; sourceUrl?: string; synced?: boolean }
