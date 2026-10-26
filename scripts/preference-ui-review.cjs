// Real renderer/preload/IPC/SQLite; no API or user profile is substituted.
const { _electron: electron } = require('playwright')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { resizeReviewWindow } = require('./review-window.cjs')
const root = path.resolve(__dirname, '..')
const key = 'desktop_preferences_v1'

async function run() {
  const profile = fs.mkdtempSync(path.join(root, '.ui-review-preference-ui-'))
  const env = { ...process.env, AURORA_UI_REVIEW_PROFILE: profile }
  delete env.ELECTRON_RUN_AS_NODE
  let application
  try {
    application = await electron.launch({ executablePath: require('electron'), args: [path.join(__dirname, 'ui-review-main.cjs')], cwd: root, env })
    const page = await application.firstWindow()
    const pageErrors = []
    page.on('pageerror', error => pageErrors.push(error.message))
    await page.getByRole('heading', { name: '现在就听', exact: true }).waitFor()
    const sql = (statement, params = [], method = 'get') => application.evaluate(({ app }, input) => {
      const require = globalThis.reviewRequire
      const Database = require(require('node:path').join(input.root, 'node_modules/better-sqlite3'))
      const database = new Database(require('node:path').join(app.getPath('userData'), 'musicPlayer.db'))
      try { return database.prepare(input.statement)[input.method](...input.params) }
      finally { database.close() }
    }, { root, statement, params, method })
    const snapshot = async () => JSON.parse((await sql('SELECT value FROM settings WHERE key = ?', [key]))?.value || 'null')

    await page.getByRole('button', { name: '切换明暗主题', exact: true }).click()
    await page.waitForFunction(() => document.documentElement.dataset.theme === 'dark')
    assert.ok(await snapshot(), 'Changing theme must create durable canonical preferences, not just localStorage')
    assert.equal((await snapshot()).values.theme, 'dark')
    console.log('PREFERENCE_UI_WRITE_PASS: actual theme action commits SQLite before publication')

    // First-run migration with old browser storage, then a reload with deliberately stale values.
    await sql('DELETE FROM settings WHERE key = ?', [key], 'run')
    await page.evaluate(() => {
      localStorage.setItem('aurora-theme', 'dark')
      localStorage.setItem('aurora-language', 'fr')
      localStorage.setItem('aurora-player-preferences', JSON.stringify({ state: { preferences: { prefetch: false, accent: 'blue', lyricsSize: 30 } }, version: 0 }))
      localStorage.setItem('aurora-playback', JSON.stringify({ state: { volume: 0.25, playMode: 'repeat-one', queue: ['remembered'], currentId: 'remembered', position: 42 }, version: 0 }))
    })
    await page.reload()
    await page.waitForFunction(() => document.documentElement.lang === 'fr' && document.documentElement.dataset.accent === 'blue')
    const migrated = await snapshot()
    assert.equal(migrated.values.volume, 0.25)
    assert.equal(migrated.values.lyricsSize, 30)
    assert.equal(migrated.values.playMode, 'repeat-one')
    await page.evaluate(() => { localStorage.setItem('aurora-theme', 'light'); localStorage.setItem('aurora-language', 'en') })
    await page.reload()
    await page.waitForFunction(() => document.documentElement.lang === 'fr' && document.documentElement.dataset.theme === 'dark')
    assert.deepEqual(await snapshot(), migrated)
    console.log('PREFERENCE_UI_MIGRATION_PASS: legacy values migrate once; stale browser values cannot overwrite DB')

    const responses = await page.evaluate(() => Promise.all([
      window.electronAPI.updatePreferences({ theme: 'light', locale: 'zh-CN' }),
      window.electronAPI.updatePreferences({ volume: 0, playMode: 'smart-shuffle' }),
    ]))
    assert.ok(responses.every(result => result.success))
    await page.waitForFunction(() => document.documentElement.lang === 'zh-CN' && document.documentElement.dataset.theme === 'light')
    await page.waitForFunction(() => document.querySelector('input[aria-label="音量"]')?.value === '0')
    assert.equal((await snapshot()).values.playMode, 'smart-shuffle')
    await page.getByRole('button', { name: '随机播放', exact: true }).getAttribute('aria-pressed').then(value => assert.equal(value, 'true'))
    assert.equal((await page.evaluate(() => window.electronAPI.setSettings('desktop_preferences_v1', '{}'))).success, false)
    console.log('PREFERENCE_UI_CONCURRENCY_PASS: committed changes reach real stores, merge and reject generic bypass')

    const beforeFailure = await snapshot()
    await sql(`CREATE TRIGGER fail_preference_ui BEFORE INSERT ON settings WHEN NEW.key = '${key}' BEGIN SELECT RAISE(ABORT, 'private fixture failure'); END`, [], 'run')
    await page.getByRole('button', { name: '切换明暗主题', exact: true }).click()
    await page.getByTestId('preference-save-error').waitFor()
    fs.mkdirSync(path.join(root, 'test-results/screenshots'), { recursive: true })
    await page.screenshot({ path: path.join(root, 'test-results/screenshots/preference-save-error.png') })
    assert.equal(await page.locator('html').getAttribute('data-theme'), 'light')
    assert.deepEqual(await snapshot(), beforeFailure)
    assert.ok(!(await page.getByTestId('preference-save-error').innerText()).includes('private fixture failure'))
    await sql('DROP TRIGGER fail_preference_ui', [], 'run')
    await page.getByTestId('preference-save-error').getByRole('button').click()
    await page.waitForFunction(() => document.documentElement.dataset.theme === 'dark')
    await page.getByTestId('preference-save-error').waitFor({ state: 'hidden' })
    console.log('PREFERENCE_UI_FAILURE_PASS: rejected commit leaves UI unchanged, bounded error retries successfully')

    await sql(`CREATE TRIGGER fail_preference_ui BEFORE INSERT ON settings WHEN NEW.key = '${key}' BEGIN SELECT RAISE(ABORT, 'private fixture failure'); END`, [], 'run')
    await page.getByRole('slider', { name: '音量', exact: true }).fill('0.2')
    await page.getByTestId('preference-save-error').waitFor()
    await sql('DROP TRIGGER fail_preference_ui', [], 'run')
    await page.getByRole('slider', { name: '音量', exact: true }).fill('0.6')
    await page.waitForFunction(() => document.querySelector('input[aria-label="音量"]')?.value === '0.6')
    assert.equal(await page.getByTestId('preference-save-error').count(), 0, 'new explicit success must discard obsolete failed volume')
    assert.equal((await snapshot()).values.volume, 0.6)
    console.log('PREFERENCE_UI_SUPERSEDED_RETRY_PASS: newer successful user choice replaces failed intent')

    for (const rememberPlayback of [false, true]) {
      assert.equal((await page.evaluate(value => window.electronAPI.updatePreferences({ rememberPlayback: value }), rememberPlayback)).success, true)
    }
    await page.evaluate(() => localStorage.setItem('aurora-playback', JSON.stringify({ state: {
      queue: ['remembered'], currentId: 'remembered', position: 42, restoreRevision: 0,
    }, version: 0 })))
    await page.reload()
    await page.getByRole('heading', { name: '现在就听', exact: true }).waitFor()
    const recovery = await page.evaluate(() => JSON.parse(localStorage.getItem('aurora-playback')).state)
    assert.equal(recovery.currentId, null)
    assert.equal(recovery.position, 0)
    assert.equal(recovery.restoreRevision, 2)
    assert.deepEqual(recovery.queue, ['remembered'], 'invalidating a recovery point does not delete queue identity/history')
    assert.equal((await snapshot()).values.volume, 0.6)
    console.log('PREFERENCE_UI_RECOVERY_PASS: old generation cannot resurrect after remember off/on and reload')

    const beforeStale = await snapshot()
    await application.evaluate(({ BrowserWindow }, previous) => {
      BrowserWindow.getAllWindows()[0].webContents.send('preferences:changed', {
        ...previous, revision: previous.revision - 1, values: { ...previous.values, theme: 'light', volume: 0.9 },
      })
    }, beforeStale)
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))))
    assert.equal(await page.locator('html').getAttribute('data-theme'), 'dark')
    assert.equal(await page.getByRole('slider', { name: '音量', exact: true }).inputValue(), '0.6')
    assert.deepEqual(await snapshot(), beforeStale)
    console.log('PREFERENCE_UI_STALE_EVENT_PASS: older native event cannot regress displayed or committed settings')

    const valid = await snapshot()
    const future = JSON.stringify({ ...valid, version: 99 })
    await sql('UPDATE settings SET value = ? WHERE key = ?', [future, key], 'run')
    await page.reload()
    await page.getByTestId('preference-startup-error').waitFor()
    assert.equal(await page.locator('.desktop-app').count(), 0)
    assert.equal((await sql('SELECT value FROM settings WHERE key = ?', [key])).value, future)
    await sql('UPDATE settings SET value = ? WHERE key = ?', [JSON.stringify(valid), key], 'run')
    await page.getByTestId('preference-startup-error').getByRole('button').click()
    await page.getByRole('heading', { name: '现在就听', exact: true }).waitFor()
    await page.addInitScript(() => {
      if (localStorage.getItem('aurora-test-storage-full') === '1') {
        Storage.prototype.setItem = () => { throw new DOMException('Fixture storage full', 'QuotaExceededError') }
      }
    })
    await page.evaluate(() => localStorage.setItem('aurora-test-storage-full', '1'))
    await page.reload()
    const ready = page.getByRole('heading', { name: '现在就听', exact: true })
    await ready.or(page.getByTestId('preference-startup-error')).waitFor()
    assert.equal(await ready.count(), 1, 'optional session storage failure must not block canonical preferences or startup')
    await page.getByTestId('session-save-error').waitFor()
    const savedWithoutSession = await page.evaluate(() => window.electronAPI.updatePreferences({ volume: 0.4 }))
    assert.equal(savedWithoutSession.success, true)
    await page.waitForFunction(() => document.querySelector('input[aria-label="音量"]')?.value === '0.4')
    await page.evaluate(() => localStorage.removeItem('aurora-test-storage-full'))
    console.log('PREFERENCE_UI_SESSION_STORAGE_PASS: optional browser session quota failure does not block SQLite settings')
    console.log('PREFERENCE_UI_STARTUP_PASS: future data fails closed without reset; repair/retry mounts the application')

    // Observe real HTML audio elements; do not replace playback or its volume setter.
    await page.addInitScript(() => {
      window.reviewAudioElements = []
      window.Audio = new Proxy(window.Audio, { construct(target, args) {
        const element = new target(...args)
        window.reviewAudioElements.push(element)
        return element
      } })
    })
    await application.evaluate(require('./demo-library.cjs'), root)
    await page.reload()
    await page.getByRole('heading', { name: '为你精选', exact: true }).waitFor()
    await page.getByRole('navigation', { name: '资料库', exact: true }).getByRole('button', { name: '歌曲', exact: true }).click()
    await page.locator('.song-row').filter({ has: page.locator('.song-identity b', { hasText: /^First Light$/ }) }).locator('.song-identity').click()
    await page.waitForFunction(() => navigator.mediaSession.playbackState === 'playing')
    await resizeReviewWindow(application, page, 720, 520)
    await page.getByRole('button', { name: '播放模式：随机播放', exact: true }).click()
    await page.getByRole('button', { name: '播放模式：单曲循环', exact: true }).waitFor()
    assert.equal((await snapshot()).values.playMode, 'repeat-one')
    await page.evaluate(() => window.electronAPI.updatePreferences({ volume: 0.17 }))
    await page.waitForFunction(() => window.reviewAudioElements.some(audio => !audio.paused && audio.volume === 0.17))
    await resizeReviewWindow(application, page, 360, 132)
    await page.evaluate(() => window.electronAPI.updatePreferences({ volume: 0 }))
    await page.waitForFunction(() => window.reviewAudioElements.some(audio => !audio.paused && audio.volume === 0))
    await page.evaluate(() => { const audio = window.reviewAudioElements.find(audio => !audio.paused && audio.currentSrc); audio.currentTime = audio.duration - 0.2 })
    await page.waitForFunction(() => window.reviewAudioElements.some(audio => !audio.paused && audio.currentTime < 3)
      && navigator.mediaSession.metadata.title === 'First Light')
    await page.getByRole('button', { name: '暂停', exact: true }).click()
    assert.deepEqual(pageErrors, [])
    console.log('PREFERENCE_UI_AUDIO_PASS: actual HTML audio volume responds in lyrics/mini layouts and repeat-one replays the same track')
  } finally {
    if (application) await application.close()
    assert.equal(path.dirname(profile), root)
    assert.ok(path.basename(profile).startsWith('.ui-review-preference-ui-'))
    assert.equal(fs.lstatSync(profile).isSymbolicLink(), false)
    fs.rmSync(profile, { recursive: true, force: true, maxRetries: 5, retryDelay: 250 })
  }
}
run().catch(error => { console.error(error); process.exitCode = 1 })
