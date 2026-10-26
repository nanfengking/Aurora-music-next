// Production repository + real Electron SQLite. All writes use a disposable project-local profile.
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const root = path.resolve(__dirname, '..')

async function check() {
  const { app } = require('electron')
  const profile = path.resolve(process.env.AURORA_PREFERENCE_PROFILE || '')
  assert.equal(path.dirname(profile), root)
  assert.ok(path.basename(profile).startsWith('.ui-review-preferences-'))
  app.setPath('userData', profile)
  app.setPath('sessionData', profile)
  await app.whenReady()
  const source = path.join(root, 'src/main/preferences.ts')
  assert.ok(fs.existsSync(source), 'Missing SQLite-authoritative preference repository')
  const ts = require('typescript')
  require.extensions['.ts'] = (module, file) => module._compile(ts.transpileModule(fs.readFileSync(file, 'utf8'), {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS, esModuleInterop: true },
  }).outputText, file)
  const { DesktopPreferenceRepository, DESKTOP_PREFERENCES_KEY } = require(source)
  const Database = require('better-sqlite3')
  const db = new Database(path.join(profile, 'preferences.db'))
  db.exec('CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT)')
  const readRaw = () => db.prepare('SELECT value FROM settings WHERE key = ?').get(DESKTOP_PREFERENCES_KEY)?.value
  const setRaw = value => db.prepare('INSERT OR REPLACE INTO settings VALUES (?,?)').run(DESKTOP_PREFERENCES_KEY, value)
  try {
    let repo = new DesktopPreferenceRepository(db)
    const initial = repo.initialize({ theme: 'dark', locale: 'fr', volume: 0.3, prefetch: true,
      lyricsSize: 30, crossfadeSeconds: 2, playMode: 'repeat-one', password: 'never-copy-me' })
    assert.deepEqual(initial, { version: 1, revision: 1, restoreRevision: 0, values: {
      theme: 'dark', locale: 'fr', playMode: 'repeat-one', volume: 0.3,
      rememberPlayback: true, alwaysOnTop: false, prefetch: true, normalizeVolume: false,
      crossfadeSeconds: 2, lyricsSize: 30, showBackdrop: true, accent: 'rose',
    } })
    assert.ok(!readRaw().includes('never-copy-me'))
    const once = readRaw()
    repo = new DesktopPreferenceRepository(db)
    assert.deepEqual(repo.initialize({ theme: 'light', locale: 'en', prefetch: false }), initial)
    assert.equal(readRaw(), once, 'reopening must not replace canonical values with stale browser values')
    console.log('PREFERENCES_MIGRATION_PASS: whitelist, defaults, durable reopen and canonical precedence')

    const changed = repo.update({ theme: 'system', volume: 0 })
    assert.equal(changed.revision, 2)
    assert.equal(changed.values.theme, 'system')
    assert.equal(changed.values.volume, 0)
    assert.equal(changed.values.locale, 'fr')
    assert.equal(repo.update({ rememberPlayback: false }).restoreRevision, 1)
    assert.equal(repo.update({ rememberPlayback: false }).restoreRevision, 1)
    assert.equal(repo.update({ rememberPlayback: true }).restoreRevision, 2)
    for (const patch of [{ password: 'x' }, { restoreRevision: 0 }, { prefetch: 1 }, { theme: 'blue' },
      { volume: NaN }, { volume: Infinity }, { volume: -0.01 }, { volume: 1.01 },
      { crossfadeSeconds: 0.5 }, { lyricsSize: 100 }, { locale: 'de' }, { playMode: 'random' },
      null, [], 'theme', { constructor: 'bad' }]) {
      const before = readRaw()
      assert.throws(() => repo.update(patch), /preference/i)
      assert.equal(readRaw(), before)
    }
    assert.equal(repo.update({ volume: 1, accent: 'violet', showBackdrop: false }).values.volume, 1)
    console.log('PREFERENCES_VALIDATION_PASS: exact enums, finite bounds, Boolean and internal-field rejection')

    const beforeFailure = readRaw()
    db.exec(`CREATE TEMP TRIGGER fail_preferences BEFORE INSERT ON settings
      WHEN NEW.key = '${DESKTOP_PREFERENCES_KEY}' BEGIN SELECT RAISE(ABORT, 'fixture storage failure'); END`)
    assert.throws(() => repo.update({ theme: 'light', locale: 'en' }), /fixture storage failure/)
    assert.equal(readRaw(), beforeFailure)
    db.exec('DROP TRIGGER fail_preferences')
    assert.equal(repo.initialize({}).values.theme, 'system')
    console.log('PREFERENCES_ROLLBACK_PASS: real trigger failure retains previous committed generation')

    for (const invalid of ['{', 'null', JSON.stringify({ ...initial, version: 2 }),
      JSON.stringify({ ...initial, revision: -1 }), JSON.stringify({ ...initial, values: { theme: 'dark' } }),
      JSON.stringify({ ...initial, values: { ...initial.values, password: 'not-allowed' } })]) {
      setRaw(invalid)
      assert.throws(() => new DesktopPreferenceRepository(db).initialize({ theme: 'light' }), /preference/i)
      assert.equal(readRaw(), invalid, 'corrupt/future/overflow state must never reset itself')
    }
    const overflow = JSON.stringify({ ...initial, restoreRevision: Number.MAX_SAFE_INTEGER, values: { ...initial.values, rememberPlayback: false } })
    setRaw(overflow)
    assert.throws(() => new DesktopPreferenceRepository(db).update({ rememberPlayback: true }), /preference/i)
    assert.equal(readRaw(), overflow)
    db.prepare('DELETE FROM settings WHERE key = ?').run(DESKTOP_PREFERENCES_KEY)
    const clean = new DesktopPreferenceRepository(db).initialize({ prefetch: 'true', volume: 3, theme: 'invalid' })
    assert.equal(clean.values.prefetch, false)
    assert.equal(clean.values.volume, 0.8)
    assert.equal(clean.values.theme, 'light')
    console.log('PREFERENCES_CORRUPTION_PASS: malformed/future records refused, invalid legacy values safely defaulted')
  } finally { db.close() }
}

if (process.versions.electron) {
  check().then(() => require('electron').app.exit(0), error => { console.error(error); require('electron').app.exit(1) })
} else {
  const { promisify } = require('node:util')
  const execFile = promisify(require('node:child_process').execFile)
  ;(async () => {
    const profile = fs.mkdtempSync(path.join(root, '.ui-review-preferences-'))
    const env = { ...process.env, AURORA_PREFERENCE_PROFILE: profile }
    delete env.ELECTRON_RUN_AS_NODE
    try {
      const result = await execFile(require('electron'), [__filename], { cwd: root, env, windowsHide: true, timeout: 30000 })
      process.stdout.write(result.stdout)
      assert.match(result.stdout, /PREFERENCES_CORRUPTION_PASS:/)
    } catch (error) {
      if (error.stdout) process.stdout.write(error.stdout)
      if (error.stderr) process.stderr.write(error.stderr)
      throw error
    } finally {
      assert.equal(path.dirname(profile), root)
      assert.ok(path.basename(profile).startsWith('.ui-review-preferences-'))
      assert.equal(fs.lstatSync(profile).isSymbolicLink(), false)
      fs.rmSync(profile, { recursive: true, force: true, maxRetries: 5, retryDelay: 250 })
    }
  })().catch(error => { console.error(error.message); process.exitCode = 1 })
}
