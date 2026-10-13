const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const Module = require('node:module')
const ts = require('typescript')

const file = path.resolve(__dirname, '../src/main/assetMatch.ts')
const compiled = ts.transpileModule(fs.readFileSync(file, 'utf8'), {
  compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.CommonJS },
})
const instance = new Module(file, module)
instance.filename = file
instance.paths = module.paths
instance._compile(compiled.outputText, file)
const { searchMetadata, lyricLevels, bestLyrics, bestAlbumCover, bestTrackCover } = instance.exports

const meta = searchMetadata({ title: 'Song', artist: 'Singer', album: 'Album', duration: 240 })
assert.deepEqual(lyricLevels(meta), [
  { title: 'Song', artist: 'Singer', album: 'Album' },
  { title: 'Song', artist: 'Singer', album: '' },
  { title: 'Song', artist: '', album: '' },
])
assert.equal(lyricLevels({ title: 'Song', artist: '', album: '' }).length, 1)
assert.deepEqual(searchMetadata({ title: 'Singer - Song', artist: '未知艺术家', album: '' }),
  { title: 'Song', artist: 'Singer', album: '', duration: undefined })

const lyrics = [
  { trackName: 'Song', artistName: 'Other', albumName: 'Album', syncedLyrics: '[00:00.00]wrong' },
  { trackName: 'Song', artistName: 'Singer', albumName: 'Other album', plainLyrics: 'fallback' },
  { trackName: 'Song', artistName: 'Singer', albumName: 'Album', syncedLyrics: '[00:00.00]correct' },
]
assert.equal(bestLyrics(lyrics, meta, lyricLevels(meta)[0]).syncedLyrics, '[00:00.00]correct')
assert.equal(bestLyrics(lyrics.slice(0, 2), meta, lyricLevels(meta)[0]), undefined)
assert.equal(bestLyrics(lyrics.slice(0, 2), meta, lyricLevels(meta)[1]).plainLyrics, 'fallback')
assert.equal(bestLyrics(lyrics.slice(0, 1), meta, lyricLevels(meta)[2]).syncedLyrics, '[00:00.00]wrong')

const albums = [
  { strArtist: 'Other', strAlbum: 'Album', strAlbumThumb: 'https://example.com/wrong.jpg' },
  { strArtist: 'Singer', strAlbum: 'Album', strAlbumThumb: 'https://example.com/right.jpg' },
]
assert.equal(bestAlbumCover(albums, meta).strAlbumThumb, 'https://example.com/right.jpg')
assert.equal(bestAlbumCover(albums.slice(0, 1), meta), undefined)
const tracks = [
  { trackName: 'Song', artistName: 'Singer', albumName: 'Wrong', artworkUrl100: 'wrong' },
  { trackName: 'Song', artistName: 'Singer', albumName: 'Album', trackTimeMillis: 240000, artworkUrl100: 'right' },
  { trackName: 'Song', artistName: 'Other', albumName: 'Album', artworkUrl100: 'wrong' },
]
assert.equal(bestTrackCover(tracks, meta).artworkUrl100, 'right')
assert.equal(bestTrackCover(tracks.slice(0, 1), meta), undefined)
console.log('ASSET_MATCH_PASS: three lyric levels, exact album and track covers, filename metadata')
