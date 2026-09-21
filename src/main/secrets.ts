import { safeStorage } from 'electron'

export function encryptSecret(value: string): string {
  if (!value) return ''
  if (!safeStorage.isEncryptionAvailable()) throw new Error('Windows 凭据加密不可用，未保存密钥')
  return `encrypted:${safeStorage.encryptString(value).toString('base64')}`
}
export function decryptSecret(value: string): string {
  if (!value.startsWith('encrypted:')) return value
  if (!safeStorage.isEncryptionAvailable()) return ''
  try { return safeStorage.decryptString(Buffer.from(value.slice(10), 'base64')) } catch { return '' }
}
