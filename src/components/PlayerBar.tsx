import { t, tm, artistLabel } from '../i18n'
import { useRef, type CSSProperties } from 'react';
import type { Song } from '../types/music';
import { audioEngine, type PlayMode, type PlayState } from '../services/audioEngine';
import { usePlayerStore } from '../stores/playerStore';
import { Artwork } from './Artwork';
import { Icon } from './Icon';
export const formatTime = (value: number) => Number.isFinite(value) && value >= 0
    ? `${Math.floor(value / 60)}:${Math.floor(value % 60).toString().padStart(2, '0')}` : '0:00';
export const rangeStyle = (value: number) => ({ '--range-fill': `${Math.max(0, Math.min(100, value))}%` } as CSSProperties);
export function Timeline({ state, song }: {
    state: PlayState;
    song: Song | null;
}) {
    const duration = state.duration || song?.duration || 0;
    return <div className="timeline"><span>{formatTime(state.currentTime)}</span>
    <input aria-label={t("播放进度")} aria-valuetext={`${formatTime(state.currentTime)} / ${formatTime(duration)}`} disabled={!song || !duration} type="range" min="0" max="1000" value={state.progress * 1000 || 0} style={rangeStyle(state.progress * 100)} onChange={(event) => audioEngine.seek(Number(event.target.value) / 1000 * duration)}/>
    <span>{formatTime(duration)}</span></div>;
}
export function Transport({ song, state, songs }: {
    song: Song | null;
    state: PlayState;
    songs: Song[];
}) {
    const mode = usePlayerStore((store) => store.playMode);
    const modes: PlayMode[] = ['sequential', 'smart-shuffle', 'repeat-one'];
    const label = mode === 'sequential' ? t("顺序播放") : mode === 'smart-shuffle' ? t("随机播放") : t("单曲循环");
    return <div className="transport-buttons">
    <button className={mode === 'smart-shuffle' ? 'enabled' : ''} onClick={() => audioEngine.setPlayMode(mode === 'smart-shuffle' ? 'sequential' : 'smart-shuffle')} aria-label={t("随机播放")} aria-pressed={mode === 'smart-shuffle'} title={t("随机播放")}><Icon name="shuffle" size={17}/></button>
    <button onClick={() => audioEngine.prev(songs)} disabled={!song} aria-label={t("上一首")}><Icon name="prev" size={21}/></button>
    <button className="main-play" onClick={() => song && audioEngine.togglePlay(song)} disabled={!song} aria-label={state.isPlaying ? t("暂停") : t("播放")}>
      {state.isLoading ? <i className="spinner"/> : <Icon name={state.isPlaying ? 'pause' : 'play'} size={26}/>}</button>
    <button onClick={() => audioEngine.next(songs)} disabled={!song} aria-label={t("下一首")}><Icon name="next" size={21}/></button>
    <button className={mode === 'repeat-one' ? 'enabled' : ''} aria-label={t("播放模式：{0}", [label])} title={label} onClick={() => audioEngine.setPlayMode(modes[(modes.indexOf(mode) + 1) % modes.length])}><Icon name="repeat" size={17}/>{mode === 'repeat-one' && <sup>1</sup>}</button>
  </div>;
}
export function PlayerBar({ song, state, songs, onQueue, onExpand, onFavorite, queueOpen }: {
    song: Song | null;
    state: PlayState;
    songs: Song[];
    onQueue: () => void;
    onExpand: () => void;
    onFavorite: (song: Song) => void;
    queueOpen: boolean;
}) {
    const volume = usePlayerStore((store) => store.volume);
    const beforeMute = useRef(0.8);
    return <footer className={`player-bar ${song ? '' : 'idle'}`}>
    <div className="player-identity"><button className="now-playing" onClick={onExpand} disabled={!song} aria-label={song ? t("展开 {0}", [song.title]) : t("尚未播放")}>
      <Artwork song={song} size="small"/><span><b>{song?.title || t("选择一首音乐")}</b><small>{song ? artistLabel(song.artist) : 'Aurora Music'}</small></span></button>
      {song && <button className={`icon-button ${song.isFavorite ? 'favorite' : ''}`} onClick={() => onFavorite(song)} aria-label={song.isFavorite ? t("取消喜欢当前歌曲") : t("喜欢当前歌曲")}><Icon name="heart" filled={Boolean(song.isFavorite)} size={18}/></button>}</div>
    <div className="player-center"><Transport song={song} state={state} songs={songs}/><Timeline song={song} state={state}/>
      {state.isLoading && state.loadingMessage && <p className="player-loading" role="status">{tm(state.loadingMessage)}</p>}
      {state.error && <p className="player-error" role="alert">{tm(state.error)}</p>}</div>
    <div className="player-tools"><button className="icon-button lyrics-toggle" onClick={onExpand} disabled={!song} title={t("正在播放与歌词")} aria-label={t("正在播放与歌词")}><Icon name="lyrics" size={19}/></button>
      <button className={`icon-button ${queueOpen ? 'enabled' : ''}`} onClick={onQueue} aria-label={t("播放队列")} aria-pressed={queueOpen}><Icon name="queue" size={19}/></button>
      <span className="tool-divider"/><button className="icon-button volume-button" aria-label={volume ? t("静音") : t("恢复音量")} onClick={() => { if (volume) {
        beforeMute.current = volume;
        audioEngine.setVolume(0);
    }
    else
        audioEngine.setVolume(beforeMute.current); }}><Icon name="volume" size={17}/>{!volume && <i />}</button>
      <input aria-label={t("音量")} type="range" min="0" max="1" step=".01" value={volume} style={rangeStyle(volume * 100)} onChange={(event) => audioEngine.setVolume(Number(event.target.value))}/>
    </div>
  </footer>;
}
