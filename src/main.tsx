import React from 'react'
import ReactDOM from 'react-dom/client'
import App from './App'
import { getLocale, t } from './i18n'
import { initializeDesktopPreferences } from './stores/desktopPreferences'

const root = ReactDOM.createRoot(document.getElementById('root')!)
const startupMessages = {
  'zh-CN': ['无法读取设置，原资料未清除。请检查存储权限或更新 Aurora 后重试。', '重试'],
  'zh-TW': ['無法讀取設定，原資料未清除。請檢查儲存權限或更新 Aurora 後重試。', '重試'],
  en: ['Settings could not be read. Your data has not been cleared. Check storage permissions or update Aurora and retry.', 'Retry'],
  fr: ['Impossible de lire les réglages. Vos données n’ont pas été effacées. Vérifiez les autorisations ou mettez Aurora à jour.', 'Réessayer'],
}
let starting = false
async function start() {
  if (starting) return
  starting = true
  root.render(<main className="soft-empty" role="status">{t('正在读取音乐库…')}</main>)
  try {
    await initializeDesktopPreferences()
    root.render(<React.StrictMode><App /></React.StrictMode>)
  } catch {
    const [message, retry] = startupMessages[getLocale()]
    root.render(<main className="soft-empty" data-testid="preference-startup-error" role="alert">
      <p>{message}</p><button className="secondary-button" onClick={() => void start()}>{retry}</button>
    </main>)
  } finally { starting = false }
}
void start()
