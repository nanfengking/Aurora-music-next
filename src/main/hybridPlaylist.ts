import type Database from 'better-sqlite3'
import type { AiPlaylistResult, PlayHistory, Song } from '../types/music'
import { getProviders, requestJsonWithProviders } from './aiProviders'
import { generateHybridPlaylistCore } from '../services/hybridPlaylist'

export async function generateHybridPlaylist(db: Database.Database, songs: Song[], history: PlayHistory[], prompt: string, useAi = true): Promise<AiPlaylistResult> {
  return generateHybridPlaylistCore({ songs, history, prompt, useAi,
    configured: getProviders(db).some(provider => provider.enabled && provider.configured),
    requestJson: (_stage, system, user, validate, deadline) => requestJsonWithProviders(db, system, user, validate, deadline),
  })
}
