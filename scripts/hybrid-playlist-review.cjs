// Credential-free regression tests: every network response and database is a fixture.
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const Module = require('node:module')
const ts = require('typescript')
require.extensions['.ts'] = (module, file) => module._compile(ts.transpileModule(fs.readFileSync(file, 'utf8'), {
  compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.CommonJS },
}).outputText, file)
const { buildAiRequest, extractAiJson } = require('../src/services/aiProtocol.ts')
const { CandidateStream } = require('../src/services/candidateStream.ts')
const { generateHybridPlaylistCore } = require('../src/services/hybridPlaylist.ts')
const { buildBehaviorModel, localIntent } = require('../src/services/recommendation.ts')
const originalLoad = Module._load
Module._load = function (request, parent, ...rest) {
  if (request === './secrets' && parent.filename.endsWith(path.join('main', 'aiProviders.ts')))
    return { encryptSecret: value => 'fixture:' + Buffer.from(value).toString('base64'), decryptSecret: value => value.startsWith('fixture:') ? Buffer.from(value.slice(8), 'base64').toString() : value }
  return originalLoad.call(this, request, parent, ...rest)
}
const providers = require('../src/main/aiProviders.ts')
Module._load = originalLoad
const song = (id, extra = {}) => ({ id, title: 'Track ' + id, artist: 'Artist ' + id, album: 'Album', genre: 'Pop', year: null,
  duration: 240, filePath: '/private/file.m4a', remotePath: 'https://dav-private.example/music/' + id + '.m4a', sourceType: 'webdav', format: 'm4a',
  coverPath: null, customArtistImage: null, customLyrics: 'private lyric', createdAt: '', ...extra })
