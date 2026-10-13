import type { Playlist, SmartPlaylistRules, Song } from '../types/music'

export const DEFAULT_SMART_RULES: SmartPlaylistRules = {
  version: 1, favoritesOnly: false, artist: '', genre: '', recentDays: 0,
  limit: 50, durationMinutes: 0, sort: 'recent',
}

export function parseSmartRules(value: unknown): SmartPlaylistRules | null {
  if (value == null || value === '') return null
  const input = typeof value === 'string' ? JSON.parse(value) : value
  if (!input || input.version !== 1) throw new Error('歌单规则无效')
  const integer = (key: string, min: number, max: number) => {
    const n = input[key]
    if (!Number.isInteger(n) || n < min || n > max) throw new Error('歌单规则无效')
    return n as number
  }
  if (typeof input.favoritesOnly !== 'boolean' || typeof input.artist !== 'string' || typeof input.genre !== 'string' ||
      !['recent', 'most-played', 'newest', 'title'].includes(input.sort)) throw new Error('歌单规则无效')
  return {
    version: 1, favoritesOnly: input.favoritesOnly, artist: input.artist.trim().slice(0, 100), genre: input.genre.trim().slice(0, 100),
    recentDays: integer('recentDays', 0, 365), limit: integer('limit', 1, 5000), durationMinutes: integer('durationMinutes', 0, 1440), sort: input.sort,
  }
}

/** Deterministic and network-free. Resolve on library changes, never on playback ticks. */
export function selectSmartSongs(songs: Song[], rules: SmartPlaylistRules, now = Date.now()): Song[] {
  const normalize = (text: string) => text.normalize('NFKC').toLocaleLowerCase()
  const artist = normalize(rules.artist), genre = normalize(rules.genre)
  const sorted = songs.filter(song =>
    (!rules.favoritesOnly || Boolean(song.isFavorite)) &&
    (!artist || normalize(song.artist || '').includes(artist)) &&
    (!genre || normalize(song.genre || '').includes(genre)) &&
    (!rules.recentDays || (song.lastPlayedAt || 0) >= now - rules.recentDays * 86400000),
  ).sort((a, b) => {
    const score = rules.sort === 'recent' ? Number(b.lastPlayedAt || 0) - Number(a.lastPlayedAt || 0)
      : rules.sort === 'most-played' ? Number(b.playCount || 0) - Number(a.playCount || 0)
      : rules.sort === 'newest' ? b.createdAt.localeCompare(a.createdAt) : a.title.localeCompare(b.title)
    return score || a.id.localeCompare(b.id)
  })
  const selected: Song[] = []
  let seconds = 0
  for (const song of sorted) {
    if (selected.length >= rules.limit) break
    if (rules.durationMinutes && (song.duration <= 0 || seconds + song.duration > rules.durationMinutes * 60)) continue
    selected.push(song)
    seconds += song.duration
  }
  return selected
}

export function resolveSmartPlaylists(playlists: Playlist[], songs: Song[], now = Date.now()): Playlist[] {
  return playlists.map(playlist => {
    try {
      const rules = parseSmartRules(playlist.smartRules)
      return rules ? { ...playlist, songIds: JSON.stringify(selectSmartSongs(songs, rules, now).map(song => song.id)) } : playlist
    } catch { return { ...playlist, songIds: '[]' } }
  })
}
