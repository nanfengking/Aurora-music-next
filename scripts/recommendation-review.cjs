// Pure-model regression tests: no Electron, credentials, network or user database.
const fs = require('node:fs')
const path = require('node:path')
const Module = require('node:module')
const assert = require('node:assert/strict')
const ts = require('typescript')
const file = path.resolve(__dirname, '../src/main/recommendation.ts')
const compiled = ts.transpileModule(fs.readFileSync(file, 'utf8'), { compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.CommonJS } })
const modelModule = new Module(file, module)
modelModule.filename = file; modelModule.paths = module.paths
modelModule._compile(compiled.outputText, file)
const { buildBehaviorModel, recommendationFeed, localIntent, retrieveCandidates, fitPlaylist, validateIntent, mergeIntent, genresOf } = modelModule.exports
const now = Date.now(), day = 86400000
const song = (id, extra = {}) => ({ id, title: `Track ${id}`, artist: `Artist ${id}`, album: 'Album', genre: '', year: null, duration: 240, filePath: `/${id}.m4a`, remotePath: `/${id}.m4a`, sourceType: 'webdav', format: 'm4a', coverPath: null, customArtistImage: null, customLyrics: null, createdAt: '', ...extra })
const event = (id, songId, action, progress = 0, timestamp = now - 1000 + id) => ({ id, songId, action, progress, timestamp })
let count = 0
function test(name, fn) { fn(); count++; console.log(`PASS ${name}`) }

