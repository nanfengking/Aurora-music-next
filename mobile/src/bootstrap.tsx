import { StrictMode } from 'react'
import type { Root } from 'react-dom/client'
import { App } from './App'
import { readNativeAppearance } from './appearance'
import { native } from './hooks'
import { setLocale, t } from './i18n'

function Startup({ failed, retry }: { failed: boolean; retry: () => void }) {
  return <main className="preference-startup" role={failed ? 'alert' : 'status'} aria-busy={!failed}>
    <div className="wordmark">AURORA<span>music</span></div>
    <p>{t(failed ? 'settingsStartupFailed' : 'settingsStartup')}</p>
    {failed && <button className="outlined-button" onClick={retry}>{t('settingsRetry')}</button>}
  </main>
}

/** Mount writers only after the complete native snapshot; retry never resets stored data. */
export function startMobile(root: Root): void {
  if (!native) { root.render(<StrictMode><App/></StrictMode>); return }
  let attempt = 0
  const start = async () => {
    const request = ++attempt
    root.render(<Startup failed={false} retry={() => void start()}/>)
    let timer: ReturnType<typeof setTimeout> | undefined
    try {
      const initial = await Promise.race([
        readNativeAppearance(),
        new Promise<never>((_resolve, reject) => { timer = setTimeout(() => reject(new Error('Preference startup timed out')), 15_000) }),
      ])
      if (request !== attempt) return
      setLocale(initial.effectiveLanguage, false)
      document.documentElement.lang = initial.effectiveLanguage
      root.render(<StrictMode><App initialAppearance={initial}/></StrictMode>)
    } catch {
      if (request === attempt) root.render(<Startup failed retry={() => void start()}/>)
    } finally { clearTimeout(timer) }
  }
  void start()
}
