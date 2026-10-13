export type AiProtocol = 'openai' | 'responses' | 'anthropic' | 'gemini'
export interface AiProvider { id: string; name: string; protocol: AiProtocol; baseUrl: string; model: string; priority: number; enabled: boolean; configured: boolean; apiKey?: string }
export type AssetKind = 'lyrics' | 'cover' | 'background'
/** Resource endpoints use GET + title/artist/album/kind, not an AI chat protocol. */
export interface AssetProvider { id: string; name: string; kind: AssetKind; baseUrl: string; priority: number; enabled: boolean; configured: boolean; apiKey?: string }
export interface AssetConfig { lyricsUrl: string; coverUrl: string; backgroundUrl: string; autoLookup: boolean; itunesEnabled?: boolean; apiKey?: string; configured: boolean; audioDbKey?: string; audioDbConfigured: boolean }
export interface AssetCandidate { id: string; title: string; artist: string; album: string; text?: string; imagePath?: string; source: string; sourceUrl?: string; synced?: boolean }
