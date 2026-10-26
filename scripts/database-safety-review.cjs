// Actual Electron + production SQLite initialization. Only disposable profiles.
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const { execFile, spawn } = require('node:child_process')
const root = path.resolve(__dirname, '..')
const allowed = ['future', 'migration', 'wal', 'future-wal', 'startup']

function productionDatabase() {
  const Module = require('node:module'), ts = require('typescript')
  const filename = path.join(root, 'src/main/database.ts')
  const compiled = ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS, esModuleInterop: true },
  })
  const instance = new Module(filename, module)
  instance.filename = filename
  instance.paths = Module._nodeModulePaths(path.dirname(filename))
  instance._compile(compiled.outputText, filename)
  return instance.exports
}

function seedDatabase(Database, filename, scenario) {
  const seed = new Database(filename)
  try {
    seed.exec(`
      CREATE TABLE songs (id TEXT PRIMARY KEY,title TEXT NOT NULL,artist TEXT,album TEXT,
        year INTEGER,genre TEXT,duration REAL,filePath TEXT UNIQUE NOT NULL,format TEXT,
        coverPath TEXT,customArtistImage TEXT,customLyrics TEXT,sourceType TEXT,isFavorite INTEGER);
      INSERT INTO songs(id,title,duration,filePath,sourceType,isFavorite)
        VALUES ('kept','Original Song',60,'https://fixture.invalid/song.wav','webdav',1);
      CREATE TABLE settings (key TEXT PRIMARY KEY,value TEXT);
      INSERT INTO settings VALUES ('theme','dark');
      CREATE TABLE play_history (id INTEGER PRIMARY KEY,songId TEXT,action TEXT,progress REAL);
    `)
    if (scenario === 'future') {
      seed.exec('ALTER TABLE play_history ADD COLUMN timestamp INTEGER; CREATE TABLE future_marker(note TEXT);')
      seed.prepare('INSERT INTO future_marker VALUES (?)').run('must survive')
      seed.pragma('user_version = 4')
    } else seed.pragma('user_version = 1')
  } finally { seed.close() }
}

function snapshot(Database, filename, readonly = false) {
  const inspect = new Database(filename, { readonly, fileMustExist: true })
  try {
    return {
      schema: inspect.prepare("SELECT type,name,sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY type,name").all(),
      version: inspect.pragma('user_version', { simple: true }),
      songs: inspect.prepare('SELECT id,title,isFavorite FROM songs ORDER BY id').all(),
      settings: inspect.prepare('SELECT * FROM settings ORDER BY key').all(),
    }
  } finally { inspect.close() }
}

async function checkDatabase(scenario) {
  const { app } = require('electron')
  const profile = path.resolve(process.env.AURORA_DB_SAFETY_PROFILE || '')
  assert.ok(profile.startsWith(root + path.sep + '.ui-review-safety-'), 'Requires an isolated project profile')
  assert.equal(fs.lstatSync(profile).isSymbolicLink(), false)
  app.setPath('userData', profile)
  app.setPath('sessionData', profile)
  await app.whenReady()
  const Database = require('better-sqlite3')
  const filename = path.join(profile, 'musicPlayer.db')
  if (scenario === 'future-wal') return checkFutureWal(Database, filename)
  if (scenario.startsWith('wal-')) return checkWal(filename, scenario)
  seedDatabase(Database, filename, scenario)
  const before = snapshot(Database, filename)
  const beforeHash = crypto.createHash('sha256').update(fs.readFileSync(filename)).digest('hex')
  const database = productionDatabase()
  let failure
  try {
    try { database.initDatabase() } catch (error) { failure = error }
    if (scenario === 'future') {
      assert.match(failure?.message || 'NO_ERROR: future database accepted', /newer/i)
      assert.deepEqual(snapshot(Database, filename), before, 'Future database must be unchanged')
      assert.equal(crypto.createHash('sha256').update(fs.readFileSync(filename)).digest('hex'), beforeHash)
      const inspect = new Database(filename)
      try { assert.equal(inspect.prepare('SELECT note FROM future_marker').get().note, 'must survive') }
      finally { inspect.close() }
    } else {
      assert.match(failure?.message || 'NO_ERROR', /timestamp/i, 'Fixture must fail on its real missing-column SQL')
      assert.deepEqual(snapshot(Database, filename), before, 'Failed migration must roll back every DDL/data/version change')
    }
    assert.throws(() => database.getDb(), /not initialized/i, 'Failed initialization must not expose a connection')
    // A fresh writer proves no dangling write lock after failure.
    const writer = new Database(filename)
    try { writer.exec('BEGIN IMMEDIATE; ROLLBACK;') } finally { writer.close() }
    console.log(`DATABASE_SAFETY_PASS: ${scenario}`)
  } finally { database.closeDatabase() }
}

