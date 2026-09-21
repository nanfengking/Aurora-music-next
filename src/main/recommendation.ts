import type { ListeningProfile, PlayHistory, RecommendationFeed, SceneIntent, Song, TasteFacet } from '../types/music'

const DAY = 86_400_000
const UNKNOWN = /^(未知.*|unknown.*|various artists|其他|其它|none|null)$/i
const norm = (value: string) => value.normalize('NFKC').trim().toLowerCase()
const meaningful = (value: string) => Boolean(value.trim()) && !UNKNOWN.test(value.trim())
const clamp = (value: number, min: number, max: number) => Math.min(max, Math.max(min, value))
const GENRES: Record<string, string[]> = {
  '流行': ['pop', 'mandopop', 'cantopop', 'c-pop', 'j-pop', 'k-pop', '流行'],
  '摇滚': ['rock', '摇滚'], '民谣': ['folk', '民谣'], '爵士': ['jazz', '爵士'],
  '电子': ['electronic', 'electronica', 'edm', '电子'], '舞曲': ['dance', '舞曲'],
  '嘻哈': ['hip-hop', 'hip hop', 'rap', '说唱', '嘻哈'], '古典': ['classical', '古典'],
  '轻音乐': ['easy listening', '轻音乐'], '氛围': ['ambient', '氛围'],
  'R&B': ['r&b', 'rnb', 'rhythm and blues'], '金属': ['metal', '金属'],
  '原声': ['soundtrack', 'ost', '原声'], '乡村': ['country', '乡村'],
}
function contains(text: string, term: string): boolean {
  if (!term) return false
  if (/^[a-z0-9 &-]+$/.test(term)) {
    const escaped = term.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
    return new RegExp(`(^|[^a-z0-9])${escaped}($|[^a-z0-9])`, 'i').test(text)
  }
  return text.includes(term)
}
export function genresOf(song: Song): string[] {
  if (!meaningful(song.genre || '')) return []
  const raw = norm(song.genre)
  const canonical = Object.entries(GENRES).filter(([, aliases]) => aliases.some(alias => contains(raw, alias))).map(([name]) => name)
  return canonical.length ? canonical : song.genre.split(/[;,，；/|]/).map(x => x.trim()).filter(meaningful).slice(0, 5)
}
function canonicalGenres(values: string[]): string[] {
  return [...new Set(values.flatMap(genre => genresOf({ genre } as Song)))]
}
function hash(value: string): number {
  let n = 2166136261
  for (const c of value) n = Math.imul(n ^ c.charCodeAt(0), 16777619)
  return n >>> 0
}

export interface BehaviorModel {
  profile: ListeningProfile
  trackScores: Map<string, number>
  artistScores: Map<string, number>
  genreScores: Map<string, number>
}

