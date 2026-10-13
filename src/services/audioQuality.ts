import type { Song } from '../types/music'

export type QualityTier = 'lossless' | 'high-bitrate' | 'compressed' | 'unknown'

export function qualityTier(song: Song | null): QualityTier {
  if (!song) return 'unknown'
  const codec = (song.codec || '').toLowerCase()
  const format = (song.format || '').toLowerCase()
  if (/flac|alac|pcm|wavpack|monkey|ape|dsd/.test(codec) || /^(flac|alac|ape|wv|dsf|dff)$/.test(format)) return 'lossless'
  if (!song.bitrate) return 'unknown'
  return song.bitrate >= 256_000 ? 'high-bitrate' : 'compressed'
}
