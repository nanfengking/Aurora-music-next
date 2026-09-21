import { t, tm, artistLabel } from '../i18n'
import { useEffect, useMemo, useRef, useState } from 'react';
import type { Song } from '../types/music';
import { audioEngine, type PlayState } from '../services/audioEngine';
import { Artwork, localAssetUrl } from './Artwork';
import { AssetFinder } from './AssetFinder';
import { Modal } from './Modal';
import { Timeline, Transport } from './PlayerBar';
import { Icon } from './Icon';
function parseLyrics(text: string) {
    const offset = Number(text.match(/\[offset:([+-]?\d+)\]/i)?.[1] || 0) / 1000;
    return text.split(/\r?\n/).flatMap(line => {
        const matches = [...line.matchAll(/\[(\d+):(\d+(?:\.\d+)?)\]/g)];
        const content = line.replace(/\[[^\]]*\]/g, '').trim();
        if (!content)
            return [];
        if (!matches.length)
            return [{ time: -1, text: content }];
        return matches.map(match => ({ time: Math.max(0, Number(match[1]) * 60 + Number(match[2]) + offset), text: content }));
    }).sort((a, b) => a.time - b.time);
}
export function NowPlaying({ song, songs, state, onClose, onFavorite, onResourcesChanged }: {
    song: Song;
    songs: Song[];
    state: PlayState;
    onClose: () => void;
    onFavorite: (song: Song) => void;
    onResourcesChanged: () => void;
}) {
    const [finding, setFinding] = useState(false);
    const lines = useMemo(() => parseLyrics(song.customLyrics || ''), [song.customLyrics]);
    const synced = lines.some(line => line.time >= 0);
    const active = synced ? lines.reduce((index, line, i) => line.time <= state.currentTime ? i : index, -1) : -1;
    const lyricRoot = useRef<HTMLDivElement>(null);
    useEffect(() => {
        const root = lyricRoot.current;
        const line = root?.querySelector<HTMLElement>('[aria-current="true"]');
        if (root && line)
            root.scrollTo({ top: line.offsetTop - root.clientHeight / 2 + line.clientHeight / 2, behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth' });
    }, [active]);
    return <Modal title={t("正在播放")} onClose={onClose} className={`now-playing-dialog ${lines.length ? 'with-lyrics' : ''}`}>
    {(song.customArtistImage || song.coverPath) && <div className="full-backdrop">{song.customArtistImage ? <img src={localAssetUrl(song.customArtistImage)} alt=""/> : <Artwork song={song} size="large"/>}</div>}
    <div className="listening-stage"><section className="listening-record"><Artwork song={song} size="large"/>
      <div className="listening-title"><div><h1>{song.title}</h1><p>{artistLabel(song.artist)}{song.album && ` · ${song.album}`}</p></div><button className={`icon-button ${song.isFavorite ? 'favorite' : ''}`} onClick={() => onFavorite(song)} aria-label={song.isFavorite ? t("取消喜欢") : t("喜欢")}><Icon name="heart" filled={Boolean(song.isFavorite)}/></button></div>
      <Timeline song={song} state={state}/><Transport song={song} songs={songs} state={state}/>
      <button className="text-button find-assets" onClick={() => setFinding(true)}>{t("查找歌词、封面与背景")}</button>
      {state.error && <p className="form-error" role="alert">{tm(state.error)}</p>}
    </section>
    {lines.length > 0 && <div className={`lyrics-scroll ${synced ? 'synced' : ''}`} ref={lyricRoot} aria-label={t("歌词")}>{lines.map((line, index) => <button key={`${index}-${line.time}`} aria-current={index === active} className={index === active ? 'active' : ''} disabled={line.time < 0} onClick={() => audioEngine.seek(line.time)}>{line.text}</button>)}</div>}
    </div>
    {finding && <AssetFinder song={song} onClose={() => setFinding(false)} onApplied={onResourcesChanged}/>}
  </Modal>;
}