/** Rebuildable, explainable implicit-feedback model. No network, audio analysis or persisted inferred tags. */
export function buildBehaviorModel(songs: Song[], history: PlayHistory[], now = Date.now()): BehaviorModel {
  const byId = new Map(songs.map(song => [song.id, song]))
  type Session = { songId: string; timestamp: number; progress: number; complete: boolean; skip: boolean }
  const sessions: Session[] = []
  const active = new Map<string, Session>()
  for (const event of [...history].sort((a, b) => a.timestamp - b.timestamp || a.id - b.id)) {
    if (!byId.has(event.songId) || event.timestamp < now - 180 * DAY || event.timestamp > now) continue
    let session = active.get(event.songId)
    if (event.action === 'start' || !session || event.timestamp - session.timestamp > 12 * 3_600_000) {
      session = { songId: event.songId, timestamp: event.timestamp, progress: 0, complete: false, skip: false }
      sessions.push(session); active.set(event.songId, session)
    }
    session.progress = Math.max(session.progress, clamp(Number(event.progress) || 0, 0, 1))
    session.complete ||= event.action === 'complete'
    session.skip ||= event.action === 'skip'
  }
  const daily = new Map<string, { id: string; weight: number; timestamp: number }>()
  for (const session of sessions) {
    // Repeated pause/resume within a play is one signal; a bare start is not evidence of liking.
    const weight = session.complete ? 3 : session.skip ? (session.progress < 0.25 ? -2 : session.progress < 0.7 ? -0.6 : 1) : session.progress >= 0.5 ? 0.8 : 0
    const key = `${session.songId}:${Math.floor(session.timestamp / DAY)}`
    const previous = daily.get(key)
    daily.set(key, { id: session.songId, weight: clamp((previous?.weight || 0) + weight, -4, 6), timestamp: session.timestamp })
  }
  const trackScores = new Map<string, number>()
  for (const value of daily.values()) {
    const decayed = value.weight * 2 ** (-(now - value.timestamp) / (45 * DAY))
    trackScores.set(value.id, clamp((trackScores.get(value.id) || 0) + decayed, -12, 18))
  }
  for (const song of songs) if (song.isFavorite) trackScores.set(song.id, (trackScores.get(song.id) || 0) + 5)
  const artistScores = new Map<string, number>(), genreScores = new Map<string, number>()
  const artistEvidence = new Map<string, Set<string>>(), genreEvidence = new Map<string, Set<string>>()
  const artistAvailable = new Map<string, number>(), genreAvailable = new Map<string, number>(), artistNames = new Map<string, string>()
  const add = (scores: Map<string, number>, evidence: Map<string, Set<string>>, key: string, weight: number, id: string) => {
    scores.set(key, (scores.get(key) || 0) + weight)
    if (weight > 0) { const tracks = evidence.get(key) || new Set<string>(); tracks.add(id); evidence.set(key, tracks) }
  }
  for (const song of songs) {
    const weight = trackScores.get(song.id) || 0
    const artist = norm(song.artist || '')
    if (meaningful(artist)) {
      artistNames.set(artist, song.artist); artistAvailable.set(artist, (artistAvailable.get(artist) || 0) + 1)
      add(artistScores, artistEvidence, artist, weight, song.id)
    }
    const genres = genresOf(song)
    for (const genre of genres) {
      genreAvailable.set(genre, (genreAvailable.get(genre) || 0) + 1)
      add(genreScores, genreEvidence, genre, weight / genres.length, song.id)
    }
  }
  const facets = (scores: Map<string, number>, evidence: Map<string, Set<string>>, available: Map<string, number>, names?: Map<string, string>): TasteFacet[] =>
    [...scores].filter(([, score]) => score > 0).sort((a, b) => b[1] - a[1]).slice(0, 5)
      .map(([key, score]) => ({ name: names?.get(key) || key, score: Math.round(score * 10) / 10, evidenceTracks: evidence.get(key)?.size || 0, availableTracks: available.get(key) || 0 }))
  const evidenceSessions = sessions.filter(s => s.complete || s.skip || s.progress >= 0.5)
  const evidenceTracks = new Set(evidenceSessions.map(s => s.songId)).size
  const confidence = evidenceSessions.length >= 20 && evidenceTracks >= 8 ? 'established' : evidenceSessions.length || songs.some(s => s.isFavorite) ? 'learning' : 'new'
  const profile: ListeningProfile = {
    confidence, historySessions: evidenceSessions.length,
    completedSessions: sessions.filter(s => s.complete).length,
    skippedSessions: sessions.filter(s => s.skip && !s.complete).length,
    artists: facets(artistScores, artistEvidence, artistAvailable, artistNames),
    genres: facets(genreScores, genreEvidence, genreAvailable), suggestedGenres: [],
    genreCoverage: songs.filter(song => genresOf(song).length).length, librarySize: songs.length,
    summary: confidence === 'new' ? '还在认识你的耳朵。多听几首、点一点喜欢，推荐会逐渐贴近你。' : confidence === 'learning' ? '这是初步的音乐偏好，还会随着聆听变化。' : '根据近 180 天的聆听与当前收藏生成，近期行为权重更高。',
  }
  const model = { profile, trackScores, artistScores, genreScores }
  const ranked = rankLibrary(songs, model, now)
  const preferred = new Set(profile.genres.slice(0, 3).map(g => g.name))
  const names = [...preferred, ...[...genreAvailable.keys()].filter(g => !preferred.has(g) && (genreScores.get(g) || 0) >= 0).sort((a, b) => (genreScores.get(b) || 0) - (genreScores.get(a) || 0)).slice(0, 2)]
  profile.suggestedGenres = names.slice(0, 5).map(name => ({
    name, reason: preferred.has(name) ? '收藏与近期聆听中出现的类型' : '换个口味 · 曲库中已有的类型，并非已确认的偏好',
    songIds: ranked.filter(item => genresOf(item.song).includes(name)).slice(0, 30).map(item => item.song.id),
  }))
  return model
}

