export interface MatchMetadata { title: string; artist: string; album: string; duration?: number }
export interface MatchRow { trackName?: string; title?: string; name?: string; artistName?: string; artist?: string; albumName?: string; album?: string; strArtist?: string; strAlbum?: string; syncedLyrics?: string; plainLyrics?: string; lyrics?: string; instrumental?: boolean; duration?: number; imageUrl?: string; artworkUrl100?: string; strAlbumThumb?: string; trackTimeMillis?: number }

const normalized = (value: string) => value.normalize('NFKC').toLocaleLowerCase().replace(/[\s\p{P}\p{S}]+/gu, '')
const same = (left: string, right: string) => Boolean(left && right && normalized(left) === normalized(right))
const textOf = (row: MatchRow) => String(row.syncedLyrics || row.plainLyrics || row.lyrics || '').trim()
const rowTitle = (row: MatchRow) => String(row.trackName || row.title || row.name || '')
const rowArtist = (row: MatchRow) => String(row.artistName || row.artist || row.strArtist || '')
const rowAlbum = (row: MatchRow) => String(row.albumName || row.album || row.strAlbum || '')

export function searchMetadata(song: MatchMetadata): MatchMetadata {
  const title = song.title.trim()
  const artist = song.artist.trim() === '未知艺术家' ? '' : song.artist.trim()
  const filename = !artist && title.match(/^(.+?)\s[-–—]\s(.+)$/)
  return { title: filename ? filename[2].trim() : title, artist: filename ? filename[1].trim() : artist, album: song.album.trim(), duration: song.duration }
}

export function lyricLevels(meta: MatchMetadata): Array<Pick<MatchMetadata, 'title' | 'artist' | 'album'>> {
  if (!meta.title) return []
  const levels = [
    { title: meta.title, artist: meta.artist, album: meta.album },
    { title: meta.title, artist: meta.artist, album: '' },
    { title: meta.title, artist: '', album: '' },
  ]
  return levels.filter((level, index) => index === levels.findIndex(other => other.title === level.title && other.artist === level.artist && other.album === level.album))
}

export function bestLyrics(rows: MatchRow[], meta: MatchMetadata, level: Pick<MatchMetadata, 'artist' | 'album'>): MatchRow | undefined {
  return rows.filter(row => textOf(row).length > 0 && !row.instrumental && same(rowTitle(row), meta.title) &&
    (!level.artist || same(rowArtist(row), level.artist)) && (!level.album || same(rowAlbum(row), level.album)))
    .sort((a, b) => {
      const score = (row: MatchRow) => Number(Boolean(row.syncedLyrics)) * 10 + Number(same(rowArtist(row), meta.artist)) * 4 + Number(same(rowAlbum(row), meta.album)) * 2 -
        (meta.duration && row.duration ? Math.min(10, Math.abs(row.duration - meta.duration) / 10) : 0)
      return score(b) - score(a)
    })[0]
}

export function bestAlbumCover(rows: MatchRow[], meta: MatchMetadata): MatchRow | undefined {
  if (!meta.artist || !meta.album) return undefined
  return rows.find(row => Boolean(row.imageUrl || row.strAlbumThumb) && same(rowArtist(row), meta.artist) &&
    same(rowAlbum(row) || String(row.title || ''), meta.album))
}

export function bestTrackCover(rows: MatchRow[], meta: MatchMetadata): MatchRow | undefined {
  if (!meta.artist || !meta.title) return undefined
  const matches = rows.filter(row => Boolean(row.artworkUrl100 || row.imageUrl) && same(rowTitle(row), meta.title) && same(rowArtist(row), meta.artist) &&
    (!meta.album || same(rowAlbum(row), meta.album)) &&
    (!meta.duration || !row.trackTimeMillis || Math.abs(row.trackTimeMillis / 1000 - meta.duration) <= 20))
  return matches[0]
}

export function lyricText(row: MatchRow): string { return textOf(row).slice(0, 100_000) }
