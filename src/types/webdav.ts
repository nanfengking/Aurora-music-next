export interface WebDavConfig {
  url: string
  username: string
  password: string
}

export interface WebDavFile {
  href: string
  name: string
  size: number
  lastModified: string
  isDirectory: boolean
  isMusic: boolean
}

export interface SyncProgress {
  total: number
  completed: number
  currentFile: string
  bytesDownloaded: number
  totalBytes: number
  isRunning: boolean
  errors: string[]
}
