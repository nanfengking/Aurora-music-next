// Test the real audio engine with a controlled Howler/IPC boundary; never plays sound.
const fs = require('node:fs')
const path = require('node:path')
const Module = require('node:module')
const assert = require('node:assert/strict')
const ts = require('typescript')
const events = []
class FakeHowl {
  constructor(options) { this.options = options; this.position = 0; this.active = false; FakeHowl.last = this }
  play() { this.active = true }
  pause() { this.active = false; this.options.onpause?.() }
  unload() { this.active = false }
  playing() { return this.active }
  seek(value) { if (value !== undefined) this.position = value; return this.position }
  duration() { return 100 }
  volume() {}
}
const state = { volume: 0, playMode: 'sequential', setQueue() {}, setQueueIndex() {}, setPlayMode(mode) { this.playMode = mode } }
global.window = { dispatchEvent() {}, electronAPI: {
  getAudioUrl: async () => ({ success: true, data: 'local-protocol://test/test.flac' }),
  recordPlayAction: async record => { events.push(record); return { success: true } },
} }
const file = path.resolve(__dirname, '../src/services/audioEngine.ts')
const compiled = ts.transpileModule(fs.readFileSync(file, 'utf8'), { compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.CommonJS } })
const compiledModule = new Module(file, module)
compiledModule.filename = file; compiledModule.paths = module.paths
compiledModule.require = name => name === 'howler' ? { Howl: FakeHowl } : name.includes('playerStore') ? { usePlayerStore: { getState: () => state } } : name.includes('i18n') ? { t: (text, values = []) => text.replace(/\{(\d+)\}/g, (_, index) => String(values[index])) } : require(name)
compiledModule._compile(compiled.outputText, file)
const { audioEngine: engine } = compiledModule.exports
const a = { id: 'a', title: 'A', filePath: '/a.flac', sourceType: 'local', format: 'FLAC', duration: 100 }
const b = { ...a, id: 'b', title: 'B', filePath: '/b.flac' }

async function run() {
  try {
    await engine.play(a)
    assert.equal(events.length, 0, 'calling play before onplay cannot count a start')
    const stale = FakeHowl.last
    stale.options.onloaderror(1, 'fixture decode failure')
    await engine.play(b)
    assert.equal(events.length, 0, 'failed playback cannot count a start or skip')
    stale.options.onplay()
    assert.equal(events.length, 0, 'late callback cannot record the wrong track')
    FakeHowl.last.options.onplay()
    assert.equal(events.filter(e => e.action === 'start').length, 1)
    engine.pause(); engine.resume(); FakeHowl.last.options.onplay()
    assert.equal(events.filter(e => e.action === 'start').length, 1, 'pause/resume must not count a second start')
    engine.seek(99)
    await engine.play(a)
    assert.equal(events.filter(e => e.action === 'skip').length, 1)
    assert.ok(events.find(e => e.action === 'skip').progress < 0.1, 'seek position is not time listened')
    FakeHowl.last.options.onplay()
    engine.seek(99)
    FakeHowl.last.options.onend()
    engine.pause()
    assert.equal(events.filter(e => e.action === 'complete').length, 0, 'jumping to the end is not a completed listen')
    // Invalidate the queued auto-next before cleanup.
    await engine.play(b)
    assert.equal(events.filter(e => e.action === 'skip').length, 1, 'automatic end does not record a skip')
    console.log('AUDIO_HISTORY_PASS: failed loads, stale callbacks, pause/resume, seek, direct switches and auto-end')
  } finally { engine.destroy() }
}
run().catch(error => { console.error(error); process.exitCode = 1 })
