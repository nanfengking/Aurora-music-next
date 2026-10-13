import { t } from '../i18n'
import { usePlayerPreferences } from '../stores/playerPreferences'
import { usePlayerStore } from '../stores/playerStore'
import { Icon } from './Icon'

export function PlayerPreferences() {
  const { preferences: value, update, reset } = usePlayerPreferences()
  return <section className="settings-card wide player-preferences">
    <div className="setting-title"><span><Icon name="settings"/></span><div><h2>{t("播放器与个性化")}</h2><p>{t("窗口随大小切换形态，拖动顶部可移动窗口。")}</p></div></div>
    <div className="preference-grid">
      <label><span>{t("记住歌曲、进度和队列")}</span><input type="checkbox" checked={value.rememberPlayback} onChange={event => { update({ rememberPlayback: event.target.checked }); if (!event.target.checked) usePlayerStore.persist.clearStorage() }}/></label>
      <label><span>{t("窗口置顶")}</span><input type="checkbox" checked={value.alwaysOnTop} onChange={event => update({ alwaysOnTop: event.target.checked })}/></label>
      <label><span>{t("提前缓存下一首")}</span><input type="checkbox" checked={value.prefetch} onChange={event => update({ prefetch: event.target.checked })}/></label>
      <label><span>{t("音量均衡（ReplayGain）")}</span><input type="checkbox" checked={value.normalizeVolume} onChange={event => update({ normalizeVolume: event.target.checked })}/></label>
      <label><span>{t("交叉淡化")}</span><select value={value.crossfadeSeconds} onChange={event => update({ crossfadeSeconds: Number(event.target.value) })}><option value={0}>{t("关闭")}</option>{[1, 2, 3].map(seconds => <option key={seconds} value={seconds}>{t("{0} 秒", [seconds])}</option>)}</select></label>
      <label><span>{t("歌词字号")}</span><select value={value.lyricsSize} onChange={event => update({ lyricsSize: Number(event.target.value) })}>{[18, 24, 30, 36].map(size => <option key={size} value={size}>{size} px</option>)}</select></label>
      <label><span>{t("封面氛围背景")}</span><input type="checkbox" checked={value.showBackdrop} onChange={event => update({ showBackdrop: event.target.checked })}/></label>
      <label><span>{t("强调色")}</span><select value={value.accent} onChange={event => update({ accent: event.target.value as typeof value.accent })}><option value="rose">{t("玫瑰")}</option><option value="blue">{t("海蓝")}</option><option value="green">{t("森林")}</option><option value="violet">{t("紫罗兰")}</option></select></label>
    </div>
    <p className="inline-note">{t("音量均衡仅对已读取 ReplayGain 标签的歌曲生效；交叉淡化取决于下一首音频的加载速度。预下载默认关闭。")}</p>
    <div className="settings-footer"><p>{t("恢复播放后保持暂停，按播放继续。")}</p><button className="text-button" onClick={reset}>{t("恢复播放器默认设置")}</button></div>
  </section>
}