const service = (id, extra = {}) => ({ id, name: id, model: 'fixture-model', protocol: 'responses', baseUrl: 'https://example.com/v1', priority: 1, enabled: true, secret: 'fixture-credential', ...extra })
const db = input => {
  let stored = JSON.stringify(input)
  return { prepare: () => ({ get: () => ({ value: stored }), run: (_key, value) => { stored = value } }) }
}
let passed = 0
async function test(name, task) { await task(); passed++; console.log('PASS ' + name) }
async function main() {
  await test('Responses request is stateless, uses instructions/input and never duplicates endpoint', () => {
    for (const baseUrl of ['https://example.com/v1', 'https://example.com/v1/responses']) {
      const request = buildAiRequest({ protocol: 'responses', baseUrl, model: 'fixture-model' }, 'fixture', 'system', 'user')
      assert.equal(request.url, 'https://example.com/v1/responses'); assert.equal(request.body.store, false)
      assert.equal(request.body.instructions, 'system'); assert.equal(request.body.input, 'user'); assert.equal(request.body.messages, undefined)
    }
  })
  await test('Raw Responses output ignores reasoning and extracts only assistant output_text', () => {
    const payload = { status: 'completed', output: [{ type: 'reasoning', content: [{ type: 'output_text', text: '{"bad":true}' }] },
      { type: 'message', role: 'assistant', content: [{ type: 'output_text', text: '{"ok":true}' }] }] }
    assert.deepEqual(extractAiJson('responses', payload), { ok: true })
    assert.throws(() => extractAiJson('responses', { status: 'incomplete', output_text: '{"ok":true}' }))
    assert.throws(() => extractAiJson('responses', { output: [{ type: 'message', role: 'assistant', content: [{ type: 'refusal', refusal: 'No' }] }] }))
  })
  await test('Other protocol adapters preserve endpoints and skip Gemini thought content', () => {
    assert.equal(buildAiRequest({ protocol: 'openai', baseUrl: 'https://example.com/v1/chat/completions', model: 'x' }, 'fixture', 's', 'u').url, 'https://example.com/v1/chat/completions')
    assert.equal(buildAiRequest({ protocol: 'anthropic', baseUrl: 'https://example.com', model: 'x' }, 'fixture', 's', 'u').url, 'https://example.com/v1/messages')
    assert.deepEqual(extractAiJson('anthropic', { content: [{ type: 'text', text: '```json\n{"ok":true}\n```' }] }), { ok: true })
    assert.deepEqual(extractAiJson('gemini', { candidates: [{ content: { parts: [{ thought: true, text: 'not JSON' }, { text: '{"ok":true}' }] } }] }), { ok: true })
  })
  await test('Streaming discovery at track 3001 constrains every earlier page to the named artist', () => {
    const seed = Array.from({ length: 600 }, (_, i) => song('seed-' + i, { isFavorite: 1 }))
    const collector = new CandidateStream(buildBehaviorModel(seed, []), '听稀有歌手的摇滚', localIntent('听稀有歌手的摇滚'))
    for (let page = 0; page < 15; page++) collector.accept(Array.from({ length: 200 }, (_, i) => song(String(page * 200 + i))))
    collector.accept([song('rare', { artist: '稀有歌手', genre: 'Rock' })])
    assert.deepEqual(collector.finish().candidates.map(item => item.id), ['rare']); assert.equal(collector.finish().eligibleSize, 1)
  })
  await test('Empty explicit-artist result is not broadened to another artist', () => {
    const collector = new CandidateStream(buildBehaviorModel([], []), '稀有歌手的爵士', localIntent('稀有歌手的爵士'))
    collector.accept([song('jazz', { genre: 'Jazz' })]); collector.accept([song('rare', { artist: '稀有歌手', genre: 'Rock' })])
    assert.equal(collector.finish().candidates.length, 0)
  })
  await test('Large catalog retains bounded metadata and still finds a rare final-page title', () => {
    const collector = new CandidateStream(buildBehaviorModel([], []), 'Special Ending', localIntent('Special Ending'))
    for (let page = 0; page < 250; page++) {
      collector.accept(Array.from({ length: 200 }, (_, i) => song(String(page * 200 + i))))
      assert.ok(collector.retainedCount <= 1920)
    }
    collector.accept([song('last', { title: 'Special Ending' })])
    assert.equal(collector.finish().candidates[0].id, 'last'); assert.equal(collector.finish().eligibleSize, 50001)
  })
  const songs = Array.from({ length: 30 }, (_, i) => song(String(i)))
  await test('Hybrid generation fits duration and emits metadata without URLs/lyrics/history', async () => {
    const requests = []
    const result = await generateHybridPlaylistCore({ songs, history: [], prompt: '40分钟', configured: true,
      requestJson: async (stage, system, user, validate) => {
        requests.push(user)
        const data = stage === 'plan' ? { scene: 'any', keywords: [] } : { name: 'Fixture', description: 'Fixture', songIds: JSON.parse(user).catalog.map(item => item.id) }
        return { data: validate(data), providerName: 'Fixture', attempts: [] }
      } })
    assert.equal(result.songIds.length, 10); assert.equal(result.diagnostics.durationSeconds, 2400)
    assert.equal(result.source, 'hybrid')
    assert.ok(requests.every(value => !value.includes('dav-private.example') && !value.includes('private lyric') && !value.includes('filePath')))
  })
  await test('No API configured, or explicit local mode, never invokes transport', async () => {
    for (const input of [{ configured: false }, { configured: true, useAi: false }]) {
      const result = await generateHybridPlaylistCore({ songs, history: [], prompt: '40分钟', ...input, requestJson: () => { throw new Error('Transport must not run') } })
      assert.equal(result.source, 'local'); assert.equal(result.songIds.length, 10)
    }
  })
  await test('All API failures retain an explainable, complete local playlist', async () => {
    const result = await generateHybridPlaylistCore({ songs, history: [], prompt: '40分钟', configured: true, requestJson: async () => { throw new Error('Fixture unavailable') } })
    assert.equal(result.source, 'local'); assert.equal(result.songIds.length, 10); assert.equal(result.attempts.length, 2)
  })
  await test('Foreign song IDs cannot enter a playlist; short valid AI lists remain short', async () => {
    const result = await generateHybridPlaylistCore({ songs, history: [], prompt: '随便听听', configured: true,
      requestJson: async (stage, _system, user, validate) => ({ data: validate(stage === 'plan' ? { scene: 'any', keywords: [] }
        : { songIds: ['not-in-library', JSON.parse(user).catalog[0].id, JSON.parse(user).catalog[0].id, JSON.parse(user).catalog[1].id] }), providerName: 'Fixture', attempts: [] }) })
    assert.equal(result.songIds.length, 2); assert.ok(!result.songIds.includes('not-in-library'))
  })
  const originalFetch = global.fetch
  try {
    await test('Priority failover handles HTTP errors and semantically invalid JSON before accepting next service', async () => {
      let calls = 0
      global.fetch = async () => { calls++; return calls === 1 ? new Response('Never echo credential-bearing errors', { status: 503 }) : new Response(JSON.stringify({ output_text: calls === 2 ? '{"wrong":true}' : '{"ok":true}' })) }
      const result = await providers.requestJsonWithProviders(db([service('one'), service('two', { priority: 2 }), service('three', { priority: 3 })]), 's', 'u', value => { assert.equal(value.ok, true); return value })
      assert.equal(calls, 3); assert.equal(result.providerName, 'three'); assert.equal(result.attempts.length, 2)
    })
    await test('Oversized response is bounded while reading and switches to next service', async () => {
      let calls = 0
      global.fetch = async () => new Response(++calls === 1 ? 'x'.repeat(2_000_001) : JSON.stringify({ output_text: '{"ok":true}' }))
      const result = await providers.requestJsonWithProviders(db([service('one'), service('two', { priority: 2 })]), 's', 'u', value => value)
      assert.equal(calls, 2); assert.equal(result.providerName, 'two')
    })
    await test('Expired generation deadline makes no network requests', async () => {
      global.fetch = () => { throw new Error('Must not request after deadline') }
      await assert.rejects(providers.requestJsonWithProviders(db([service('one')]), 's', 'u', value => value, Date.now() - 1))
    })
  } finally { global.fetch = originalFetch }
  await test('Saved key is retained only for an unchanged URL and never returned to UI', () => {
    const database = db([service('one')]), item = { ...providers.getProviders(database)[0], apiKey: '' }
    assert.equal(providers.saveProviders(database, [item])[0].configured, true)
    assert.equal(providers.getProviders(database)[0].secret, undefined); assert.equal(providers.getProviders(database)[0].apiKey, undefined)
    assert.throws(() => providers.saveProviders(database, [{ ...item, baseUrl: 'https://another.example.com/v1' }]))
  })
  console.log(`HYBRID_PASS: ${passed} cases; fixture-only database/network, no real API calls`)
}
main().catch(error => { console.error(error); process.exitCode = 1 })
