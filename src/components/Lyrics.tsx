import { useEffect, useMemo, useRef } from 'react'
import { t } from '../i18n'
import { audioEngine } from '../services/audioEngine'
import { usePlayerPreferences } from '../stores/playerPreferences'

export function parseLyrics(text: string) {
  const offset = Number(text.match(/\[offset:([+-]?\d+)\]/i)?.[1] || 0) / 1000
  return text.split(/\r?\n/).flatMap(line => {
    const matches = [...line.matchAll(/\[(\d+):(\d+(?:\.\d+)?)\]/g)]
    const content = line.replace(/\[[^\]]*\]/g, '').trim()
    if (!content) return []
    return matches.length ? matches.map(match => ({ time: Math.max(0, Number(match[1]) * 60 + Number(match[2]) + offset), text: content })) : [{ time: -1, text: content }]
  }).sort((a, b) => a.time - b.time)
}

export function Lyrics({ text, currentTime, onFind }: { text: string; currentTime: number; onFind?: () => void }) {
  const lines = useMemo(() => parseLyrics(text), [text])
  const size = usePlayerPreferences(store => store.preferences.lyricsSize)
  const synced = lines.some(line => line.time >= 0)
  const active = synced ? lines.reduce((index, line, i) => line.time >= 0 && line.time <= currentTime ? i : index, -1) : -1
  const rootRef = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const root = rootRef.current
    const line = root?.querySelector<HTMLElement>('[aria-current="true"]')
    if (root && line) root.scrollTo({ top: line.offsetTop - root.clientHeight / 2 + line.clientHeight / 2, behavior: matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth' })
  }, [active])
  if (!lines.length) return <div className="lyrics-empty"><p>{t("暂无歌词")}</p>{onFind && <button className="text-button" onClick={onFind}>{t("查找歌词与封面")}</button>}</div>
  return <div className={`lyrics-scroll ${synced ? 'synced' : ''}`} style={{ fontSize: size }} ref={rootRef} aria-label={t("歌词")}>{lines.map((line, index) =>
    <button key={`${index}-${line.time}`} aria-current={index === active} className={index === active ? 'active' : ''} disabled={line.time < 0} onClick={() => audioEngine.seek(line.time)}>{line.text}</button>,
  )}</div>
}
