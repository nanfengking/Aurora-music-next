import Database from 'better-sqlite3'
import { app } from 'electron'
import { existsSync } from 'fs'
import { join } from 'path'

let db: Database.Database | null = null
const CURRENT_SCHEMA_VERSION = 3

export class DatabaseVersionError extends Error {}

function requireSupportedVersion(database: Database.Database): void {
  const version = database.pragma('user_version', { simple: true }) as number
  if (version > CURRENT_SCHEMA_VERSION) {
    throw new DatabaseVersionError('Database was created by a newer version of Aurora. Please update Aurora; your data has not been migrated.')
  }
}

function ensureSongColumn(database: Database.Database, name: string, definition: string): void {
  const columns = database.pragma('table_info(songs)') as Array<{ name: string }>
  if (!columns.some((column) => column.name === name)) {
    database.exec(`ALTER TABLE songs ADD COLUMN ${name} ${definition}`)
  }
}

function reconcileMissingCacheFiles(database: Database.Database): void {
  const rows = database.prepare(
    'SELECT id, localCachePath FROM songs WHERE localCachePath IS NOT NULL'
  ).all() as Array<{ id: string; localCachePath: string }>
  const clear = database.prepare(`UPDATE songs SET localCachePath = NULL, filePath = remotePath,
    cacheSize = 0, lastCachedAt = NULL, lastAccessedAt = NULL WHERE id = ?`)
  const reconcile = database.transaction(() => {
    for (const row of rows) {
      if (!existsSync(row.localCachePath)) clear.run(row.id)
    }
  })
  reconcile()
}

