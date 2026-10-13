import type { AiProtocol } from '../types/providers'

/** Transport-only helpers. No credentials are persisted here. */
export function buildAiRequest(provider: { protocol: AiProtocol; baseUrl: string; model: string }, apiKey: string, system: string, user: string) {
  const base = provider.baseUrl.replace(/\/$/, '')
  if (provider.protocol === 'anthropic') return {
    url: base.endsWith('/messages') ? base : `${base.endsWith('/v1') ? base : base + '/v1'}/messages`,
    headers: { 'x-api-key': apiKey, 'anthropic-version': '2023-06-01' } as Record<string, string>,
    body: { model: provider.model, max_tokens: 4096, system, messages: [{ role: 'user', content: user }] },
  }
  if (provider.protocol === 'gemini') return {
    url: `${/\/v1(beta)?$/.test(base) ? base : base + '/v1beta'}/models/${encodeURIComponent(provider.model.replace(/^models\//, ''))}:generateContent`,
    headers: { 'x-goog-api-key': apiKey } as Record<string, string>,
    body: { systemInstruction: { parts: [{ text: system }] }, contents: [{ role: 'user', parts: [{ text: user }] }], generationConfig: { responseMimeType: 'application/json' } },
  }
  if (provider.protocol === 'responses') return {
    url: base.endsWith('/responses') ? base : `${base}/responses`,
    headers: { Authorization: `Bearer ${apiKey}` } as Record<string, string>,
    // Stateless requests; this flag is not a guarantee about a relay's retention policy.
    body: { model: provider.model, instructions: system, input: user, store: false, stream: false, max_output_tokens: 4096 },
  }
  return {
    url: base.endsWith('/chat/completions') ? base : `${base}/chat/completions`,
    headers: { Authorization: `Bearer ${apiKey}` } as Record<string, string>,
    body: { model: provider.model, messages: [{ role: 'system', content: system }, { role: 'user', content: user }] },
  }
}

export function extractAiJson(protocol: AiProtocol, value: unknown): unknown {
  if (!value || typeof value !== 'object') throw new Error('响应格式不兼容')
  const payload = value as Record<string, any>
  let content: unknown
  if (protocol === 'responses') {
    if (payload.error || (payload.status && payload.status !== 'completed')) throw new Error('响应未完成')
    content = Array.isArray(payload.output) ? payload.output.filter(item => item.type === 'message' && item.role === 'assistant')
      .flatMap(item => Array.isArray(item.content) ? item.content : []).filter(item => item.type === 'output_text')
      .map(item => typeof item.text === 'string' ? item.text : '').join('') : payload.output_text
  } else if (protocol === 'anthropic') content = payload.content?.filter((item: { type: string }) => item.type === 'text').map((item: { text: string }) => item.text).join('')
  else if (protocol === 'gemini') content = payload.candidates?.[0]?.content?.parts?.filter((item: { thought?: boolean }) => !item.thought).map((item: { text?: string }) => item.text || '').join('')
  else content = payload.choices?.[0]?.message?.content
  if (typeof content !== 'string') throw new Error('响应格式不兼容')
  const start = content.indexOf('{'), end = content.lastIndexOf('}')
  if (start < 0 || end < start) throw new Error('没有 JSON 对象')
  return JSON.parse(content.slice(start, end + 1))
}