export function rankLibrary(songs: Song[], model: BehaviorModel, now = Date.now()): Array<{ song: Song; score: number; reason: string }> {
  const day = Math.floor(now / DAY)
  return songs.map(song => {
    const artist = model.artistScores.get(norm(song.artist || '')) || 0
    const genre = Math.max(0, ...genresOf(song).map(g => model.genreScores.get(g) || 0))
    const track = model.trackScores.get(song.id) || 0
    const lastPlayed = Number(song.lastPlayedAt || 0)
    const recentPenalty = lastPlayed && now - lastPlayed < DAY ? 4 : 0
    const exploration = (hash(`${day}:${song.id}`) % 1000) / 1000 * 3
    const score = track * 2 + Math.sign(artist) * Math.log1p(Math.abs(artist)) * 2 + Math.log1p(genre) * 1.5 + exploration - recentPenalty
    const reason = song.isFavorite ? '来自你喜欢的音乐' : track > 1 ? '最近有认真听过这首歌' : artist > 1 ? `延续你对 ${song.artist} 的聆听` : genre > 1 ? `与你近期的 ${genresOf(song).find(g => (model.genreScores.get(g) || 0) > 1)} 偏好相近` : '换个口味，探索曲库中的音乐'
    return { song, score, reason }
  }).sort((a, b) => b.score - a.score || a.song.id.localeCompare(b.song.id))
}

/** Diversity is a soft penalty, not a filter: a one-artist library still fills the list. */
export function diversify<T extends { song: Song; score: number }>(ranked: T[], limit: number): T[] {
  const remaining = [...ranked], result: T[] = [], counts = new Map<string, number>()
  while (remaining.length && result.length < limit) {
    let best = 0, score = -Infinity
    for (let i = 0; i < remaining.length; i++) {
      const artist = norm(remaining[i].song.artist || '')
      const adjusted = remaining[i].score - (meaningful(artist) ? (counts.get(artist) || 0) * 4 : 0)
      if (adjusted > score) { best = i; score = adjusted }
    }
    const [item] = remaining.splice(best, 1)
    const artist = norm(item.song.artist || '')
    counts.set(artist, (counts.get(artist) || 0) + 1); result.push(item)
  }
  return result
}

export function recommendationFeed(songs: Song[], history: PlayHistory[], limit: number, now = Date.now()): RecommendationFeed {
  const model = buildBehaviorModel(songs, history, now)
  const ranked = rankLibrary(songs, model, now)
  const familiar = diversify(ranked, limit)
  const discoveries = diversify(ranked.filter(item => !item.song.isFavorite && !(item.song.playCount && item.song.playCount > 1) && (model.trackScores.get(item.song.id) || 0) >= 0 && (model.artistScores.get(norm(item.song.artist || '')) || 0) >= 0), Math.ceil(limit / 5))
  const selected: typeof ranked = [], used = new Set<string>()
  for (let i = 0; i < limit; i++) {
    const discovery = i % 5 === 4 ? discoveries.find(item => !used.has(item.song.id)) : undefined
    const next = discovery || familiar.find(item => !used.has(item.song.id))
    if (!next) break
    selected.push(discovery ? { ...next, reason: `探索推荐 · ${next.reason}` } : next); used.add(next.song.id)
  }
  return { songs: selected.map(item => item.song), reasons: Object.fromEntries(selected.map(item => [item.song.id, item.reason])), profile: model.profile }
}

