import type Database from 'better-sqlite3'
import { DEFAULT_DESKTOP_PREFERENCES, pickDesktopPreferences, validateDesktopPreferencePatch,
  validateDesktopPreferenceSnapshot, type DesktopPreferenceSnapshot } from '../types/preferences'

export const DESKTOP_PREFERENCES_KEY = 'desktop_preferences_v1'
const MAX_RECORD_LENGTH = 4096

/** No renderer/browser storage is authoritative once this record exists. */
export class DesktopPreferenceRepository {
  constructor(private readonly database: Database.Database) {}

  private read(): DesktopPreferenceSnapshot | null {
    const row = this.database.prepare('SELECT value FROM settings WHERE key = ?')
      .get(DESKTOP_PREFERENCES_KEY) as { value: unknown } | undefined
    if (!row) return null
    if (typeof row.value !== 'string' || row.value.length > MAX_RECORD_LENGTH) throw new Error('Invalid preference record')
    try { return validateDesktopPreferenceSnapshot(JSON.parse(row.value)) }
    catch { throw new Error('Unsupported or invalid preference record; update Aurora or restore a valid backup') }
  }

  private write(snapshot: DesktopPreferenceSnapshot): DesktopPreferenceSnapshot {
    this.database.prepare('INSERT OR REPLACE INTO settings (key,value) VALUES (?,?)')
      .run(DESKTOP_PREFERENCES_KEY, JSON.stringify(snapshot))
    return snapshot
  }

  initialize(legacy: unknown): DesktopPreferenceSnapshot {
    return this.database.transaction(() => this.read() ?? this.write(validateDesktopPreferenceSnapshot({
      version: 1, revision: 1, restoreRevision: 0,
      values: { ...DEFAULT_DESKTOP_PREFERENCES, ...pickDesktopPreferences(legacy) },
    })))()
  }

  update(input: unknown): DesktopPreferenceSnapshot {
    const patch = validateDesktopPreferencePatch(input)
    return this.database.transaction(() => {
      const previous = this.read()
      if (!previous) throw new Error('Preferences are not initialized')
      const values = { ...previous.values, ...patch }
      if (Object.keys(patch).every(key => values[key as keyof typeof values] === previous.values[key as keyof typeof values])) return previous
      const snapshot = validateDesktopPreferenceSnapshot({ version: 1,
        revision: previous.revision + 1,
        restoreRevision: previous.restoreRevision + Number(values.rememberPlayback !== previous.values.rememberPlayback), values })
      return this.write(snapshot)
    })()
  }
}
