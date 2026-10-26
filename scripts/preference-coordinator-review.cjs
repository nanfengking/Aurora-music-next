// Executes the real renderer coordinator and real Zustand. Only IPC timing is controlled.
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const Module = require('node:module')
const ts = require('typescript')
const root = path.resolve(__dirname, '..')
const compile = (module, file) => module._compile(ts.transpileModule(fs.readFileSync(file, 'utf8'), {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS, esModuleInterop: true },
}).outputText, file)
require.extensions['.ts'] = compile
const flush = () => new Promise(resolve => setImmediate(resolve))

async function fixture() {
  const source = path.join(root, 'src/stores/preferencePersistence.ts')
  const instance = new Module(source, module)
  instance.filename = source
  instance.paths = Module._nodeModulePaths(path.dirname(source))
  compile(instance, source)
  const coordinator = instance.exports
  let current = { version: 1, revision: 1, restoreRevision: 0, values: {
    theme: 'light', locale: 'zh-CN', playMode: 'sequential', volume: 0.8,
    rememberPlayback: true, alwaysOnTop: false, prefetch: false, normalizeVolume: false,
    crossfadeSeconds: 0, lyricsSize: 24, showBackdrop: true, accent: 'rose',
  } }
  let shown = null
  let failures = 0
  let hold = null
  const calls = []
  global.window = { electronAPI: {
    onPreferencesChanged: () => () => {},
    initializePreferences: async () => ({ success: true, data: current }),
    updatePreferences: async patch => {
      calls.push({ ...patch })
      if (failures > 0) { failures--; return { success: false } }
      if (hold) { const gate = hold; hold = null; await gate }
      current = { ...current, revision: current.revision + 1, values: { ...current.values, ...patch } }
      return { success: true, data: current }
    },
  } }
  await coordinator.connectDesktopPreferences({}, snapshot => { shown = snapshot })
  return { coordinator, calls, get shown() { return shown }, fail: count => { failures = count },
    hold: () => { let release; hold = new Promise(resolve => { release = resolve }); return () => release() } }
}

let failures = 0
async function test(name, body) {
  try { await body(); console.log('PASS', name) }
  catch (error) { failures++; console.error('FAIL', name, error.message) }
}

;(async () => {
  await test('New successful value supersedes an older failed value without a stale retry warning', async () => {
    const item = await fixture()
    item.fail(1)
    assert.equal(await item.coordinator.persistDesktopPreferences({ volume: 0.2 }), false)
    assert.equal(await item.coordinator.persistDesktopPreferences({ volume: 0.6 }), true)
    assert.equal(item.coordinator.usePreferencePersistence.getState().error, false)
    item.coordinator.retryDesktopPreferences()
    await flush()
    assert.equal(item.shown.values.volume, 0.6)
    assert.equal(item.calls.length, 2, 'empty retry must not issue a redundant native write')
  })
  await test('Retry queued behind a newer intent cannot restore a captured obsolete value', async () => {
    const item = await fixture()
    item.fail(1)
    await item.coordinator.persistDesktopPreferences({ volume: 0.2 })
    const newer = item.coordinator.persistDesktopPreferences({ volume: 0.6 })
    item.coordinator.retryDesktopPreferences()
    assert.equal(await newer, true)
    await flush()
    assert.equal(item.shown.values.volume, 0.6)
    assert.deepEqual(item.calls, [{ volume: 0.2 }, { volume: 0.6 }])
  })
  await test('Retry uses the latest failure when a queued newer choice also fails', async () => {
    const item = await fixture()
    item.fail(2)
    await item.coordinator.persistDesktopPreferences({ volume: 0.2 })
    const newer = item.coordinator.persistDesktopPreferences({ volume: 0.6 })
    item.coordinator.retryDesktopPreferences()
    assert.equal(await newer, false)
    await flush()
    assert.equal(item.shown.values.volume, 0.6)
    assert.equal(item.coordinator.usePreferencePersistence.getState().error, false)
  })
  await test('A successful field does not discard an unrelated failed field', async () => {
    const item = await fixture()
    item.fail(1)
    await item.coordinator.persistDesktopPreferences({ volume: 0.2, theme: 'dark' })
    await item.coordinator.persistDesktopPreferences({ volume: 0.6 })
    assert.equal(item.coordinator.usePreferencePersistence.getState().error, true)
    item.coordinator.retryDesktopPreferences()
    await flush()
    assert.equal(item.shown.values.volume, 0.6)
    assert.equal(item.shown.values.theme, 'dark')
    assert.deepEqual(item.calls.at(-1), { theme: 'dark' })
  })
  await test('Delayed acknowledgement does not optimistically publish uncommitted preferences', async () => {
    const item = await fixture()
    const release = item.hold()
    const saved = item.coordinator.persistDesktopPreferences({ volume: 0.6 })
    await flush()
    assert.equal(item.shown.values.volume, 0.8)
    release()
    assert.equal(await saved, true)
    assert.equal(item.shown.values.volume, 0.6)
  })
  if (failures) process.exitCode = 1
  else console.log('PREFERENCE_COORDINATOR_PASS: 5 actual-coordinator ordering and acknowledgement cases; IPC transport controlled')
})().catch(error => { console.error(error); process.exitCode = 1 })
