// Real Electron/SQLite, isolated legacy schema fixture. Never opens AppData.
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const root = path.resolve(__dirname, '..')

async function checkDatabase() {
  const { app } = require('electron')
  const profile = path.resolve(process.env.AURORA_DB_REVIEW_PROFILE || '')
  if (!profile.startsWith(root + path.sep + '.ui-review-database-')) {
    throw new Error('Database review requires its own project-local profile')
  }
  app.setPath('userData', profile)
  app.setPath('sessionData', profile)
  await app.whenReady()
  const Database = require('better-sqlite3')
  const file = path.join(profile, 'musicPlayer.db')
  const keptFile = path.join(profile, 'kept.cache')
  const missingFile = path.join(profile, 'missing.cache')
  fs.writeFileSync(keptFile, 'synthetic cache fixture, not audio')
  const seed = new Database(file)
  try {
    seed.exec(`
      CREATE TABLE songs (
        id TEXT PRIMARY KEY, title TEXT NOT NULL, artist TEXT, album TEXT,
        year INTEGER, genre TEXT, duration REAL, filePath TEXT UNIQUE NOT NULL,
        format TEXT, coverPath TEXT, customArtistImage TEXT, customLyrics TEXT,
        sourceType TEXT, remotePath TEXT, localCachePath TEXT, isFavorite INTEGER,
        playCount INTEGER, skipCount INTEGER, lastPlayedAt INTEGER, createdAt TEXT
      );
      CREATE TABLE playlists (
        id TEXT PRIMARY KEY, name TEXT NOT NULL, description TEXT, songIds TEXT,
        createdAt TEXT, updatedAt TEXT
      );
      CREATE TABLE play_history (
        id INTEGER PRIMARY KEY AUTOINCREMENT, songId TEXT NOT NULL,
        action TEXT NOT NULL, timestamp INTEGER NOT NULL, progress REAL
      );
      CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT);
      CREATE TABLE cache_info (key TEXT PRIMARY KEY, value TEXT);
    `)
    const song = seed.prepare(`INSERT INTO songs
      (id,title,artist,album,duration,filePath,format,customLyrics,sourceType,
       remotePath,localCachePath,isFavorite,playCount,skipCount,lastPlayedAt)
      VALUES (?,?,'Fixture Artist','Fixture Album',60,?,'wav','original fixture lyric',
       'webdav',?,?,1,7,2,1234)`)
    song.run('kept', 'Legacy Kept', keptFile, 'https://fixture.invalid/kept.wav', keptFile)
    song.run('missing', 'Legacy Missing', missingFile, 'https://fixture.invalid/missing.wav', missingFile)
    seed.prepare('INSERT INTO playlists VALUES (?,?,?,?,?,?)')
      .run('list', 'Original order', 'fixture only', '["missing","kept"]', 'old-created', 'old-updated')
    seed.prepare('INSERT INTO play_history (songId,action,timestamp,progress) VALUES (?,?,?,?)')
      .run('kept', 'complete', 1000, 1)
    seed.prepare('INSERT INTO settings VALUES (?,?)').run('theme', 'dark')
    seed.prepare('INSERT INTO settings VALUES (?,?)').run('original_preference', 'keep-me')
    seed.prepare('INSERT INTO cache_info VALUES (?,?)').run('max_size', '1048576')
    seed.pragma('user_version = 1')
  } finally { seed.close() }

  const Module = require('node:module')
  const ts = require('typescript')
  const source = path.join(root, 'src/main/database.ts')
  const compiled = ts.transpileModule(fs.readFileSync(source, 'utf8'), {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS, esModuleInterop: true },
  })
  const instance = new Module(source, module)
  instance.filename = source
  instance.paths = Module._nodeModulePaths(path.dirname(source))
  instance._compile(compiled.outputText, source)
  const { initDatabase, closeDatabase } = instance.exports
  // Characterize the actual production migration, then reopen to test idempotence.
  for (let round = 0; round < 2; round++) {
    const db = initDatabase()
    try {
      assert.equal(db.pragma('user_version', { simple: true }), 3)
      assert.deepEqual(db.prepare('SELECT id,title,isFavorite,playCount,skipCount,customLyrics FROM songs ORDER BY id').all(), [
        { id: 'kept', title: 'Legacy Kept', isFavorite: 1, playCount: 7, skipCount: 2, customLyrics: 'original fixture lyric' },
        { id: 'missing', title: 'Legacy Missing', isFavorite: 1, playCount: 7, skipCount: 2, customLyrics: 'original fixture lyric' },
      ])
      const playlist = db.prepare('SELECT * FROM playlists WHERE id = ?').get('list')
      assert.deepEqual(JSON.parse(playlist.songIds), ['missing', 'kept'])
      assert.equal(playlist.name, 'Original order')
      assert.equal(playlist.createdAt, 'old-created')
      assert.equal(playlist.updatedAt, 'old-updated')
      assert.equal(playlist.smartRules, null)
      assert.deepEqual(db.prepare('SELECT songId,action,timestamp,progress FROM play_history').all(), [
        { songId: 'kept', action: 'complete', timestamp: 1000, progress: 1 },
      ])
      assert.deepEqual(db.prepare('SELECT key,value FROM settings ORDER BY key').all(), [
        { key: 'original_preference', value: 'keep-me' }, { key: 'theme', value: 'dark' },
      ])
      assert.equal(db.prepare('SELECT value FROM cache_info WHERE key = ?').get('max_size').value, '1048576')
      const kept = db.prepare('SELECT filePath,localCachePath,codec,replayGainDb FROM songs WHERE id = ?').get('kept')
      assert.equal(kept.filePath, keptFile)
      assert.equal(kept.localCachePath, keptFile)
      assert.equal(kept.codec, null)
      assert.equal(kept.replayGainDb, null)
      const missing = db.prepare('SELECT filePath,localCachePath,cacheSize FROM songs WHERE id = ?').get('missing')
      assert.deepEqual(missing, { filePath: 'https://fixture.invalid/missing.wav', localCachePath: null, cacheSize: 0 })
    } finally { closeDatabase() }
  }
  assert.equal(fs.readFileSync(keptFile, 'utf8'), 'synthetic cache fixture, not audio')
  console.log('DATABASE_UPGRADE_PASS: real Electron/SQLite schema v1→3, two opens, song/favourite/count/lyric/playlist/history/settings retention and narrow stale-cache repair')
}

if (process.versions.electron) {
  checkDatabase().then(() => require('electron').app.exit(0), error => {
    console.error(error)
    require('electron').app.exit(1)
  })
} else {
  const profile = fs.mkdtempSync(path.join(root, '.ui-review-database-'))
  const env = { ...process.env, AURORA_DB_REVIEW_PROFILE: profile }
  delete env.ELECTRON_RUN_AS_NODE
  const { execFile } = require('node:child_process')
  execFile(require('electron'), [__filename], { cwd: root, env, windowsHide: true, timeout: 30000 }, (error, stdout, stderr) => {
    try {
      if (stdout) process.stdout.write(stdout)
      if (stderr) process.stderr.write(stderr)
      if (error) throw error
      assert.match(stdout, /DATABASE_UPGRADE_PASS:/)
    } catch (failure) { console.error(failure); process.exitCode = 1 }
    finally {
      if (profile.startsWith(root + path.sep + '.ui-review-database-')) {
        fs.rmSync(profile, { recursive: true, force: true, maxRetries: 5, retryDelay: 250 })
      }
    }
  })
}
