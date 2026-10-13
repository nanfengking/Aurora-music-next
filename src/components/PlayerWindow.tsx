import { useEffect, useState } from 'react'
import { t, tm, artistLabel } from '../i18n'
import { playerLayout, type PlayerLayout } from '../types/player'
import type { Song } from '../types/music'
import { audioEngine } from '../services/audioEngine'
import { usePlayback } from '../services/usePlayback'
import { usePlayerPreferences } from '../stores/playerPreferences'
import { Icon } from './Icon'
import { Timeline, Transport } from './PlayerBar'
import { Lyrics } from './Lyrics'
import { localAssetUrl } from './Artwork'
import { SongDetails } from './SongDetails'
import { AssetFinder } from './AssetFinder'

export function usePlayerLayout() {
  const [layout, setLayout] = useState(() => playerLayout(innerWidth, innerHeight))
  useEffect(() => {
    const resize = () => setLayout(playerLayout(innerWidth, innerHeight))
    window.addEventListener('resize', resize)
    return () => window.removeEventListener('resize', resize)
  }, [])
  return layout
}

export function WindowBar({ layout }: { layout: PlayerLayout }) {
  const { preferences, update } = usePlayerPreferences()
  const presets = [{ value: 'mini', label: t("小播放器"), icon: 'minus' }, { value: 'lyrics', label: t("歌词播放器"), icon: 'lyrics' }, { value: 'full', label: t("完整播放器"), icon: 'grid' }] as const
  return <header className={`window-bar window-bar-${layout}`}>
    <span className="window-brand">Aurora</span>
    <nav className="window-controls" aria-label={t("窗口形态")}>
      {presets.map(preset => <button key={preset.value} aria-label={preset.label} title={preset.label} aria-pressed={layout === preset.value} onClick={() => void window.electronAPI.windowAction(preset.value)}><Icon name={preset.icon} size={14}/></button>)}
      <button className={preferences.alwaysOnTop ? 'enabled' : ''} aria-label={t("窗口置顶")} title={t("窗口置顶")} aria-pressed={preferences.alwaysOnTop} onClick={() => update({ alwaysOnTop: !preferences.alwaysOnTop })}><Icon name="pin" size={14}/></button>
      <span className="window-divider"/>
      <button aria-label={t("最小化")} title={t("最小化")} onClick={() => void window.electronAPI.windowAction('minimize')}><Icon name="minus" size={14}/></button>
      {layout === 'full' && <button aria-label={t("最大化或还原")} title={t("最大化或还原")} onClick={() => void window.electronAPI.windowAction('maximize')}><Icon name="expand" size={13}/></button>}
      <button className="window-close" aria-label={t("关闭窗口")} title={t("关闭窗口")} onClick={() => void window.electronAPI.windowAction('close')}><Icon name="close" size={14}/></button>
    </nav>
  </header>
}

export function CompactPlayer({ layout, song, songs, onResourcesChanged }: { layout: 'mini' | 'lyrics'; song: Song | null; songs: Song[]; onResourcesChanged: () => void }) {
  const state = usePlayback()
  const backdrop = usePlayerPreferences(store => store.preferences.showBackdrop)
  const [details, setDetails] = useState(false)
  const [finding, setFinding] = useState(false)
  return <main className={`compact-player compact-${layout}`}>
    {layout === 'lyrics' && backdrop && song?.coverPath && <div className="compact-backdrop"><img src={localAssetUrl(song.coverPath)} alt=""/></div>}
    <div className="compact-title"><h1 title={song?.title}>{song?.title || t("选择一首音乐")}</h1>{layout === 'lyrics' && <p>{song ? artistLabel(song.artist) : t("打开完整播放器选择音乐")}</p>}</div>
    {layout === 'lyrics' && <Lyrics text={song?.customLyrics || ''} currentTime={state.currentTime} onFind={song ? () => setFinding(true) : undefined}/>}
    <div className="compact-bottom"><Timeline song={song} state={state}/>
      {layout === 'lyrics' ? <div className="compact-transport"><Transport song={song} songs={songs} state={state}/><button className="icon-button" aria-label={t("歌曲详情")} onClick={() => setDetails(true)} disabled={!song}><Icon name="info" size={18}/></button></div>
        : <button className="mini-play" aria-label={state.isPlaying ? t("暂停") : t("播放")} disabled={!song} onClick={() => audioEngine.togglePlay()}>{state.isLoading ? <i className="spinner"/> : <Icon name={state.isPlaying ? 'pause' : 'play'} size={16}/>}</button>}
      {state.error && <p className="form-error" role="alert" title={tm(state.error)}>{tm(state.error)}</p>}
      {layout === 'lyrics' && state.isLoading && <p className="inline-note" role="status">{tm(state.loadingMessage || '')}</p>}
    </div>
    {details && song && layout === 'lyrics' && <SongDetails song={song} onClose={() => setDetails(false)}/>}
    {finding && song && layout === 'lyrics' && <AssetFinder song={song} onClose={() => setFinding(false)} onApplied={onResourcesChanged}/>}
  </main>
}
