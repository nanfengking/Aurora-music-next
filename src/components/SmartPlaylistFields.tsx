import { t } from '../i18n'
import type { SmartPlaylistRules } from '../types/music'

export function SmartPlaylistFields({ rules, onChange }: { rules: SmartPlaylistRules; onChange: (rules: SmartPlaylistRules) => void }) {
  const update = (patch: Partial<SmartPlaylistRules>) => onChange({ ...rules, ...patch })
  return <div className="smart-rules">
    <p className="inline-note">{t("曲库、收藏和播放记录变化时自动更新，不调用 API。播放中的队列不会被重排。")}</p>
    <label className="hybrid-toggle"><input type="checkbox" checked={rules.favoritesOnly} onChange={event => update({ favoritesOnly: event.target.checked })}/>{t("仅喜欢的歌曲")}</label>
    <div className="settings-form">
      <label><span>{t("艺术家包含")}</span><input maxLength={100} value={rules.artist} onChange={event => update({ artist: event.target.value })} placeholder={t("不限")}/></label>
      <label><span>{t("音乐类型包含")}</span><input maxLength={100} value={rules.genre} onChange={event => update({ genre: event.target.value })} placeholder={t("不限")}/></label>
      <label><span>{t("最近听过")}</span><select value={rules.recentDays} onChange={event => update({ recentDays: Number(event.target.value) })}><option value={0}>{t("不限")}</option>{[7, 30, 90, 365].map(days => <option key={days} value={days}>{t("最近 {0} 天", [days])}</option>)}</select></label>
      <label><span>{t("排序方式")}</span><select value={rules.sort} onChange={event => update({ sort: event.target.value as SmartPlaylistRules['sort'] })}><option value="recent">{t("最近播放")}</option><option value="most-played">{t("最常播放")}</option><option value="newest">{t("最近入库")}</option><option value="title">{t("歌曲名称")}</option></select></label>
      <label><span>{t("最多歌曲数")}</span><input type="number" min={1} max={5000} value={rules.limit} onChange={event => update({ limit: Math.max(1, Math.min(5000, Number(event.target.value) || 1)) })}/></label>
      <label><span>{t("时长上限（分钟，0 为不限）")}</span><input type="number" min={0} max={1440} value={rules.durationMinutes} onChange={event => update({ durationMinutes: Math.max(0, Math.min(1440, Number(event.target.value) || 0)) })}/></label>
    </div>
    {rules.durationMinutes > 0 && <p className="inline-note">{t("设置时长上限后，仅纳入已知时长的歌曲，总时长不会超过上限。")}</p>}
  </div>
}
