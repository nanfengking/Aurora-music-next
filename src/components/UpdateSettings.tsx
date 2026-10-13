import { useEffect, useState } from 'react'
import { t } from '../i18n'
import type { UpdateStatus } from '../types/update'
import { Icon } from './Icon'
import { audioEngine } from '../services/audioEngine'

const initial: UpdateStatus = { phase: 'idle', currentVersion: '—' }

export function UpdateSettings() {
  const [status, setStatus] = useState<UpdateStatus>(initial)
  const [busy, setBusy] = useState(false)
  useEffect(() => {
    let active = true
    const unsubscribe = window.electronAPI.onUpdateStatus(next => { if (active) setStatus(next) })
    void window.electronAPI.getUpdateStatus().then(response => { if (active && response.data) setStatus(response.data) })
    return () => { active = false; unsubscribe() }
  }, [])
  const check = async () => {
    setBusy(true)
    try {
      const response = await window.electronAPI.checkForUpdates()
      if (response.data) setStatus(response.data)
      else if (response.error) setStatus(value => ({ ...value, phase: 'error', message: response.error }))
    } finally { setBusy(false) }
  }
  const download = async () => {
    setBusy(true)
    try {
      const response = await window.electronAPI.downloadUpdate()
      if (response.data) setStatus(response.data)
      else if (response.error) setStatus(value => ({ ...value, phase: 'error', message: response.error }))
    } finally { setBusy(false) }
  }
  const install = async () => {
    audioEngine.saveSession()
    await window.electronAPI.installUpdate()
  }
  const message = status.message ? t(status.message) : ({
    idle: t('点击检查更新。'), checking: t('正在检查 GitHub Releases…'),
    available: t('发现新版本 {0}。', [status.nextVersion || '']),
    downloading: t('下载中：{0}%', [status.progress || 0]),
    ready: t('新版本已下载，安装时会关闭并重新打开播放器。'),
    'up-to-date': t('当前已是最新版本。'), error: t('更新失败，请稍后重试。'),
    unsupported: t('应用内更新仅支持已安装的 Windows 正式版。'),
  } as Record<UpdateStatus['phase'], string>)[status.phase]
  return <section className="settings-card wide"><div className="setting-title"><span><Icon name="download"/></span><div><h2>{t('应用更新')}</h2><p>{t('当前版本 {0}；同系列新版可直接覆盖安装，无需先卸载。', [status.currentVersion])}</p></div></div>
    <p className="inline-note" role="status">{message}</p>
    {status.phase === 'downloading' && <progress max="100" value={status.progress || 0} aria-label={t('更新下载进度')}/>}
    <div className="settings-footer"><p>{t('只从项目 GitHub Releases 获取更新；下载和安装均由你确认。')}</p><div className="button-row">
      <button className="secondary-button" onClick={() => void window.electronAPI.openReleasePage()}>{t('查看发布页')}</button>
      {status.phase === 'available' ? <button className="primary-button" disabled={busy} onClick={() => void download()}>{t('下载新版本')}</button>
        : status.phase === 'ready' ? <button className="primary-button" onClick={() => void install()}>{t('安装并重启')}</button>
        : <button className="primary-button" disabled={busy || status.phase === 'checking' || status.phase === 'downloading' || status.phase === 'unsupported'} onClick={() => void check()}>{t('检查更新')}</button>}
    </div></div>
  </section>
}
