const { _electron: electron } = require('playwright')
const { resolve, join, sep } = require('node:path')
const fs = require('node:fs')
const assert = require('node:assert/strict')
const messages = require('../src/i18n/messages.json')
const root = resolve(__dirname, '..')
async function run() {
  const profile = fs.mkdtempSync(join(root, '.ui-review-locales-'))
  const env = { ...process.env, AURORA_UI_REVIEW_PROFILE: profile }; delete env.ELECTRON_RUN_AS_NODE
  let application
  try {
    application = await electron.launch({ executablePath: require('electron'), args: [join(__dirname, 'ui-review-main.cjs')], cwd: root, env })
    const page = await application.firstWindow(), errors = []
    page.on('pageerror', error => errors.push(error.message))
    await page.getByRole('heading', { name: '现在就听', exact: true }).waitFor()
    await application.evaluate(require('./demo-library.cjs'), root)
    await page.reload()
    await page.getByRole('heading', { name: '为你精选', exact: true }).waitFor()
    let previous = 'zh-CN'
    const text = (source, locale = previous) => messages[source]?.[locale] || source
    for (const locale of ['zh-CN', 'zh-TW', 'en', 'fr']) {
      const L = source => text(source, locale)
      const folder = join(root, 'docs', 'images', locale); fs.mkdirSync(folder, { recursive: true })
      const shot = async name => {
        await page.evaluate(() => new Promise(done => requestAnimationFrame(() => requestAnimationFrame(done))))
        await page.screenshot({ path: join(folder, name + '.png'), animations: 'disabled', scale: 'css' })
        assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false, locale + ': document overflow')
        assert.equal(await page.evaluate(() => { const main = document.querySelector('.main-content'); return main.scrollWidth > main.clientWidth }), false, locale + ': main overflow')
      }
      await page.getByRole('button', { name: text('设置'), exact: true }).click()
      await page.getByTestId('language-select').selectOption(locale)
      assert.equal(await page.locator('html').getAttribute('lang'), locale)
      await application.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows()[0].setContentSize(1320, 860))
      await shot('settings')
      await page.locator('.settings-signature').scrollIntoViewIfNeeded()
      assert.equal(await page.locator('.settings-signature').innerText(), 'by jinlaoshi')
      const position = await page.locator('.settings-signature').evaluate(el => { const box = el.getBoundingClientRect(), grid = el.parentElement.getBoundingClientRect(); return { right: grid.right - box.right, bottom: grid.bottom - box.bottom } })
      assert.ok(position.right < 4 && position.bottom < 4, 'signature is at bottom-right of settings')
      if (['en', 'fr'].includes(locale)) {
        const textOnly = await page.locator('.preferences-grid').evaluate(el => { const copy = el.cloneNode(true); copy.querySelectorAll('option').forEach(node => node.remove()); return copy.textContent })
        assert.ok(!/[\u3400-\u9fff]/.test(textOnly), locale + ': untranslated settings text: ' + textOnly.match(/[^.!?\n]*[\u3400-\u9fff][^.!?\n]*/g))
      }
      // Reload proves persistence, and resets scroll to the home view.
      await page.reload()
      await page.getByRole('heading', { name: L('为你精选'), exact: true }).waitFor()
      assert.equal(await page.locator('html').getAttribute('lang'), locale)
      await shot('home')
      if (['en', 'fr'].includes(locale)) assert.ok(!/[\u3400-\u9fff]/.test(await page.locator('.listen-page').innerText()), locale + ': untranslated recommendation text')
      await page.getByRole('button', { name: L('新建歌单'), exact: true }).first().click()
      const dialog = page.getByRole('dialog', { name: L('新建歌单'), exact: true })
      await dialog.getByRole('button', { name: L('智能歌单'), exact: true }).click()
      await dialog.getByLabel(L('此刻想听什么？'), { exact: true }).fill('40 minutes jazz')
      await dialog.getByRole('button', { name: L('生成预览'), exact: true }).click()
      await dialog.locator('.playlist-diagnostics').waitFor()
      if (['en', 'fr'].includes(locale)) {
        assert.ok(!/[\u3400-\u9fff]/.test(await dialog.innerText()), locale + ': untranslated playlist status')
        assert.ok(!/[\u3400-\u9fff]/.test(await dialog.getByLabel(L('描述'), { exact: false }).inputValue()), locale + ': untranslated default description')
      }
      await shot('playlist')
      await page.keyboard.press('Escape')
      await page.getByRole('navigation', { name: L('资料库'), exact: true }).getByRole('button', { name: L('歌曲'), exact: true }).click()
      await page.getByRole('textbox', { name: L('搜索音乐'), exact: true }).fill('First Light')
      await page.getByRole('slider', { name: L('音量'), exact: true }).fill('0')
      await page.locator('.song-row').filter({ has: page.locator('.song-identity b', { hasText: /^First Light$/ }) }).locator('.song-identity').click()
      await page.waitForFunction(() => navigator.mediaSession?.playbackState === 'playing')
      await page.getByRole('button', { name: L('暂停'), exact: true }).click()
      await page.getByRole('button', { name: L('正在播放与歌词'), exact: true }).click()
      await page.getByRole('dialog', { name: L('正在播放'), exact: true }).waitFor()
      await page.getByRole('button', { name: '时间停在旋律里', exact: true }).click()
      await shot('player')
      await page.keyboard.press('Escape')
      for (const width of [980, 1600]) {
        await application.evaluate(({ BrowserWindow }, width) => BrowserWindow.getAllWindows()[0].setContentSize(width, 860), width)
        await page.getByRole('button', { name: L('设置'), exact: true }).click()
        assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false, locale + ': responsive overflow')
      }
      assert.equal((await page.evaluate(() => window.electronAPI.getSongById('demo-0'))).data.title, 'First Light')
      assert.ok((await page.evaluate(() => window.electronAPI.getSongById('demo-0'))).data.customLyrics.includes('时间停在旋律里'))
      previous = locale
      console.log('LOCALE_PASS:', locale, 'switch, persistence, layout, local recommendations, lyrics and metadata integrity')
    }
    assert.deepEqual(errors, [])
  } finally {
    if (application) await application.close()
    if (profile.startsWith(root + sep + '.ui-review-locales-')) fs.rmSync(profile, { recursive: true, force: true, maxRetries: 5, retryDelay: 250 })
  }
}
run().catch(error => { console.error(error); process.exitCode = 1 })