function checkFutureWal(Database, filename) {
  const before = snapshot(Database, filename, true)
  assert.equal(before.version, 4, 'Future schema version must exist in the WAL view')
  const files = [filename, filename + '-wal']
  const hashes = files.map(file => crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex'))
  const database = productionDatabase()
  try {
    assert.throws(() => database.initDatabase(), /newer/i)
    assert.ok(fs.existsSync(filename + '-wal'), 'Rejecting a future database must not delete its WAL')
    assert.deepEqual(files.map(file => crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex')), hashes,
      'Future DB and WAL bytes must not change on rejection')
    assert.deepEqual(snapshot(Database, filename, true), before)
    assert.throws(() => database.getDb(), /not initialized/i)
    console.log('DATABASE_SAFETY_PASS: future-wal')
  } finally { database.closeDatabase() }
}

function checkStartup(scenario) {
  const { app, dialog, BrowserWindow } = require('electron')
  const profile = path.resolve(process.env.AURORA_DB_SAFETY_PROFILE || '')
  assert.ok(profile.startsWith(root + path.sep + '.ui-review-safety-'))
  assert.equal(fs.lstatSync(profile).isSymbolicLink(), false)
  app.setPath('userData', profile)
  app.setPath('sessionData', profile)
  const [, mode, locale] = scenario.split(':')
  seedDatabase(require('better-sqlite3'), path.join(profile, 'musicPlayer.db'), mode)
  // Only intercept the OS dialog and locale. Database and application entry are real.
  app.getLocale = () => locale
  dialog.showErrorBox = (title, content) => {
    fs.writeFileSync(path.join(profile, 'startup-result.json'), JSON.stringify({ title, content,
      windows: BrowserWindow.getAllWindows().length }))
  }
  app.once('ready', () => setTimeout(() => app.exit(78), 5000).unref())
  require('../out/main/index.js')
}

function checkWal(filename, scenario) {
  const database = productionDatabase()
  const db = database.initDatabase()
  assert.equal(db.pragma('journal_mode', { simple: true }), 'wal')
  if (scenario === 'wal-write' || scenario === 'wal-future-write') {
    db.pragma('wal_autocheckpoint = 0')
    db.transaction(() => {
      db.exec(`
        INSERT INTO songs(id,title,duration,filePath,sourceType,isFavorite)
          VALUES ('a','Retained A',60,'https://fixture.invalid/a.wav','webdav',1),
                 ('b','Retained B',90,'https://fixture.invalid/b.wav','webdav',0);
        INSERT INTO playlists(id,name,songIds) VALUES ('list','Retained order','["b","a"]');
        INSERT INTO play_history(songId,action,timestamp,progress)
          VALUES ('a','complete',1000,1),('b','pause',2000,0.5);
        INSERT INTO settings VALUES ('committed','keep');
      `)
      if (scenario === 'wal-future-write') {
        db.exec("CREATE TABLE future_marker(note TEXT); INSERT INTO future_marker VALUES ('must survive'); PRAGMA user_version=4;")
      }
    })()
    db.exec(`BEGIN IMMEDIATE;
      UPDATE songs SET isFavorite=0 WHERE id='a';
      UPDATE playlists SET songIds='["a","b"]' WHERE id='list';
      DELETE FROM play_history;
      INSERT INTO settings VALUES ('uncommitted','discard');`)
    const walBytes = fs.statSync(filename + '-wal').size
    assert.ok(walBytes > 0, 'Crash fixture must have actual outstanding WAL records')
    process.stdout.write(`WAL_READY:${process.pid}:${walBytes}\n`)
    setInterval(() => {}, 1000)
    // Parent terminates this exact child; never gracefully close/checkpoint here.
    return new Promise(() => {})
  }
  try {
    assert.equal(db.pragma('user_version', { simple: true }), 3)
    assert.equal(db.pragma('integrity_check', { simple: true }), 'ok')
    assert.deepEqual(db.prepare('SELECT id,title,isFavorite FROM songs ORDER BY id').all(), [
      { id: 'a', title: 'Retained A', isFavorite: 1 }, { id: 'b', title: 'Retained B', isFavorite: 0 },
    ])
    assert.equal(db.prepare("SELECT songIds FROM playlists WHERE id='list'").get().songIds, '["b","a"]')
    assert.deepEqual(db.prepare('SELECT songId,action,timestamp,progress FROM play_history ORDER BY id').all(), [
      { songId: 'a', action: 'complete', timestamp: 1000, progress: 1 },
      { songId: 'b', action: 'pause', timestamp: 2000, progress: 0.5 },
    ])
    assert.deepEqual(db.prepare('SELECT * FROM settings').all(), [{ key: 'committed', value: 'keep' }])
    console.log('DATABASE_SAFETY_PASS: wal-recover')
  } finally { database.closeDatabase() }
}

function removeProfile(profile) {
  if (profile.startsWith(root + path.sep + '.ui-review-safety-') && !fs.lstatSync(profile).isSymbolicLink())
    fs.rmSync(profile, { recursive: true, force: true, maxRetries: 5, retryDelay: 250 })
}

function runChild(scenario, sharedProfile) {
  const profile = sharedProfile || fs.mkdtempSync(path.join(root, '.ui-review-safety-'))
  const env = { ...process.env, AURORA_DB_SAFETY_PROFILE: profile }
  delete env.ELECTRON_RUN_AS_NODE
  return new Promise((resolve, reject) => {
    execFile(require('electron'), [__filename, '--child', scenario], { cwd: root, env, windowsHide: true, timeout: 30000 }, (error, stdout, stderr) => {
      try {
        if (stdout) process.stdout.write(stdout)
        if (stderr) process.stderr.write(stderr)
        if (error) throw new Error(`Database safety scenario failed: ${scenario} (exit ${error.code})`)
        assert.match(stdout, new RegExp(`DATABASE_SAFETY_PASS: ${scenario}`))
        resolve()
      } catch (failure) { reject(failure) }
      finally {
        if (!sharedProfile) removeProfile(profile)
      }
    })
  })
}

async function runWal(future = false) {
  const profile = fs.mkdtempSync(path.join(root, '.ui-review-safety-'))
  const env = { ...process.env, AURORA_DB_SAFETY_PROFILE: profile }
  delete env.ELECTRON_RUN_AS_NODE
  try {
    await new Promise((resolve, reject) => {
      const writer = spawn(require('electron'), [__filename, '--child', future ? 'wal-future-write' : 'wal-write'], { cwd: root, env, windowsHide: true })
      let output = '', errors = '', ready = false, killRequested = false, failure
      const timer = setTimeout(() => {
        failure = new Error('WAL writer did not reach readiness before its deadline')
        if (writer.exitCode === null) writer.kill('SIGKILL')
      }, 30000)
      writer.stderr.on('data', chunk => { errors = (errors + chunk).slice(-16384) })
      writer.stdout.on('data', chunk => {
        output = (output + chunk).slice(-16384)
        const match = output.match(/WAL_READY:(\d+):(\d+)\r?\n/)
        if (!match || ready) return
        if (Number(match[1]) !== writer.pid || Number(match[2]) <= 0) {
          failure = new Error('Invalid WAL fixture PID/byte count'); writer.kill('SIGKILL'); return
        }
        ready = true
        killRequested = writer.kill('SIGKILL')
      })
      writer.on('error', error => { clearTimeout(timer); reject(error) })
      writer.on('close', (code, signal) => {
        clearTimeout(timer)
        try {
          if (failure) throw failure
          assert.ok(ready && killRequested, `Writer must be terminated only after verified WAL readiness: ${errors}`)
          assert.ok(signal === 'SIGKILL' || code !== 0, 'Writer must not exit gracefully')
          assert.ok(fs.statSync(path.join(profile, 'musicPlayer.db-wal')).size > 0, 'Outstanding WAL must survive the abrupt termination')
          console.log('WAL_CRASH_CONFIRMED: owned writer terminated, nonempty WAL retained')
          resolve()
        } catch (error) { reject(error) }
      })
    })
    await runChild(future ? 'future-wal' : 'wal-recover', profile)
    if (!future) console.log('DATABASE_SAFETY_PASS: wal')
  } finally { removeProfile(profile) }
}

async function runStartup() {
  const expected = {
    'zh-CN': [/无法启动/, /更新/, /权限/], 'zh-TW': [/無法啟動/, /更新/, /權限/],
    en: [/Unable to start/, /Update/, /permissions/], fr: [/Démarrage impossible/, /Mettez/, /autorisations/],
  }
  for (const [locale, [title, future, migration]] of Object.entries(expected)) {
    for (const mode of ['future', 'migration']) {
      const profile = fs.mkdtempSync(path.join(root, '.ui-review-safety-'))
      const env = { ...process.env, AURORA_DB_SAFETY_PROFILE: profile }
      delete env.ELECTRON_RUN_AS_NODE
      try {
        await new Promise((resolve, reject) => {
          execFile(require('electron'), [__filename, '--child', `startup:${mode}:${locale}`],
            { cwd: root, env, windowsHide: true, timeout: 15000 }, error => {
              try {
                assert.equal(error?.code, 1, `${mode}/${locale}: real app must exit with controlled failure`)
                const result = JSON.parse(fs.readFileSync(path.join(profile, 'startup-result.json'), 'utf8'))
                assert.match(result.title, title)
                assert.match(result.content, mode === 'future' ? future : migration)
                assert.equal(result.windows, 0, 'Failed startup must not open a normal player')
                assert.ok(!result.content.includes(profile), 'Do not expose personal profile paths')
                resolve()
              } catch (failure) { reject(failure) }
            })
        })
      } finally { removeProfile(profile) }
    }
  }
  console.log('DATABASE_SAFETY_PASS: startup (real entry, 2 failures x 4 locales, dialog request, controlled exit, no player)')
}

if (process.versions.electron) {
  const scenario = process.argv.at(-1)
  if (scenario.startsWith('startup:')) checkStartup(scenario)
  else if (!['future', 'migration', 'wal-write', 'wal-recover', 'wal-future-write', 'future-wal'].includes(scenario)) throw new Error('Unknown database scenario')
  else checkDatabase(scenario).then(() => require('electron').app.exit(0), error => {
    console.error(error); require('electron').app.exit(1)
  })
} else {
  const argument = process.argv.indexOf('--scenario')
  const scenarios = argument < 0 ? allowed : [process.argv[argument + 1]]
  assert.ok(scenarios.every(scenario => allowed.includes(scenario)), 'Unknown database scenario')
  ;(async () => {
    for (const scenario of scenarios) {
      if (scenario === 'wal' || scenario === 'future-wal') await runWal(scenario === 'future-wal')
      else if (scenario === 'startup') await runStartup()
      else await runChild(scenario)
    }
  })()
    .catch(error => { console.error(error); process.exitCode = 1 })
}