test('No history means no invented preferences; unknown genres do not become taste', () => {
  const model = buildBehaviorModel([song('a', { artist: '未知艺术家', genre: 'Unknown' })], [], now)
  assert.equal(model.profile.confidence, 'new'); assert.deepEqual(model.profile.genres, []); assert.deepEqual(model.profile.artists, [])
})
test('Bare starts do not mean likes; pause/resume is one listening session', () => {
  const library = [song('a', { genre: 'Rock' })]
  assert.equal(buildBehaviorModel(library, [event(1, 'a', 'start')], now).trackScores.get('a'), 0)
  const history = [event(1, 'a', 'start'), event(2, 'a', 'pause', 0.6), event(3, 'a', 'pause', 0.6), event(4, 'a', 'complete', 1)]
  const model = buildBehaviorModel(library, history, now)
  assert.equal(model.profile.historySessions, 1); assert.equal(model.profile.completedSessions, 1)
  assert.ok(model.trackScores.get('a') <= 3)
})
test('Completed vs early skip changes genre and artist affinity', () => {
  const library = [song('a', { genre: 'Rock' }), song('b', { genre: 'Jazz' }), song('c', { genre: 'Rock' })]
  const history = [event(1, 'a', 'start'), event(2, 'a', 'complete', 1), event(3, 'b', 'start'), event(4, 'b', 'skip', 0.1)]
  const model = buildBehaviorModel(library, history, now)
  assert.equal(model.profile.genres[0].name, '摇滚'); assert.ok(model.genreScores.get('爵士') < 0)
  const feed = recommendationFeed(library, history, 3, now)
  assert.ok(feed.songs.findIndex(s => s.id === 'c') < feed.songs.findIndex(s => s.id === 'b'))
})
test('Recent behavior outweighs old behavior; future/orphan events ignored', () => {
  const library = [song('a'), song('b')]
  const model = buildBehaviorModel(library, [event(1, 'a', 'complete', 1, now - 90 * day), event(2, 'b', 'complete', 1), event(3, 'missing', 'complete', 1), event(4, 'b', 'complete', 1, now + day)], now)
  assert.ok(model.trackScores.get('b') > model.trackScores.get('a') * 3)
  assert.equal(model.profile.completedSessions, 2)
})
test('Repeated same-day loops are capped; favorites count without history', () => {
  const history = Array.from({ length: 50 }, (_, i) => [event(i * 2, 'a', 'start'), event(i * 2 + 1, 'a', 'complete', 1)]).flat()
  const model = buildBehaviorModel([song('a'), song('b', { isFavorite: 1, genre: 'Folk' })], history, now)
  assert.ok(model.trackScores.get('a') <= 6); assert.equal(model.trackScores.get('b'), 5)
})
test('Single-artist and unknown-artist libraries still fill recommendations', () => {
  for (const artist of ['Same artist', '未知艺术家']) {
    const feed = recommendationFeed(Array.from({ length: 40 }, (_, i) => song(String(i), { artist })), [], 30, now)
    assert.equal(feed.songs.length, 30); assert.equal(new Set(feed.songs.map(s => s.id)).size, 30)
  }
})
test('Whole-library retrieval reaches song 3001, outside the old top 240', () => {
  const library = [...Array.from({ length: 3000 }, (_, i) => song(String(i), { isFavorite: 1, genre: 'Pop' })), song('rare', { artist: '稀有歌手', genre: 'Rock' })]
  const model = buildBehaviorModel(library, [], now)
  const retrieved = retrieveCandidates(library, model, '听稀有歌手的摇滚', localIntent('听稀有歌手的摇滚'))
  assert.deepEqual(retrieved.candidates.map(s => s.id), ['rare'])
})
test('AI scene keywords can recall a low-history album from the whole library', () => {
  const library = [...Array.from({ length: 500 }, (_, i) => song(String(i), { isFavorite: 1 })), song('night', { album: 'Night Runner' })]
  const intent = { ...localIntent('夜跑'), keywords: ['Night Runner'] }
  const retrieved = retrieveCandidates(library, buildBehaviorModel(library, [], now), '夜跑', intent)
  assert.ok(retrieved.candidates.some(s => s.id === 'night')); assert.equal(retrieved.candidates.length, 240)
})
test('Explicit exclusions and genre filters are enforced, not silently broadened', () => {
  const library = [song('a', { genre: 'Rock' }), song('b', { genre: 'Jazz' }), song('c')]
  const model = buildBehaviorModel(library, [], now)
  assert.deepEqual(retrieveCandidates(library, model, '爵士，不要摇滚', localIntent('爵士，不要摇滚')).candidates.map(s => s.id), ['b'])
  assert.equal(retrieveCandidates(library, model, '古典', localIntent('古典')).candidates.length, 0)
})
test('Language comes only from explicit tags, not Chinese titles', () => {
  const library = [song('a', { title: '中文歌名' }), song('b', { genre: 'English Pop' }), song('c', { remotePath: '/华语/某歌.m4a' })]
  const retrieved = retrieveCandidates(library, buildBehaviorModel(library, [], now), '华语', localIntent('华语'))
  assert.equal(retrieved.candidates[0].id, 'c'); assert.ok(!retrieved.candidates.some(s => s.id === 'b')); assert.ok(retrieved.warnings.some(w => w.includes('语种')))
})
test('Duration parsing: Chinese and Arabic numbers, half hour, hours and counts', () => {
  for (const prompt of ['40分钟夜跑', '四十分钟夜跑', '40 minutes running']) assert.equal(localIntent(prompt).durationSeconds, 2400)
  assert.equal(localIntent('半小时爵士').durationSeconds, 1800); assert.equal(localIntent('一个小时').durationSeconds, 3600)
  assert.equal(localIntent('12 首民谣').count, 12)
})
test('Local explicit duration overrides AI; malformed/oversized plans are bounded', () => {
  const ai = validateIntent({ scene: 'run', keywords: ['a'], artists: Array(100).fill('a'), durationSeconds: 999999, count: 999 })
  assert.equal(ai.durationSeconds, 21600); assert.equal(ai.count, 80); assert.equal(ai.artists.length, 1)
  assert.equal(mergeIntent(localIntent('40分钟夜跑'), ai).durationSeconds, 2400)
  assert.throws(() => validateIntent({ songIds: ['a'] }))
})
test('Forty-minute playlists: duration fit, no duplicate IDs, count caps', () => {
  const library = Array.from({ length: 50 }, (_, i) => song(String(i)))
  const result = fitPlaylist([...library, ...library], localIntent('40分钟'))
  assert.equal(result.length, 10); assert.equal(result.reduce((sum, s) => sum + s.duration, 0), 2400)
  assert.equal(fitPlaylist(library, localIntent('5首')).length, 5)
  assert.equal(fitPlaylist(library, localIntent('5首40分钟')).length, 5)
})
test('Unknown durations use estimates; tiny/empty libraries are safe', () => {
  assert.equal(fitPlaylist(Array.from({ length: 20 }, (_, i) => song(String(i), { duration: 0 })), localIntent('40分钟')).length, 10)
  assert.equal(fitPlaylist([song('long', { duration: 3600 })], localIntent('5分钟')).length, 1)
  assert.deepEqual(fitPlaylist([], localIntent('40分钟')), [])
})
test('Genre alias normalization and deterministic daily recommendations', () => {
  assert.deepEqual(genresOf(song('a', { genre: 'Rock; Jazz' })), ['摇滚', '爵士'])
  const library = Array.from({ length: 100 }, (_, i) => song(String(i)))
  assert.deepEqual(recommendationFeed(library, [], 30, now), recommendationFeed(library, [], 30, now))
})
test('Daily recommendations reserve room for discovery despite many favorites', () => {
  const library = [...Array.from({ length: 80 }, (_, i) => song(String(i), { isFavorite: 1 })), song('new')]
  const feed = recommendationFeed(library, [], 10, now)
  assert.ok(feed.songs.some(s => s.id === 'new')); assert.equal(new Set(feed.songs.map(s => s.id)).size, 10)
})
test('Uncached filename-only artist hints participate in full-library recall', () => {
  const library = [...Array.from({ length: 400 }, (_, i) => song(String(i), { isFavorite: 1 })), song('filename', { title: '测试歌手-夜跑', artist: '未知艺术家' })]
  const intent = { ...localIntent('夜跑'), artists: ['测试歌手'] }
  assert.equal(retrieveCandidates(library, buildBehaviorModel(library, [], now), '夜跑', intent).candidates[0].id, 'filename')
})
console.log(`RECOMMENDATION_PASS: ${count} cases; no API calls or user-data writes`)
