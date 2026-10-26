const { chromium } = require('playwright')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const http = require('node:http')
const { nativeFixture } = require('./mobile-smoke.cjs')

// Real built UI and Capacitor adapter; only the native process boundary is simulated.
// These cases catch mount-time reverse writes, full-status patches and stale language acknowledgements.
async function main() {
  const dist = path.resolve(__dirname, '../mobile/dist')
  const server = http.createServer((request, response) => {
    const target = path.resolve(dist, '.' + decodeURIComponent(request.url.split('?')[0]))
    if (!target.startsWith(dist + path.sep) && target !== dist) { response.writeHead(403).end(); return }
    const file = target === dist ? path.join(dist, 'index.html') : target
    response.setHeader('Content-Type', file.endsWith('.js') ? 'text/javascript' : file.endsWith('.css') ? 'text/css' : 'text/html')
    fs.createReadStream(file).on('error', () => response.writeHead(404).end()).pipe(response)
  })
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  const browser = await chromium.launch({ executablePath: process.env.AURORA_CHROME || 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe', headless: true })
  let failed = 0, passed = 0
  const run = async (name, config, test) => {
    const context = await browser.newContext({ viewport: { width: 393, height: 852 }, isMobile: true, hasTouch: true })
    const page = await context.newPage(), errors = []
    page.setDefaultTimeout(4000)
    page.on('pageerror', error => errors.push(error.message))
    page.on('dialog', dialog => void dialog.accept())
    await context.route('**/*', route => new URL(route.request().url()).hostname === '127.0.0.1' ? route.continue() : route.abort())
    if (config.preview) await page.addInitScript(language => localStorage.setItem('aurora-language', language), config.browserLanguage)
    else await page.addInitScript(nativeFixture, config)
    if (config.clock) await page.clock.install()
    try {
      await page.goto('http://127.0.0.1:' + server.address().port + '/')
      await test(page)
      assert.deepEqual(errors, [], 'No unhandled bridge rejection')
      passed++; console.log('PASS ' + name)
    } catch (error) { failed++; console.error('FAIL ' + name + '\n' + error.stack) }
    finally { await context.close() }
  }
  const ready = page => page.locator('.mini-player strong').filter({ hasText: 'Song 255' }).waitFor()
  const settings = page => page.locator('[data-page="settings"]').click()
  const choose = async (page, control, value) => {
    await control.click()
    await page.locator('.choice-sheet [role="option"][data-value=' + JSON.stringify(value) + ']').click()
    await page.locator('.choice-sheet').waitFor({ state: 'hidden' })
  }
  const language = (page, value) => choose(page, page.locator('[data-setting="language"]'), value)
  const frame = page => page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))))
  try {
    await run('native bootstrap blocks writers and stale browser consent', {
      holdReads: true, browserLanguage: 'zh-TW', browserAppearance: { autoLookup: true, mode: 'dark' },
      appearance: { language: 'fr', effectiveLanguage: 'fr', autoLookup: false, mode: 'light' },
    }, async page => {
      await page.waitForFunction(() => window.__fixture.readWaiters.length > 0)
      await frame(page)
      assert.equal(await page.locator('.mobile-app').count(), 0, 'Do not mount preference consumers before native hydration')
      assert.deepEqual(await page.evaluate(() => window.__fixture.calls.map(call => call.method)), ['appearance'])
      await page.evaluate(() => window.__fixture.releaseReads())
      await ready(page)
      assert.equal(await page.locator('html').getAttribute('lang'), 'fr')
      assert.equal(await page.locator('html').getAttribute('data-mode'), 'light')
      assert.equal(await page.evaluate(() => window.__fixture.calls.filter(call => call.method === 'setLanguage').length), 0)
      assert.equal(await page.evaluate(() => localStorage.getItem('aurora-language')), 'zh-TW', 'Native startup never mirrors authority to browser storage')
      assert.equal(await page.evaluate(() => window.__fixture.calls.filter(call => call.method === 'lookupAssets').length), 0)
    })
    await run('failed bootstrap retains data and can retry', { readFailures: 1 }, async page => {
      await page.locator('.preference-startup[role="alert"]').waitFor()
      assert.equal(await page.locator('.mobile-app').count(), 0)
      assert.equal(await page.evaluate(() => window.__fixture.calls.some(call => call.method.startsWith('set'))), false)
      await page.locator('.preference-startup button').click()
      await ready(page)
      assert.equal(await page.evaluate(() => window.__fixture.calls.filter(call => call.method === 'setLanguage').length), 0)
    })
    await run('malformed native consent refuses mount until corrected', { appearance: { autoLookup: 'true' } }, async page => {
      await page.locator('.preference-startup[role="alert"]').waitFor()
      assert.equal(await page.locator('.mobile-app').count(), 0)
      await page.evaluate(() => window.__fixture.changeAppearance({ autoLookup: false }))
      await page.locator('.preference-startup button').click()
      await ready(page)
    })
    await run('rapid edits send only durable changed keys and recover from failure', {}, async page => {
      await ready(page); await settings(page)
      await page.evaluate(() => { window.__fixture.appearanceDelay = 120 })
      await page.getByRole('button', { name: '深色', exact: true }).click()
      await choose(page, page.getByLabel('歌词字号', { exact: true }), '36')
      await page.getByLabel('界面过渡动画', { exact: true }).uncheck()
      await page.getByLabel('封面氛围背景', { exact: true }).uncheck()
      await page.waitForFunction(() => window.__fixture.getAppearance().showBackdrop === false)
      const patches = await page.evaluate(() => window.__fixture.calls.filter(call => call.method === 'setAppearance').map(call => call.options))
      assert.deepEqual(patches, [{ mode: 'dark' }, { lyricsSize: 36 }, { motion: false }, { showBackdrop: false }])
      await page.evaluate(() => { window.__fixture.appearanceDelay = 0; window.__fixture.failAppearance = true })
      await page.getByRole('button', { name: '明亮', exact: true }).click()
      await page.locator('.toast').waitFor()
      await page.waitForFunction(() => document.documentElement.dataset.mode === 'dark')
      await page.evaluate(() => { window.__fixture.failAppearance = false })
      await page.getByLabel('界面过渡动画', { exact: true }).check()
      await page.waitForFunction(() => window.__fixture.getAppearance().motion === true)
      assert.equal(await page.locator('html').getAttribute('data-mode'), 'dark')
    })
    await run('language waits for commit, failure keeps selection, later retry succeeds', { browserLanguage: 'zh-TW' }, async page => {
      await ready(page); await settings(page)
      await page.evaluate(() => { window.__fixture.holdSaves = true })
      await language(page, 'en')
      await page.waitForFunction(() => window.__fixture.saveWaiters.length === 1)
      assert.equal(await page.locator('html').getAttribute('lang'), 'zh-CN', 'Do not acknowledge an uncommitted language')
      assert.equal(await page.locator('[data-setting="language"]').innerText(), '简体中文')
      await page.evaluate(() => window.__fixture.releaseSaves())
      await page.waitForFunction(() => document.documentElement.lang === 'en')
      await page.evaluate(() => { window.__fixture.failLanguage = 'fr' })
      await language(page, 'fr')
      await page.locator('.toast').waitFor()
      assert.equal(await page.locator('html').getAttribute('lang'), 'en')
      assert.equal(await page.locator('[data-setting="language"]').innerText(), 'English')
      await page.evaluate(() => { window.__fixture.failLanguage = null })
      await language(page, 'fr')
      await page.waitForFunction(() => document.documentElement.lang === 'fr')
      assert.equal(await page.evaluate(() => localStorage.getItem('aurora-language')), 'zh-TW')
    })
    await run('latest failed language retains preceding committed language', {}, async page => {
      await ready(page); await settings(page)
      await page.evaluate(() => { window.__fixture.holdSaves = true; window.__fixture.failLanguage = 'fr' })
      await language(page, 'en'); await language(page, 'fr')
      await page.waitForFunction(() => window.__fixture.saveWaiters.length === 1)
      await page.evaluate(() => window.__fixture.releaseSaves())
      await page.locator('.toast').waitFor()
      await page.waitForFunction(() => document.documentElement.lang === 'en')
      assert.equal(await page.locator('[data-setting="language"]').innerText(), 'English')
    })
    await run('stale visibility read cannot override newer language', {}, async page => {
      await ready(page); await settings(page)
      await page.evaluate(() => { window.__fixture.holdReads = true; document.dispatchEvent(new Event('visibilitychange')) })
      await page.waitForFunction(() => window.__fixture.readWaiters.length > 0)
      await language(page, 'fr')
      await page.evaluate(() => window.__fixture.releaseReads())
      await page.waitForFunction(() => document.documentElement.lang === 'fr')
      assert.equal(await page.locator('[data-setting="language"]').innerText(), 'Français')
    })
    await run('follow-system remains raw empty language without a startup write', {
      appearance: { language: '', effectiveLanguage: 'fr' }, systemLanguage: 'fr', browserLanguage: 'en',
    }, async page => {
      await ready(page); await settings(page)
      assert.equal(await page.locator('html').getAttribute('lang'), 'fr')
      assert.equal(await page.evaluate(() => window.__fixture.calls.filter(call => call.method === 'setLanguage').length), 0)
      await language(page, 'en')
      await page.waitForFunction(() => document.documentElement.lang === 'en')
      await language(page, '')
      await page.waitForFunction(() => document.documentElement.lang === 'fr')
      assert.equal(await page.evaluate(() => window.__fixture.getAppearance().language), '')
    })
    await run('startup timeout retries without cancellation or late overwrite', { holdReads: true, clock: true }, async page => {
      await page.waitForFunction(() => window.__fixture.readWaiters.length === 1)
      await page.clock.fastForward(15_000)
      await page.locator('.preference-startup[role="alert"]').waitFor()
      assert.equal(await page.locator('.mobile-app').count(), 0)
      await page.evaluate(() => {
        window.__fixture.changeAppearance({ language: 'fr', effectiveLanguage: 'fr' })
        window.__fixture.holdReads = false // The original read stays unresolved, only the new attempt proceeds.
      })
      await page.locator('.preference-startup button').click()
      await ready(page)
      await page.evaluate(() => window.__fixture.readWaiters.splice(0).forEach(resolve => resolve()))
      await frame(page)
      assert.equal(await page.locator('html').getAttribute('lang'), 'fr')
      assert.equal(await page.evaluate(() => window.__fixture.calls.filter(call => call.method === 'setLanguage').length), 0)
    })
    await run('network consent does not turn on until the native save succeeds', {}, async page => {
      await ready(page); await settings(page)
      await page.evaluate(() => { window.__fixture.holdSaves = true; window.__fixture.failAppearance = true })
      await page.getByLabel('自动查找歌词与封面', { exact: true }).click()
      await page.waitForFunction(() => window.__fixture.saveWaiters.length === 1)
      assert.equal(await page.getByLabel('自动查找歌词与封面', { exact: true }).isChecked(), false)
      assert.equal(await page.evaluate(() => window.__fixture.calls.some(call => call.method === 'lookupAssets')), false)
      await page.evaluate(() => window.__fixture.releaseSaves())
      await page.locator('.toast').waitFor()
      assert.equal(await page.getByLabel('自动查找歌词与封面', { exact: true }).isChecked(), false)
    })
    await run('browser preview keeps its own language without a native bridge', { preview: true, browserLanguage: 'fr' }, async page => {
      await page.locator('.mobile-app').waitFor()
      assert.equal(await page.locator('html').getAttribute('lang'), 'fr')
      await settings(page); await language(page, 'en')
      await page.waitForFunction(() => document.documentElement.lang === 'en')
      assert.equal(await page.evaluate(() => localStorage.getItem('aurora-language')), 'en')
    })
    console.log(`Mobile preference UI: ${passed} passed / ${failed} failed; simulated native boundary, not native/device acceptance.`)
    if (failed) process.exitCode = 1
  } finally { await browser.close(); await new Promise(resolve => server.close(resolve)) }
}
main().catch(error => { console.error(error); process.exitCode = 1 })