function migrateDatabase(database: Database.Database): void {
  database.transaction((db: Database.Database) => {
    // 批量建表
    db.exec(`
      CREATE TABLE IF NOT EXISTS songs (
        id          TEXT PRIMARY KEY,
        title       TEXT NOT NULL,
        artist      TEXT DEFAULT '未知艺术家',
        album       TEXT DEFAULT '',
        year        INTEGER,
        genre       TEXT DEFAULT '',
        duration    REAL NOT NULL,
        filePath    TEXT UNIQUE NOT NULL,
        format      TEXT,
        coverPath   TEXT,
        customArtistImage TEXT,
        customLyrics      TEXT,
        sourceType  TEXT DEFAULT 'local',
        remotePath  TEXT,
        remoteSize  INTEGER DEFAULT 0,
        remoteModified TEXT,
        localCachePath TEXT,
        cacheSize   INTEGER DEFAULT 0,
        lastCachedAt DATETIME,
        lastAccessedAt DATETIME,
        isFavorite INTEGER DEFAULT 0,
        playCount INTEGER DEFAULT 0,
        skipCount INTEGER DEFAULT 0,
        lastPlayedAt INTEGER,
        createdAt   TIMESTAMP DEFAULT CURRENT_TIMESTAMP
      );

      CREATE TABLE IF NOT EXISTS playlists (
        id          TEXT PRIMARY KEY,
        name        TEXT NOT NULL,
        description TEXT,
        songIds     TEXT NOT NULL,
        createdAt   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        updatedAt   TIMESTAMP DEFAULT CURRENT_TIMESTAMP
      );

      CREATE TABLE IF NOT EXISTS play_history (
        id        INTEGER PRIMARY KEY AUTOINCREMENT,
        songId    TEXT NOT NULL,
        action    TEXT NOT NULL,
        timestamp INTEGER NOT NULL,
        progress  REAL DEFAULT 0
      );

      CREATE TABLE IF NOT EXISTS settings (
        key   TEXT PRIMARY KEY,
        value TEXT
      );

      CREATE TABLE IF NOT EXISTS cache_info (
        key   TEXT PRIMARY KEY,
        value TEXT
      );
    `)

    // 兼容早期数据库。显式检查列，避免把真正的迁移错误误判为“列已存在”。
    ensureSongColumn(db, 'remotePath', 'TEXT')
    ensureSongColumn(db, 'remoteSize', 'INTEGER DEFAULT 0')
    ensureSongColumn(db, 'remoteModified', 'TEXT')
    ensureSongColumn(db, 'localCachePath', 'TEXT')
    ensureSongColumn(db, 'cacheSize', 'INTEGER DEFAULT 0')
    ensureSongColumn(db, 'lastCachedAt', 'DATETIME')
    ensureSongColumn(db, 'lastAccessedAt', 'DATETIME')
    ensureSongColumn(db, 'isFavorite', 'INTEGER DEFAULT 0')
    ensureSongColumn(db, 'playCount', 'INTEGER DEFAULT 0')
    ensureSongColumn(db, 'skipCount', 'INTEGER DEFAULT 0')
    ensureSongColumn(db, 'lastPlayedAt', 'INTEGER')
    ensureSongColumn(db, 'codec', 'TEXT')
    for (const name of ['bitrate', 'sampleRate', 'bitDepth', 'channels', 'replayGainDb', 'replayPeak']) {
      ensureSongColumn(db, name, 'REAL')
    }
    const playlistColumns = db.pragma('table_info(playlists)') as Array<{ name: string }>
    if (!playlistColumns.some(column => column.name === 'smartRules')) db.exec('ALTER TABLE playlists ADD COLUMN smartRules TEXT')

    db.exec(`
      CREATE INDEX IF NOT EXISTS idx_songs_source_title ON songs(sourceType, title COLLATE NOCASE);
      CREATE INDEX IF NOT EXISTS idx_songs_remote_path ON songs(remotePath);
      CREATE INDEX IF NOT EXISTS idx_songs_favorite ON songs(isFavorite, lastPlayedAt DESC);
      CREATE INDEX IF NOT EXISTS idx_play_history_timestamp ON play_history(timestamp DESC);
      CREATE INDEX IF NOT EXISTS idx_play_history_song_timestamp ON play_history(songId, timestamp DESC);
    `)
    db.pragma(`user_version = ${CURRENT_SCHEMA_VERSION}`)

    // 初始化 cache_info 默认值（如果不存在）
    const existing = db.prepare('SELECT value FROM cache_info WHERE key = ?').get('max_size')
    if (!existing) {
      db.prepare('INSERT OR REPLACE INTO cache_info (key, value) VALUES (?, ?)').run('max_size', String(2 * 1024 * 1024 * 1024))
      db.prepare('INSERT OR REPLACE INTO cache_info (key, value) VALUES (?, ?)').run('total_size', '0')
      db.prepare('INSERT OR REPLACE INTO cache_info (key, value) VALUES (?, ?)').run('cleanup_strategy', 'lru')
    }

    reconcileMissingCacheFiles(db)
  })(database)
}

/** 初始化失败不发布连接；不兼容版本不得迁移或写入。 */
export function initDatabase(): Database.Database {
  const dbPath = join(app.getPath('userData'), 'musicPlayer.db')
  // Even closing a writable WAL connection can checkpoint and delete the WAL.
  // Reject existing future databases before opening any writable connection.
  if (existsSync(dbPath)) {
    const probe = new Database(dbPath, { readonly: true, fileMustExist: true })
    try { requireSupportedVersion(probe) } finally { probe.close() }
  }
  const connection = new Database(dbPath)
  try {
    requireSupportedVersion(connection)
    connection.pragma('journal_mode = WAL')
    connection.pragma('foreign_keys = ON')
    connection.pragma('synchronous = NORMAL')
    connection.pragma('busy_timeout = 5000')
    migrateDatabase(connection)
    connection.pragma('optimize')
    db = connection
    return connection
  } catch (error) {
    connection.close()
    throw error
  }
}

/** 获取数据库实例（需要先调用 initDatabase） */
export function getDb(): Database.Database {
  if (!db) {
    throw new Error('Database not initialized. Call initDatabase() first.')
  }
  return db
}

/** 在应用退出前提交 WAL 并释放文件句柄。 */
export function closeDatabase(): void {
  if (!db) return
  try {
    db.pragma('optimize')
    db.pragma('wal_checkpoint(TRUNCATE)')
  } finally {
    db.close()
    db = null
  }
}
