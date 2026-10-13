export type UpdatePhase = 'idle' | 'checking' | 'available' | 'downloading' | 'ready' | 'up-to-date' | 'error' | 'unsupported'

export interface UpdateStatus {
  phase: UpdatePhase
  currentVersion: string
  nextVersion?: string
  progress?: number
  message?: string
}
