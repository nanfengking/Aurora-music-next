import { create } from 'zustand'
import { validateDesktopPreferencePatch, validateDesktopPreferenceSnapshot,
  type DesktopPreferences, type DesktopPreferenceSnapshot } from '../types/preferences'

export const usePreferencePersistence = create<{ error: boolean }>(() => ({ error: false }))
let publish: ((snapshot: DesktopPreferenceSnapshot) => void) | null = null
let unsubscribe: (() => void) | undefined
let ready = false
let tail: Promise<unknown> = Promise.resolve()
let failedPatch: Partial<DesktopPreferences> = {}

export async function connectDesktopPreferences(
  legacy: Partial<DesktopPreferences>, apply: (snapshot: DesktopPreferenceSnapshot) => void,
): Promise<void> {
  ready = false
  unsubscribe?.()
  publish = apply
  unsubscribe = window.electronAPI.onPreferencesChanged(snapshot => {
    try { apply(validateDesktopPreferenceSnapshot(snapshot)) }
    catch { usePreferencePersistence.setState({ error: true }) }
  })
  try {
    const response = await window.electronAPI.initializePreferences(legacy)
    if (!response.success) throw new Error('Unable to initialize preferences')
    apply(validateDesktopPreferenceSnapshot(response.data))
    ready = true
  } catch (error) {
    unsubscribe?.()
    unsubscribe = undefined
    publish = null
    throw error
  }
}

/** UI state is published only after the native transaction acknowledges its write. */
function enqueuePreferences(input: () => Partial<DesktopPreferences>): Promise<boolean> {
  const operation = tail.then(async () => {
    let patch: Partial<DesktopPreferences> = {}
    try {
      patch = validateDesktopPreferencePatch(input())
      if (!Object.keys(patch).length) {
        usePreferencePersistence.setState({ error: Object.keys(failedPatch).length > 0 })
        return true
      }
      if (!ready || !publish) throw new Error('Preferences are not ready')
      const response = await window.electronAPI.updatePreferences(patch)
      if (!response.success) throw new Error('Unable to save preferences')
      const snapshot = validateDesktopPreferenceSnapshot(response.data)
      publish(snapshot)
      // A newer explicit success supersedes any failed intent for those fields,
      // even when the new value differs from the failed value.
      for (const key of Object.keys(patch) as Array<keyof DesktopPreferences>) delete failedPatch[key]
      for (const key of Object.keys(failedPatch) as Array<keyof DesktopPreferences>) {
        if (snapshot.values[key] === failedPatch[key]) delete failedPatch[key]
      }
      usePreferencePersistence.setState({ error: Object.keys(failedPatch).length > 0 })
      return true
    } catch {
      failedPatch = { ...failedPatch, ...patch }
      usePreferencePersistence.setState({ error: true })
      return false
    }
  })
  tail = operation
  return operation
}

export function persistDesktopPreferences(input: Partial<DesktopPreferences>): Promise<boolean> {
  return enqueuePreferences(() => input)
}

export function retryDesktopPreferences(): void {
  // Resolve the remaining failed fields at execution time, after all earlier
  // user choices have acknowledged. Never capture obsolete intent in the queue.
  void enqueuePreferences(() => ({ ...failedPatch }))
}