function safeTerms(value: unknown): string[] {
  return Array.isArray(value) ? [...new Set(value.filter((x): x is string => typeof x === 'string').map(x => x.trim().slice(0, 60)).filter(Boolean))].slice(0, 12) : []
}
export function validateIntent(value: unknown): SceneIntent {
  if (!value || typeof value !== 'object') throw new Error('无效场景')
  const v = value as Record<string, unknown>
  if (!['any', 'run', 'focus', 'sleep', 'relax'].includes(String(v.scene)) || !Array.isArray(v.keywords)) throw new Error('无效场景字段')
  return {
    scene: v.scene as SceneIntent['scene'],
    durationSeconds: typeof v.durationSeconds === 'number' && Number.isFinite(v.durationSeconds) && v.durationSeconds > 0 ? clamp(Math.round(v.durationSeconds), 60, 21_600) : null,
    count: typeof v.count === 'number' && Number.isFinite(v.count) && v.count > 0 ? clamp(Math.round(v.count), 1, 80) : null,
    artists: safeTerms(v.artists), genres: canonicalGenres(safeTerms(v.genres)), keywords: safeTerms(v.keywords), exclude: safeTerms(v.exclude),
    language: ['chinese', 'english', 'japanese', 'korean'].includes(String(v.language)) ? v.language as SceneIntent['language'] : 'any',
  }
}
function chineseNumber(value: string): number {
  if (/^\d+(\.\d+)?$/.test(value)) return Number(value)
  const digits = '零一二三四五六七八九'
  const v = value.replace(/两/g, '二')
  if (v.includes('百')) { const [a, b] = v.split('百'); return (digits.indexOf(a) || 1) * 100 + (b ? chineseNumber(b.replace(/^零/, '')) : 0) }
  if (v.includes('十')) { const [a, b] = v.split('十'); return (a ? digits.indexOf(a) : 1) * 10 + (b ? digits.indexOf(b) : 0) }
  return digits.indexOf(v)
}
export function localIntent(prompt: string): SceneIntent {
  const excluded = [...prompt.matchAll(/(?:不要|排除|不含|不想听)\s*([^，,。；;\s]+)/g)].map(match => match[1].replace(/的?(歌曲|音乐|歌)$/, '')).filter(Boolean).slice(0, 12)
  const positive = excluded.reduce((text, term) => text.replace(term, ''), norm(prompt))
  const minutes = prompt.match(/([\d.零一二三四五六七八九十百两]+)\s*(?:分钟|分鍾|minutes?\b|min\b)/i)
  const hours = prompt.match(/([\d.一二三四五六两]+)\s*(?:个?小时|hours?\b)/i)
  const count = prompt.match(/([\d一二三四五六七八九十百两]+)\s*首/)
  const seconds = minutes ? chineseNumber(minutes[1]) * 60 : hours ? chineseNumber(hours[1]) * 3600 : /半小时/.test(prompt) ? 1800 : 0
  return {
    scene: /夜跑|跑步|运动|健身|running|workout/.test(positive) ? 'run' : /睡|助眠|sleep/.test(positive) ? 'sleep' : /专注|工作|学习|focus|study/.test(positive) ? 'focus' : /安静|放松|雨夜|relax/.test(positive) ? 'relax' : 'any',
    durationSeconds: seconds > 0 ? clamp(Math.round(seconds), 60, 21_600) : null,
    count: count ? clamp(chineseNumber(count[1]), 1, 80) : null, artists: [],
    genres: Object.entries(GENRES).filter(([, aliases]) => aliases.some(alias => contains(positive, alias))).map(([name]) => name),
    keywords: [], exclude: excluded,
    language: /华语|国语|中文|粤语|mandarin|chinese/.test(positive) ? 'chinese' : /英语|英文|english/.test(positive) ? 'english' : /日语|日文|japanese/.test(positive) ? 'japanese' : /韩语|韩文|korean/.test(positive) ? 'korean' : 'any',
  }
}
export function mergeIntent(local: SceneIntent, ai: SceneIntent): SceneIntent {
  return { ...ai, scene: local.scene !== 'any' ? local.scene : ai.scene,
    durationSeconds: local.durationSeconds ?? ai.durationSeconds, count: local.count ?? ai.count,
    genres: [...new Set([...local.genres, ...ai.genres])], exclude: [...new Set([...local.exclude, ...ai.exclude])],
    language: local.language !== 'any' ? local.language : ai.language }
}
const SCENE_GENRES: Record<SceneIntent['scene'], string[]> = {
  any: [], run: ['摇滚', '电子', '舞曲', '嘻哈', '流行'], focus: ['轻音乐', '氛围', '古典', '爵士'], sleep: ['氛围', '轻音乐'], relax: ['民谣', '爵士', '轻音乐', 'R&B'],
}
function localText(song: Song): string {
  let path = song.remotePath || ''
  try { path = decodeURIComponent(path) } catch { /* unescaped path */ }
  return norm([song.title, song.artist, song.album, song.genre, ...path.split('/').slice(-3)].join(' '))
}
function languageOf(song: Song): SceneIntent['language'] {
  let path = song.remotePath || ''
  try { path = decodeURIComponent(path) } catch { /* unescaped path */ }
  const text = norm([song.genre, song.album, ...path.split('/').slice(-3, -1)].join(' '))
  if (/华语|国语|粤语|中文|mandopop|cantopop|mandarin|c-pop|chinese/.test(text)) return 'chinese'
  if (/日语|日文|j-pop|japanese/.test(text)) return 'japanese'
  if (/韩语|韩文|k-pop|korean/.test(text)) return 'korean'
  if (/英语|英文|english/.test(text)) return 'english'
  return 'any' // A Chinese title is not proof that a track is Chinese-language.
}

