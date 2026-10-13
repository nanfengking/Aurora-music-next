import { useEffect, useState } from 'react'
import { t, artistLabel, getLocale } from '../i18n'
import type { Song } from '../types/music'
import { Modal } from './Modal'
import { Artwork } from './Artwork'
import { formatTime } from './PlayerBar'

export function SongDetails({ song, onClose }: { song: Song; onClose: () => void }) {
  const [detail, setDetail] = useState(song)
  useEffect(() => {
    let active = true
    void window.electronAPI.getSongById(song.id).then(result => { if (active && result.data) setDetail(result.data) }).catch(() => {})
    return () => { active = false }
  }, [song.id])
  const unknown = t("未读取")
  const bytes = detail.remoteSize || detail.cacheSize || 0
  const fields = [
    [t("艺术家"), artistLabel(detail.artist)], [t("专辑"), detail.album || '—'],
    [t("音乐类型"), detail.genre || '—'], [t("发行年份"), detail.year || '—'],
    [t("时长"), detail.duration ? formatTime(detail.duration) : unknown],
    [t("文件格式"), detail.format || unknown], [t("音频编码"), detail.codec || unknown],
    [t("采样率"), detail.sampleRate ? `${detail.sampleRate / 1000} kHz` : unknown],
    [t("比特率"), detail.bitrate ? `${Math.round(detail.bitrate / 1000)} kbps` : unknown],
    [t("位深"), detail.bitDepth ? `${detail.bitDepth} bit` : unknown], [t("声道数"), detail.channels || unknown],
    [t("文件大小"), bytes ? `${(bytes / 1024 ** 2).toFixed(2)} MB` : unknown],
    ['ReplayGain', detail.replayGainDb != null ? `${detail.replayGainDb.toFixed(2)} dB` : unknown],
    [t("播放次数"), detail.playCount || 0],
    [t("最近播放"), detail.lastPlayedAt ? new Date(detail.lastPlayedAt).toLocaleString(getLocale()) : '—'],
    [t("WebDAV 路径"), detail.remotePath || '—'],
  ]
  return <Modal title={t("歌曲详情")} onClose={onClose} className="song-details-dialog">
    <div className="detail-identity"><Artwork song={detail} size="medium"/><div><h3>{detail.title}</h3><p>{artistLabel(detail.artist)}</p></div></div>
    <dl className="song-detail-grid">{fields.map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{value}</dd></div>)}</dl>
    <p className="inline-note">{t("仅展示已读取的信息；打开详情不会下载音频。未读取的项目会在音频缓存解析后补充。")}</p>
  </Modal>
}
