import { useEffect, useRef, useState } from 'react'
import { t, artistLabel } from '../i18n'
import type { Song } from '../types/music'
import { qualityTier } from '../services/audioQuality'
import { Modal } from './Modal'
import { Icon } from './Icon'

function AudioDiagnosticsDialog({ song, onClose }: { song: Song | null; onClose: () => void }) {
  const [detail, setDetail] = useState<Song | null>(song)
  const [outputs, setOutputs] = useState<MediaDeviceInfo[]>([])
  const [deviceError, setDeviceError] = useState(false)
  const [readingMetadata, setReadingMetadata] = useState(false)
  const [metadataError, setMetadataError] = useState('')
  const currentSongId = useRef(song?.id)
  currentSongId.current = song?.id
  useEffect(() => {
    let active = true
    setDetail(song)
    setMetadataError('')
    if (song) void window.electronAPI.getSongById(song.id).then(response => { if (active && response.data) setDetail(response.data) })
    return () => { active = false }
  }, [song?.id])
  useEffect(() => {
    let active = true
    const media = navigator.mediaDevices
    const refresh = async () => {
      if (!media?.enumerateDevices) { if (active) setDeviceError(true); return }
      try {
        const devices = await media.enumerateDevices()
        if (active) { setOutputs(devices.filter(device => device.kind === 'audiooutput')); setDeviceError(false) }
      } catch { if (active) setDeviceError(true) }
    }
    void refresh()
    media?.addEventListener?.('devicechange', refresh)
    return () => { active = false; media?.removeEventListener?.('devicechange', refresh) }
  }, [])
  const tier = qualityTier(detail)
  const quality = {
    lossless: t('无损或 PCM 编码'),
    'high-bitrate': t('较高码率有损音源'),
    compressed: t('有损音源'),
    unknown: t('音质信息未读取'),
  }[tier]
  const defaultOutput = outputs.find(device => device.deviceId === 'default')
  const deviceLabel = defaultOutput?.label || t('Windows 系统默认输出（设备名称不可用）')
  const fields = detail ? [
    [t('音频编码'), detail.codec || t('未读取')],
    [t('文件格式'), detail.format || t('未读取')],
    [t('比特率'), detail.bitrate ? `${Math.round(detail.bitrate / 1000)} kbps` : t('未读取')],
    [t('采样率'), detail.sampleRate ? `${detail.sampleRate / 1000} kHz` : t('未读取')],
    [t('位深'), detail.bitDepth ? `${detail.bitDepth} bit` : t('未读取')],
    [t('声道数'), detail.channels || t('未读取')],
  ] : []
  const canReadMetadata = detail?.sourceType === 'webdav' && (!detail.codec || !detail.sampleRate)
  const readMetadata = async () => {
    if (!detail || readingMetadata) return
    const requestedSongId = detail.id
    setReadingMetadata(true)
    setMetadataError('')
    try {
      const saved = await window.electronAPI.webdav.loadConfig()
      if (!saved.success || !saved.data) throw new Error(t('请先在设置中保存 WebDAV 连接。'))
      const cached = await window.electronAPI.webdav.downloadSingle(saved.data, detail)
      if (!cached.success) throw new Error(cached.error || t('读取音质信息失败。'))
      const updated = await window.electronAPI.getSongById(detail.id)
      if (!updated.success || !updated.data) throw new Error(t('读取音质信息失败。'))
      if (currentSongId.current === requestedSongId) {
        setDetail(updated.data)
        if (!updated.data.codec && !updated.data.sampleRate) setMetadataError(t('已缓存歌曲，但音频文件没有提供可识别的编码信息。'))
      }
    } catch (error) {
      if (currentSongId.current === requestedSongId) setMetadataError(error instanceof Error ? error.message : t('读取音质信息失败。'))
    } finally {
      setReadingMetadata(false)
    }
  }
  return <Modal title={t('音质与听歌设备')} onClose={onClose} className="audio-diagnostics-dialog">
    <div className="audio-diagnostics-heading"><strong>{quality}</strong><span>{detail ? `${detail.title} · ${artistLabel(detail.artist)}` : t('播放一首歌曲后可查看源文件音质。')}</span></div>
    {detail && <dl className="song-detail-grid">{fields.map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{value}</dd></div>)}</dl>}
    {canReadMetadata && <div className="audio-metadata-action"><button className="secondary-button" disabled={readingMetadata} onClick={() => void readMetadata()}>{readingMetadata ? t('正在读取音质信息…') : t('读取详细音质')}</button><small>{t('仅下载当前这一首到现有缓存；可能使用网络流量与磁盘空间。')}</small></div>}
    {metadataError && <p role="alert" className="inline-note">{metadataError}</p>}
    <div className="audio-device-panel"><h3>{t('系统可见的默认输出')}</h3><p>{deviceLabel}</p><small>{t('实际播放设备可能受 Windows 每应用输出设置影响；这里不一定能读取设备名称，也不能据此判断蓝牙传输音质。')}</small>
      {outputs.length > 1 && <details><summary>{t('查看系统可见的输出设备')}</summary><ul>{outputs.filter(device => device.deviceId !== 'default').map((device, index) => <li key={device.deviceId || index}>{device.label || t('未提供设备名称')}</li>)}</ul></details>}
      {deviceError && <p className="inline-note">{t('当前环境无法读取输出设备列表，仍可在 Windows 声音设置中查看。')}</p>}
      <button className="secondary-button" onClick={() => void window.electronAPI.openSoundSettings()}>{t('打开 Windows 声音设置')}</button></div>
    <p className="inline-note">{tier === 'compressed' ? t('低码率或有损文件可能听起来发糊；软件无法恢复源文件已经丢失的细节。') : tier === 'lossless' ? t('无损音源仍可能受录音、系统音效、蓝牙编码和播放设备影响。') : t('码率标签不能单独证明真实音质；如信息未读取，请先播放或缓存歌曲。')}</p>
    {detail?.sampleRate && detail.sampleRate < 32000 && <p className="inline-note">{t('这首歌的采样率偏低；即使是 PCM 或无损编码，原始声音细节也可能有限。')}</p>}
    <p className="inline-note">{t('底噪可能来自录音或输出设备。播放器不会默认对整首音乐降噪，以免削弱高频与动态；ReplayGain 只做防削波衰减，不会放大底噪。')}</p>
  </Modal>
}

export function AudioDiagnostics({ song }: { song: Song | null }) {
  const [open, setOpen] = useState(false)
  return <section className="settings-card wide"><div className="setting-title"><span><Icon name="music"/></span><div><h2>{t('音质与设备')}</h2><p>{t('查看当前歌曲的源文件信息与 Windows 输出设备。')}</p></div></div>
    <div className="settings-footer"><p>{t('不修改音频文件，也不在后台扫描曲库。')}</p><button className="secondary-button" onClick={() => setOpen(true)}>{t('查看音质与设备')}</button></div>
    {open && <AudioDiagnosticsDialog song={song} onClose={() => setOpen(false)}/>}
  </section>
}
