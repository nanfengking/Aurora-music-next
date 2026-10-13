import type Database from 'better-sqlite3'
import type { AiProvider } from '../types/providers'
import { encryptSecret, decryptSecret } from './secrets'
import { buildAiRequest, extractAiJson } from '../services/aiProtocol'

type Stored = Omit<AiProvider, 'configured'> & { secret: string }
const KEY = 'ai_providers'
export function validateApiUrl(value: string): URL {
  const url = new URL(value)
  if (url.username || url.password || url.search || url.hash) throw new Error('API 地址不能含密码、查询参数或片段')
  if (url.protocol !== 'https:' && !(url.protocol === 'http:' && ['localhost', '127.0.0.1', '[::1]'].includes(url.hostname))) throw new Error('API 必须使用 HTTPS（本机测试允许 HTTP）')
  return url
}
function stored(db: Database.Database): Stored[] {
  const row = db.prepare('SELECT value FROM settings WHERE key = ?').get(KEY) as {value:string}|undefined
  if (row) return JSON.parse(row.value)
  const legacy = Object.fromEntries((db.prepare("SELECT key,value FROM settings WHERE key LIKE 'deepseek_%'").all() as {key:string;value:string}[]).map(row => [row.key, row.value]))
  return legacy.deepseek_api_key ? [{ id:'legacy-deepseek', name:'DeepSeek', protocol:'openai', baseUrl:legacy.deepseek_base_url || 'https://api.deepseek.com', model:legacy.deepseek_model || 'deepseek-chat', priority:1, enabled:true, secret:legacy.deepseek_api_key }] : []
}
export function getProviders(db: Database.Database): AiProvider[] { return stored(db).map(({secret, ...item}) => ({ ...item, configured: Boolean(decryptSecret(secret)) })).sort((a,b) => a.priority-b.priority) }
export function saveProviders(db: Database.Database, input: AiProvider[]): AiProvider[] {
  if (!Array.isArray(input) || input.length > 10) throw new Error('最多配置 10 个 API')
  const previous = new Map(stored(db).map(item => [item.id, item]))
  const ids = new Set<string>()
  const result: Stored[] = input.map((item, index) => {
    if (!/^[\w-]{1,80}$/.test(item.id) || ids.has(item.id)) throw new Error('API 标识无效或重复')
    ids.add(item.id)
    if (!['openai','responses','anthropic','gemini'].includes(item.protocol)) throw new Error('不支持的 API 协议')
    const baseUrl = validateApiUrl(item.baseUrl.trim()).href.replace(/\/$/, '')
    const old = previous.get(item.id)
    const key = String(item.apiKey || '').trim()
    if (old?.secret && !key && old.baseUrl !== baseUrl) throw new Error('修改 API 地址时请重新填写密钥，避免旧密钥被发送到其他服务')
    const model = String(item.model || '').trim().slice(0, 120)
    if (!model || !/^[\w.\/:@-]+$/.test(model)) throw new Error('请填写该服务实际提供的模型 ID')
    return { id:item.id, name:String(item.name || 'API').slice(0,60), protocol:item.protocol, baseUrl, model, priority:index+1, enabled:Boolean(item.enabled), secret:key ? encryptSecret(key) : old?.secret || '' }
  })
  db.prepare('INSERT OR REPLACE INTO settings VALUES (?, ?)').run(KEY, JSON.stringify(result))
  return getProviders(db)
}
function request(provider: Stored, system: string, user: string): { url: string; headers: Record<string,string>; body: unknown } {
  return buildAiRequest(provider, decryptSecret(provider.secret), system, user)
}
export async function requestJsonWithProviders<T>(
  db: Database.Database, system: string, user: string, validate: (value: unknown) => T, deadline = Date.now() + 90_000,
): Promise<{ data: T; providerName: string; attempts: string[] }> {
  const providers = stored(db).filter(p => p.enabled && decryptSecret(p.secret)).sort((a,b) => a.priority-b.priority)
  if (!providers.length) throw new Error('请先配置并启用至少一个 AI API')
  const errors: string[] = []
  for (const provider of providers) {
    if (Date.now() >= deadline) break
    try {
      const req = request(provider, system, user)
      const response = await fetch(req.url, { method:'POST', redirect:'error', headers:{...req.headers, 'Content-Type':'application/json'}, body:JSON.stringify(req.body), signal:AbortSignal.timeout(Math.max(1, Math.min(30_000, deadline-Date.now()))) })
      if (!response.ok) { await response.body?.cancel(); throw new Error(`HTTP ${response.status}`) }
      // Bound the response while reading, not after allocating an unbounded body.
      const reader = response.body?.getReader()
      if (!reader) throw new Error('空响应')
      const chunks: Uint8Array[] = []
      let size = 0
      try {
        for (;;) {
          const { value, done } = await reader.read()
          if (done) break
          size += value.byteLength
          if (size > 2_000_000) { await reader.cancel(); throw new Error('响应过大') }
          chunks.push(value)
        }
      } finally { reader.releaseLock() }
      const payload = JSON.parse(Buffer.concat(chunks).toString('utf8'))
      const parsed = extractAiJson(provider.protocol, payload)
      return { data: validate(parsed), providerName: provider.name, attempts: errors }
    } catch (error) {
      const reason = error instanceof Error && /^HTTP \d+$/.test(error.message) ? error.message : '超时、网络失败或返回格式无效'
      errors.push(`${provider.name}：${reason}`)
    }
  }
  throw new Error(`按优先级尝试后仍未得到有效响应。${errors.join('；')}`)
}