export function retrieveCandidates(songs: Song[], model: BehaviorModel, prompt: string, intent: SceneIntent, limit = 240) {
  const wanted = norm(prompt)
  const positive = intent.exclude.reduce((text, term) => text.replace(norm(term), ''), wanted)
  const namedArtists = [...new Set(songs.map(s => s.artist).filter(a => meaningful(a || '') && contains(positive, norm(a))))]
  const explicitGenres = localIntent(prompt).genres
  const excludedGenres = canonicalGenres(intent.exclude)
  const sceneGenres = SCENE_GENRES[intent.scene]
  const ranked = rankLibrary(songs, model).flatMap(item => {
    const text = localText(item.song), genres = genresOf(item.song), language = languageOf(item.song)
    if (intent.exclude.some(word => contains(text, norm(word))) || genres.some(g => excludedGenres.includes(g))) return []
    if (namedArtists.length && !namedArtists.some(a => norm(a) === norm(item.song.artist))) return []
    if (explicitGenres.length && !genres.some(g => explicitGenres.includes(g))) return []
    if (intent.language !== 'any' && language !== 'any' && language !== intent.language) return []
    const titleMatch = meaningful(item.song.title) && contains(positive, norm(item.song.title))
    // Uncached WebDAV tracks often keep "artist - title" in the filename/title only.
    const artistMatch = intent.artists.some(a => contains(norm(`${item.song.artist || ''} ${item.song.title}`), norm(a)))
    const genreMatch = genres.some(g => intent.genres.includes(g))
    const keywordMatch = intent.keywords.filter(word => contains(text, norm(word))).length
    const sceneMatch = genres.some(g => sceneGenres.includes(g))
    const languageMatch = intent.language !== 'any' && language === intent.language
    const relevance = Number(titleMatch) * 100 + Number(artistMatch) * 35 + Number(genreMatch) * 25 + keywordMatch * 12 + Number(sceneMatch) * 10 + Number(languageMatch) * 24
    // Explicit request relevance must outrank familiarity; otherwise favorite songs can hide rare matches again.
    return [{ ...item, score: clamp(item.score, -8, 8) + relevance * 2, relevance }]
  })
  ranked.sort((a, b) => b.score - a.score || a.song.id.localeCompare(b.song.id))
  const candidates = diversify(ranked, limit)
  const warnings: string[] = []
  if (!candidates.length) warnings.push('曲库中没有满足明确筛选条件的歌曲，请放宽类型、歌手或排除条件。')
  else if (intent.language !== 'any' && candidates.some(item => languageOf(item.song) === 'any')) warnings.push('部分歌曲缺少明确的语种标签，语言匹配需要试听确认；不会仅凭中文歌名判定华语。')
  if (intent.scene !== 'any') warnings.push('场景匹配根据歌曲信息和类型推测，未分析音频节奏或 BPM。')
  return { candidates: candidates.map(item => item.song), eligibleSize: ranked.length, warnings }
}

export function estimatedDuration(song: Song): number { return Number.isFinite(song.duration) && song.duration > 0 ? Math.round(song.duration) : 240 }

/** Keep AI order, then fill/trim locally for a duration request; unknown lengths are explicit estimates. */
export function fitPlaylist(ordered: Song[], intent: SceneIntent): Song[] {
  const unique = [...new Map(ordered.map(song => [song.id, song])).values()]
  const cap = intent.count || 80
  if (!intent.durationSeconds) return unique.slice(0, intent.count || 20)
  const target = intent.durationSeconds
  const selected: Song[] = [], used = new Set<string>()
  let total = 0
  for (const song of unique) {
    if (selected.length >= cap || total >= target) break
    const next = total + estimatedDuration(song)
    if (next <= target || Math.abs(next - target) < Math.abs(total - target)) {
      selected.push(song); used.add(song.id); total = next
    }
  }
  // One replacement pass improves fit without shuffling the whole curated list.
  let best = Math.abs(total - target), replaceIndex = -1, replacement: Song | undefined
  for (let i = 0; i < selected.length; i++) for (const song of unique) {
    if (used.has(song.id)) continue
    const delta = Math.abs(total - estimatedDuration(selected[i]) + estimatedDuration(song) - target)
    if (delta < best) { best = delta; replaceIndex = i; replacement = song }
  }
  if (replacement) selected[replaceIndex] = replacement
  if (!selected.length && unique.length) selected.push([...unique].sort((a, b) => Math.abs(estimatedDuration(a) - target) - Math.abs(estimatedDuration(b) - target))[0])
  return selected
}
