const { chromium } = require('playwright')
const assert = require('node:assert/strict')
const path = require('path')
const fs = require('fs')
const http = require('http')

function nativeFixture() {
  const songs = Array.from({ length: 360 }, (_, i) => ({
    id: 'song-' + String(i).padStart(3, '0'), title: 'Song ' + String(i).padStart(3, '0'),
    artist: 'Artist ' + (i % 8), album: 'Album ' + (i % 20), year: 2024, genre: i % 2 ? 'Pop' : 'Rock',
    duration: 240, filePath: 'https://example.com/music/' + i + '.m4a', format: 'm4a',
    coverPath: '/cover.svg', customArtistImage: null, customLyrics: '[00:00.00]A quiet beginning\n[00:15.00]The music carries us home\n[00:40.00]Another line',
    sourceType: 'webdav', isFavorite: i % 15 === 0 ? 1 : 0, playCount: i % 5,
    createdAt: '2026-10-01', remoteSize: 5000000, codec: 'audio/mp4a-latm', bitrate: 256000, sampleRate: 48000, channels: 2,
    replayGainDb: i === 0 ? -6.25 : i === 2 ? 0 : null, replayPeak: i === 0 ? 1.2345 : null,
    offline: i % 3 === 0, downloading: false, downloadProgress: 0,
  }))
  const listeners = new Map()
  const calls = []
  let callbackId = 0
  let status = { songId: 'song-255', playing: false, playWhenReady: false, buffering: false, position: 18, duration: 240, error: null, speed: 1, revision: 1 }
  let appearance = { mode: 'system', accent: '#b94b5c', dynamic: true, dynamicAvailable: true, motion: true, lowPowerVisuals: true, autoLookup: false, lyricsSize: 18, showBackdrop: true, dark: false, systemReducedMotion: false, powerSave: false, batteryOptimized: true, backgroundRestricted: false }
  let wifiOnly = true
  let playbackOptions = { repeatMode: 'off', shuffle: false, rememberPlayback: true, speed: 1, volume: 1, normalizeVolume: false, prefetch: false, crossfadeSeconds: 0, sleepRemainingSeconds: 0 }
  let streamBytes = 8 * 1024 * 1024
  let lists = [], playlistId = 0, apiProviders = [], aiEpoch = 0
  let assetProviders = [], itunesEnabled = false, assetEpoch = 0, assetSequence = 0
  const assetCandidates = new Map()
  const tasks = Array.from({ length: 65 }, (_, i) => ({ songId: songs[i].id, title: songs[i].title, artist: songs[i].artist,
    state: i === 0 ? 'running' : i === 1 ? 'failed' : 'paused', receivedBytes: 1000000, totalBytes: 5000000, error: i === 1 ? 'Fixture failure' : '' }))
  const emit = (event, value) => { for (const listener of listeners.values()) if (listener.event === event) listener.callback(value) }
  const filtered = options => songs.filter(song => (!options.offlineOnly || song.offline) && (!options.favoritesOnly || song.isFavorite)
    && (options.artist === undefined || song.artist === options.artist) && (options.album === undefined || song.album === options.album)
    && (!options.genreTerms?.length || options.genreTerms.some(term => song.genre.toLowerCase().includes(term.toLowerCase())))
    && (song.title + ' ' + song.artist + ' ' + song.album).toLowerCase().includes((options.query || '').toLowerCase()))
  const palette = () => ({
    ...appearance, dark: appearance.mode === 'dark',
    palette: appearance.mode === 'dark'
      ? { background: '#17171c', surface: '#24242b', text: '#f0edf0', muted: '#c1bac1', border: '#49434b', accent: '#e8a2af', container: '#473038', onAccent: '#29141b' }
      : { background: '#f8f7f5', surface: '#ffffff', text: '#252329', muted: '#746d75', border: '#ded8df', accent: appearance.accent, container: '#f4e4e8', onAccent: '#ffffff' },
  })
  const methods = ['connection', 'settings', 'setWifiOnly', 'appearance', 'setAppearance', 'playback', 'library', 'overview', 'song', 'play', 'toggle', 'next', 'previous', 'seek', 'favorite', 'download', 'downloadMany', 'removeDownload', 'browse', 'scan', 'connect', 'saveLyrics', 'lookupAssets', 'launchState', 'pinWidget', 'openSystemSettings', 'removeListener']
  window.androidBridge = {}
  methods.push('setLanguage', 'playlists', 'savePlaylist', 'deletePlaylist', 'playlistSong', 'playlistSongs')
  methods.push('aiProviders', 'saveAiProviders', 'requestAi', 'testAiProvider', 'cancelAi', 'catalogPage', 'collections')
  methods.push('playbackOptions', 'setPlaybackOptions', 'audioOutputs')
  methods.push('downloadTasks', 'pauseDownload', 'cancelDownload', 'clearStreamCache')
  methods.push('assetProviders', 'saveAssetProviders', 'searchAssets', 'previewAsset', 'applyAsset', 'clearAsset', 'cancelAssets')
  window.Capacitor = {
    PluginHeaders: [{ name: 'AuroraMusic', methods: [...methods.map(name => ({ name, rtype: 'promise' })), { name: 'addListener', rtype: 'callback' }] }],
    convertFileSrc: value => value,
    nativeCallback: (_plugin, method, options, callback) => {
      const id = String(++callbackId)
      if (method === 'addListener') listeners.set(id, { event: options.eventName, callback })
      return Promise.resolve(id)
    },
    nativePromise: async (_plugin, method, options = {}) => {
      calls.push({ method, options })
      if (method === 'assetProviders') return { providers: assetProviders, itunesEnabled }
      if (method === 'saveAssetProviders') {
        assetProviders = options.providers.map(({ apiKey, ...item }, index) => ({ ...item, priority: index + 1, configured: Boolean(apiKey || item.configured) }))
        itunesEnabled = options.itunesEnabled; return { providers: assetProviders, itunesEnabled }
      }
      if (method === 'cancelAssets') { assetEpoch++; assetCandidates.clear(); return {} }
      if (method === 'searchAssets') {
        const epoch = assetEpoch
        if (window.__fixture.assetDelay) await new Promise(resolve => setTimeout(resolve, window.__fixture.assetDelay))
        if (epoch !== assetEpoch) throw new Error('ASSET_CANCELLED')
        const candidate = { id: 'candidate-' + (++assetSequence), ...options.query, source: 'Fixture resource API', synced: options.kind === 'lyrics', ...(options.kind === 'lyrics' ? { text: '[00:00]Fixture selected lyric' } : {}) }
        assetCandidates.set(candidate.id, { candidate, songId: options.songId, kind: options.kind })
        return { candidates: [candidate] }
      }
      if (method === 'previewAsset' || method === 'applyAsset') {
        const item = assetCandidates.get(options.candidateId)
        if (!item || item.songId !== options.songId || item.kind !== options.kind) throw new Error('ASSET_EXPIRED')
        if (method === 'previewAsset') return { candidate: { ...item.candidate, ...(options.kind === 'lyrics' ? {} : { imagePath: options.kind === 'cover' ? '/selected-cover.svg' : '/background.svg' }) } }
        const song = songs.find(song => song.id === options.songId)
        if (options.kind === 'lyrics') song.customLyrics = item.candidate.text
        else if (options.kind === 'cover') song.coverPath = '/selected-cover.svg'
        else song.customArtistImage = '/background.svg'
        emit('libraryChanged', {}); return { song: { ...song } }
      }
      if (method === 'clearAsset') {
        const song = songs.find(song => song.id === options.songId)
        if (options.kind === 'cover') song.coverPath = '/cover.svg'; else song.customArtistImage = null
        return { song: { ...song } }
      }
      if (method === 'playbackOptions') return { ...playbackOptions }
      if (method === 'setPlaybackOptions') {
        playbackOptions = { ...playbackOptions, ...options, sleepRemainingSeconds: options.sleepMinutes === undefined ? playbackOptions.sleepRemainingSeconds : options.sleepMinutes * 60 }
        status = { ...status, speed: playbackOptions.speed }; emit('playbackChanged', { ...status }); return { ...playbackOptions }
      }
      if (method === 'audioOutputs') return { devices: [{ name: 'Fixture headphones', kind: 'bluetooth' }], systemVolume: 5, systemVolumeMax: 15 }
      if (method === 'collections') {
        const groups = new Map()
        for (const song of songs) {
          if (options.offlineOnly && !song.offline) continue
          const name = options.kind === 'albums' ? song.album : song.artist
          if (!name.toLowerCase().includes((options.query || '').toLowerCase())) continue
          const key = options.kind === 'albums' ? JSON.stringify([name, song.artist]) : name
          if (!groups.has(key)) groups.set(key, { name, artist: song.artist, count: 0, coverPath: '/cover.svg' })
          groups.get(key).count++
        }
        const rows = [...groups.values()].sort((a, b) => a.name.localeCompare(b.name)), offset = options.offset || 0, limit = options.limit || 60
        return { items: rows.slice(offset, offset + limit), total: rows.length, offset, hasMore: offset + limit < rows.length }
      }
      if (method === 'aiProviders') return { providers: apiProviders }
      if (method === 'saveAiProviders') { apiProviders = options.providers.map(({ apiKey, ...item }, index) => ({ ...item, priority: index + 1, configured: Boolean(apiKey || item.configured) })); return { providers: apiProviders } }
      if (method === 'testAiProvider') return { providerName: apiProviders.find(provider => provider.id === options.id).name }
      if (method === 'catalogPage') {
        const rows = filtered(options), cursor = options.cursor || 0
        return { songs: rows.slice(cursor, cursor + 200), cursor: cursor + Math.min(200, rows.length - cursor), ceiling: rows.length, total: rows.length, hasMore: cursor + 200 < rows.length }
      }
      if (method === 'cancelAi') { aiEpoch++; return {} }
      if (method === 'requestAi') {
        const epoch = aiEpoch
        if (window.__fixture.aiDelay) await new Promise(resolve => setTimeout(resolve, window.__fixture.aiDelay))
        if (epoch !== aiEpoch) throw new Error('Fixture request cancelled')
        return { value: options.stage === 'plan' ? { scene: 'any', keywords: [] } : { name: 'Fixture AI playlist', description: 'From the fixture catalog', songIds: JSON.parse(options.user).catalog.slice(0, 2).map(song => song.id) }, providerName: 'Fixture API', attempts: [] }
      }
      if (method === 'library') {
        const rows = filtered(options), offset = options.offset || 0, limit = options.limit || 60
        await new Promise(resolve => setTimeout(resolve, 20))
        return { songs: rows.slice(offset, offset + limit).map(song => ({ ...song, customLyrics: null })), total: rows.length, libraryTotal: songs.length, offlineTotal: songs.filter(song => song.offline).length, offset, hasMore: offset + limit < rows.length }
      }
      if (method === 'overview') return { songs: songs.map(song => ({ ...song, customLyrics: null })), history: [], libraryTotal: songs.length, offlineTotal: songs.filter(song => song.offline).length }
      if (method === 'song') return { song: songs.find(song => song.id === options.songId) || null }
      if (method === 'playback') return { ...status }
      if (method === 'connection') return { url: '', username: '' }
      if (method === 'settings') return { wifiOnly, storageBytes: 40000000, partialBytes: tasks.reduce((sum, task) => sum + task.receivedBytes, 0), freeBytes: 40000000000, streamBytes, streamLimitBytes: 128 * 1024 * 1024, artworkBytes: 1024 * 1024 }
      if (method === 'clearStreamCache') { const before = streamBytes; streamBytes = 1024 * 1024; return { freedBytes: before - streamBytes, retainedBytes: streamBytes } }
      if (method === 'downloadTasks') { const offset = options.offset || 0, limit = Math.min(60, options.limit || 60); return { items: tasks.slice(offset, offset + limit).map(task => ({ ...task })), total: tasks.length, active: tasks.filter(task => ['pending', 'running'].includes(task.state)).length, offset, hasMore: offset + limit < tasks.length } }
      if (method === 'pauseDownload') { tasks.find(task => task.songId === options.songId).state = 'paused'; return {} }
      if (method === 'download') { const task = tasks.find(task => task.songId === options.songId); if (task) { task.state = 'pending'; task.error = '' } return {} }
      if (method === 'cancelDownload') { const index = tasks.findIndex(task => task.songId === options.songId), task = tasks.splice(index, 1)[0]; return { freedBytes: task?.receivedBytes || 0 } }
      if (method === 'setWifiOnly') { wifiOnly = options.enabled; return {} }
      if (method === 'appearance') return palette()
      if (method === 'setAppearance') {
        if (window.__fixture.appearanceDelay) await new Promise(resolve => setTimeout(resolve, window.__fixture.appearanceDelay))
        appearance = { ...appearance, ...options }; return palette()
      }
      if (method === 'launchState') return { openPlayer: false }
      if (method === 'play') {
        window.__fixture.queue = options.playlistId ? lists.find(list => list.id === options.playlistId).ids : options.libraryQuery ? filtered(options.libraryQuery).map(song => song.id) : options.songIds
        status = { ...status, songId: options.startId, playing: true, playWhenReady: true, position: 0, revision: status.revision + 1 }
        emit('playbackChanged', { ...status }); return {}
      }
      if (method === 'toggle') { const play = status.error ? true : !status.playWhenReady; status = { ...status, playing: play, playWhenReady: play, error: null, errorCode: null }; emit('playbackChanged', { ...status }); return {} }
      if (method === 'seek') { status = { ...status, position: options.position }; emit('playbackChanged', { ...status }); return {} }
      if (method === 'favorite') { const song = songs.find(song => song.id === options.songId); song.isFavorite = options.favorite ? 1 : 0; emit('libraryChanged', {}); return {} }
      if (method === 'saveLyrics') { if (window.__fixture.lyricsFail) throw new Error('Fixture lyric save failed'); songs.find(song => song.id === options.songId).customLyrics = options.text; emit('libraryChanged', {}); return {} }
      if (method === 'lookupAssets') return { song: songs.find(song => song.id === options.songId) }
      if (method === 'playlists') return { playlists: lists.map(list => ({ ...list, count: list.ids.length })) }
      if (method === 'savePlaylist') {
        const id = options.id || 'playlist-' + (++playlistId), old = lists.find(list => list.id === id)
        const next = { id, name: options.name, description: options.description || '', smartRules: options.rules ? JSON.stringify(options.rules) : null, ids: old?.ids || options.songIds || [] }
        lists = [...lists.filter(list => list.id !== id), next]; return { id }
      }
      if (method === 'playlistSong') { const list = lists.find(list => list.id === options.id); list.ids = options.remove ? list.ids.filter(id => id !== options.songId) : [...new Set([...list.ids, options.songId])]; return {} }
      if (method === 'deletePlaylist') { lists = lists.filter(list => list.id !== options.id); return {} }
      if (method === 'playlistSongs') { const list = lists.find(list => list.id === options.id), ids = list.smartRules ? songs.filter(song => !JSON.parse(list.smartRules).favoritesOnly || song.isFavorite).map(song => song.id) : list.ids; return { songs: ids.map(id => songs.find(song => song.id === id)), total: ids.length, offset: 0, hasMore: false } }
      if (method === 'removeListener') { listeners.delete(options.callbackId); return {} }
      return {}
    },
  }
  window.__fixture = { songs, calls, emit, queue: [], setPowerSave(value) { appearance = { ...appearance, powerSave: value } }, setPlaybackError(error, errorCode = null) {
    status = { ...status, error, errorCode, playing: !error && status.playWhenReady, buffering: false }
    emit('playbackChanged', { ...status })
  } }
}

async function main() {
  const dist = path.join(__dirname, '..', 'mobile', 'dist')
  const server = http.createServer((request, response) => {
    if (/^\/(cover|selected-cover|background)\.svg/.test(request.url)) {
      response.setHeader('Content-Type', 'image/svg+xml')
      response.end('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 300 300"><rect width="300" height="300" fill="#709898"/><circle cx="150" cy="150" r="82" fill="#efe9df"/><circle cx="150" cy="150" r="24" fill="#709898"/></svg>')
      return
    }
    const target = path.resolve(dist, '.' + decodeURIComponent(request.url.split('?')[0]))
    if (!target.startsWith(dist + path.sep) && target !== dist) { response.writeHead(403).end(); return }
    const file = target === dist ? path.join(dist, 'index.html') : target
    response.setHeader('Content-Type', file.endsWith('.js') ? 'text/javascript' : file.endsWith('.css') ? 'text/css' : 'text/html')
    fs.createReadStream(file).on('error', () => response.writeHead(404).end()).pipe(response)
  })
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  const browser = await chromium.launch({ executablePath: process.env.AURORA_CHROME || 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe', headless: true })
  const output = path.join(__dirname, '..', 'test-results')
  fs.mkdirSync(output, { recursive: true })
  const guideImages = path.join(__dirname, '..', 'docs', 'images', 'android')
  fs.mkdirSync(guideImages, { recursive: true })
  try {
    const page = await browser.newPage({ viewport: { width: 393, height: 852 }, isMobile: true, hasTouch: true })
    await page.addInitScript(nativeFixture)
    let dismissNextDialog = false
    page.on('dialog', dialog => { if (dismissNextDialog) { dismissNextDialog = false; void dialog.dismiss() } else void dialog.accept() })
    const errors = []
    page.on('pageerror', error => errors.push(error.message))
    await page.goto('http://127.0.0.1:' + server.address().port + '/')
    await page.locator('.mini-player strong').filter({ hasText: 'Song 255' }).waitFor()
    const nav = item => page.locator('[data-page="' + item + '"]')
    const choose = async (control, value) => {
      await control.click()
      const sheet = page.locator('.choice-sheet')
      await sheet.getByRole('option').and(sheet.locator('[data-value=' + JSON.stringify(String(value)) + ']')).click()
      await sheet.waitFor({ state: 'hidden' })
    }
    const playerAction = async (scope, name) => {
      await scope.locator('.more-actions').click()
      await page.locator('.player-action-sheet').getByRole('button', { name, exact: true }).click()
      await page.locator('.player-action-sheet').waitFor({ state: 'hidden' })
    }
    for (const [width, height] of [[360, 800], [393, 852], [412, 915], [600, 900], [840, 1200], [1200, 840], [1600, 1000]]) {
      await page.setViewportSize({ width, height })
      for (const item of ['listen', 'library', 'cloud', 'settings']) {
        await nav(item).click()
        await page.locator('main h1').waitFor()
        assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false, 'Horizontal overflow at ' + width + ' on ' + item)
      }
      await nav('listen').click()
    }
    await page.screenshot({ path: path.join(output, 'mobile-tablet-light.png'), fullPage: true })
    await page.screenshot({ path: path.join(guideImages, 'tablet-listen.png') })
    await page.setViewportSize({ width: 393, height: 852 })
    await page.screenshot({ path: path.join(guideImages, 'phone-listen.png') })
    await page.getByRole('button', { name: '查看全部', exact: true }).click()
    await page.getByRole('button', { name: '播放 · Song 345', exact: true }).click()
    await page.waitForFunction(() => window.__fixture.queue.length === 24)
    assert.ok(await page.evaluate(() => window.__fixture.queue.every(id => window.__fixture.songs.find(song => song.id === id).isFavorite)))
    await page.getByRole('button', { name: '取消筛选', exact: true }).click()
    await nav('listen').click()
    await page.locator('.taste-card .filter-chips button').filter({ hasText: '流行' }).click()
    await page.getByRole('button', { name: '播放 · Song 001', exact: true }).click()
    await page.waitForFunction(() => window.__fixture.queue.length === 180)
    assert.ok(await page.evaluate(() => window.__fixture.queue.every(id => window.__fixture.songs.find(song => song.id === id).genre === 'Pop')))
    await page.getByRole('button', { name: '取消筛选', exact: true }).click()
    await nav('library').click()
    await page.locator('.track-list .track').nth(59).waitFor()
    assert.equal(await page.locator('main .track').count(), 60)
    await page.getByRole('button', { name: '加载下一页' }).click()
    await page.locator('main .track').nth(119).waitFor()
    assert.equal(await page.locator('main .track').count(), 120)
    await page.getByRole('searchbox').fill('Song 359')
    await page.getByRole('button', { name: '播放 · Song 359', exact: true }).waitFor()
    assert.equal(await page.locator('main .track').count(), 1, 'Full-library search must find a song beyond loaded pages')
    await page.getByRole('searchbox').fill('')
    await page.locator('main .track').nth(59).waitFor()
    await page.getByRole('button', { name: '播放 · Song 000', exact: true }).click()
    await page.waitForFunction(() => window.__fixture.queue.length === 360)
    await page.getByRole('button', { name: '专辑', exact: true }).click()
    await page.getByRole('searchbox', { name: '专辑', exact: true }).fill('Album 19')
    await page.locator('.collection-card').filter({ hasText: 'Artist 7' }).click()
    await page.getByRole('button', { name: '播放 · Song 359', exact: true }).click()
    await page.waitForFunction(() => window.__fixture.queue.length === 9)
    assert.ok(await page.evaluate(() => window.__fixture.queue.every(id => { const song = window.__fixture.songs.find(song => song.id === id); return song.album === 'Album 19' && song.artist === 'Artist 7' })))
    await page.getByRole('button', { name: '艺术家', exact: true }).click()
    await page.locator('.collection-card').filter({ hasText: 'Artist 7' }).click()
    await page.getByRole('button', { name: '播放 · Song 359', exact: true }).click()
    await page.waitForFunction(() => window.__fixture.queue.length === 45)
    await page.getByRole('button', { name: '歌曲', exact: true }).click()
    await page.getByRole('button', { name: '播放 · Song 000', exact: true }).click()
    await page.locator('.mini-track').click()
    const player = page.getByRole('dialog', { name: '正在播放', exact: true })
    await player.waitFor()
    await player.evaluate(element => Promise.all(element.getAnimations().map(animation => animation.finished.catch(() => {}))))
    const assertTransportCentered = async (scope, label) => {
      const controls = await scope.locator('.transport > button').evaluateAll(buttons => buttons.map(button => {
        const icon = button.querySelector('svg').getBoundingClientRect(), bounds = button.getBoundingClientRect()
        return { name: button.getAttribute('aria-label'), x: icon.x + icon.width / 2 - bounds.x - bounds.width / 2,
          y: icon.y + icon.height / 2 - bounds.y - bounds.height / 2, centerY: bounds.y + bounds.height / 2,
          width: bounds.width, height: bounds.height }
      }))
      assert.equal(controls.length, 3, 'All three transport controls must be present: ' + label)
      for (const control of controls) assert.ok(Math.abs(control.x) <= .5 && Math.abs(control.y) <= .5,
        'Transport icon must be centered in its button: ' + label + ' ' + JSON.stringify(control))
      assert.ok(controls.every(control => Math.abs(control.centerY - controls[1].centerY) <= .5),
        'Transport controls must share a horizontal centerline: ' + label)
      assert.ok(Math.abs(controls[1].width - controls[1].height) <= .5, 'Play/pause button must remain circular: ' + label)
    }
    await assertTransportCentered(player, 'phone pause')
    await player.getByRole('button', { name: '暂停', exact: true }).click()
    await player.getByRole('button', { name: '播放', exact: true }).waitFor()
    await assertTransportCentered(player, 'phone play')
    await player.getByRole('button', { name: '播放', exact: true }).click()
    await player.getByRole('button', { name: '暂停', exact: true }).waitFor()
    const playerBounds = await player.boundingBox()
    const phoneViewport = page.viewportSize()
    assert.ok(playerBounds && Math.abs(playerBounds.y) <= 1 && playerBounds.height >= phoneViewport.height - 1,
      'Phone player must fill the viewport instead of exposing the underlying app header: ' + JSON.stringify(playerBounds))
    const coverBounds = await player.locator('.player-art').boundingBox()
    const lyricBounds = await player.locator('.lyric-panel').boundingBox()
    assert.ok(coverBounds.height <= phoneViewport.height / 4, 'Artwork must not dominate a phone player')
    assert.ok(lyricBounds.height >= phoneViewport.height * .3, 'Lyrics must be a primary region, not a two-line strip')
    assert.equal(await player.getByRole('button', { name: '查找歌词与封面', exact: true }).count(), 0,
      'Secondary actions must stay tucked away until More is opened')
    await player.getByRole('button', { name: '更多操作', exact: true }).click()
    await page.getByRole('button', { name: '歌曲详情', exact: true }).waitFor()
    await page.evaluate(() => history.back())
    await page.getByRole('dialog', { name: '更多操作', exact: true }).waitFor({ state: 'hidden' })
    assert.equal(await player.count(), 1, 'Back must close More before closing the full-screen player')
    await player.getByRole('button', { name: '歌词', exact: true }).click()
    await player.getByRole('button', { name: 'The music carries us home', exact: true }).click()
    await page.waitForFunction(() => window.__fixture.calls.some(call => call.method === 'seek' && call.options.position === 15))
    await page.screenshot({ path: path.join(output, 'mobile-player-light.png') })
    await page.screenshot({ path: path.join(guideImages, 'phone-player.png') })
    for (const [width, height] of [[360, 740], [412, 915], [852, 393], [840, 1180], [1200, 840]]) {
      await page.setViewportSize({ width, height })
      assert.equal(await player.evaluate(dialog => dialog.scrollWidth > dialog.clientWidth), false,
        'Full player must not overflow horizontally at ' + width + 'x' + height)
      await player.getByRole('button', { name: '下一首', exact: true }).scrollIntoViewIfNeeded()
      const nextBounds = await player.getByRole('button', { name: '下一首', exact: true }).boundingBox()
      assert.ok(nextBounds.y >= 0 && nextBounds.y + nextBounds.height <= height,
        'Transport must remain reachable at ' + width + 'x' + height)
      const closeBounds = await player.getByRole('button', { name: '关闭', exact: true }).boundingBox()
      assert.ok(closeBounds.y >= 0 && closeBounds.y + closeBounds.height <= height,
        'Full player close control must remain reachable at ' + width + 'x' + height)
      await assertTransportCentered(player, width + 'x' + height)
    }
    await page.setViewportSize({ width: 393, height: 852 })
    await playerAction(player, '歌曲详情')
    const detail = page.getByRole('dialog', { name: '歌曲详情', exact: true })
    await detail.getByText('256 kbps', { exact: true }).waitFor()
    await detail.getByText('-6.25 dB', { exact: true }).waitFor()
    await detail.getByText('1.234', { exact: true }).waitFor()
    const beforeResources = await page.evaluate(() => ({ ...window.__fixture.songs[0] }))
    const openFinder = async () => {
      await detail.getByRole('button', { name: '搜索预览与手动选择', exact: true }).click()
      const finder = page.getByRole('dialog', { name: '搜索预览与手动选择', exact: true })
      await finder.getByRole('button', { name: '搜索资源', exact: true }).waitFor()
      return finder
    }
    let finder = await openFinder()
    await finder.getByLabel('歌名', { exact: true }).fill('Edited search title')
    await finder.getByRole('button', { name: '搜索资源', exact: true }).click()
    await finder.locator('.asset-result').click()
    await finder.getByRole('heading', { name: '预览', exact: true }).waitFor()
    assert.equal(await page.evaluate(() => window.__fixture.songs[0].customLyrics), beforeResources.customLyrics, 'Preview cannot replace lyrics')
    assert.equal(await finder.locator('pre').innerText(), '[00:00]Fixture selected lyric')
    await finder.getByRole('button', { name: '使用这个结果', exact: true }).click()
    await finder.waitFor({ state: 'hidden' })
    assert.equal(await page.evaluate(() => window.__fixture.songs[0].customLyrics), '[00:00]Fixture selected lyric')
    assert.equal(await page.evaluate(() => window.__fixture.songs[0].coverPath), beforeResources.coverPath)
    finder = await openFinder()
    await finder.getByRole('button', { name: '封面', exact: true }).click()
    await finder.getByRole('button', { name: '搜索资源', exact: true }).click()
    await finder.locator('.asset-result').click()
    await finder.locator('.asset-preview img').waitFor()
    for (const width of [360, 600, 1200]) {
      await page.setViewportSize({ width, height: 852 })
      assert.equal(await finder.evaluate(dialog => dialog.scrollWidth > dialog.clientWidth), false, 'Asset preview must fit at ' + width)
    }
    await page.setViewportSize({ width: 393, height: 852 })
    await finder.evaluate(dialog => { dialog.scrollTop = Math.max(0, dialog.querySelector('.asset-preview').offsetTop - 70) })
    const finderHeader = await finder.locator('.sheet-header').boundingBox()
    assert.ok(finderHeader.y >= 0 && finderHeader.y + finderHeader.height <= 852, 'Resource dialog must keep its close control visible while scrolling')
    await page.screenshot({ path: path.join(guideImages, 'asset-preview.png') })
    await finder.getByRole('button', { name: '使用这个结果', exact: true }).click()
    await finder.waitFor({ state: 'hidden' })
    assert.equal(await page.evaluate(() => window.__fixture.songs[0].coverPath), '/selected-cover.svg')
    finder = await openFinder()
    await finder.getByRole('button', { name: '背景', exact: true }).click()
    await finder.getByRole('button', { name: '搜索资源', exact: true }).click()
    await finder.locator('.asset-result').click()
    await finder.getByRole('button', { name: '使用这个结果', exact: true }).click()
    await finder.waitFor({ state: 'hidden' })
    assert.equal(await page.evaluate(() => window.__fixture.songs[0].customArtistImage), '/background.svg')
    assert.equal(await page.evaluate(() => window.__fixture.songs[0].customLyrics), '[00:00]Fixture selected lyric')
    finder = await openFinder()
    await finder.getByRole('button', { name: '背景', exact: true }).click()
    await finder.getByRole('button', { name: '恢复默认图片', exact: true }).click()
    await finder.waitFor({ state: 'hidden' })
    assert.equal(await page.evaluate(() => window.__fixture.songs[0].customArtistImage), null)
    await page.evaluate(() => { window.__fixture.assetDelay = 800 })
    finder = await openFinder()
    await finder.getByRole('button', { name: '搜索资源', exact: true }).click()
    await page.evaluate(() => window.__fixture.emit('appStateChanged', { active: false }))
    await page.waitForTimeout(1000)
    assert.equal(await finder.locator('.asset-result').count(), 0, 'Background cancellation must discard a late resource result')
    await page.evaluate(() => window.__fixture.emit('appStateChanged', { active: true }))
    await finder.getByRole('button', { name: '搜索资源', exact: true }).click()
    await finder.getByRole('button', { name: '关闭', exact: true }).click()
    await page.waitForTimeout(1000)
    assert.equal(await finder.count(), 0, 'Closing must discard delayed resource results')
    await page.evaluate(() => { window.__fixture.assetDelay = 0 })
    await detail.getByRole('button', { name: '编辑 / 粘贴 LRC 歌词', exact: true }).click()
    const lyricDraft = detail.getByRole('textbox', { name: '编辑 / 粘贴 LRC 歌词', exact: true })
    assert.equal(await lyricDraft.inputValue(), '[00:00]Fixture selected lyric', 'Editor must use the latest applied lyric, not stale initial detail data')
    const manualLyric = '[00:00]Saved manual lyric\n[00:15]The music carries us home'
    await lyricDraft.fill(manualLyric)
    await page.evaluate(() => { window.__fixture.lyricsFail = true })
    await detail.getByRole('button', { name: '保存', exact: true }).click()
    await page.locator('.toast').filter({ hasText: 'Fixture lyric save failed' }).waitFor()
    assert.equal(await lyricDraft.inputValue(), manualLyric, 'Failed save must keep the draft open and unchanged')
    await page.evaluate(() => { window.__fixture.lyricsFail = false })
    await detail.getByRole('button', { name: '保存', exact: true }).click()
    await lyricDraft.waitFor({ state: 'hidden' })
    assert.equal(await page.evaluate(() => window.__fixture.songs[0].customLyrics), manualLyric)
    await detail.getByRole('button', { name: '关闭', exact: true }).click()
    await player.waitFor()
    await playerAction(player, '播放设置')
    const playbackOptions = page.getByRole('dialog', { name: '播放设置', exact: true })
    const normalization = playbackOptions.getByLabel('标签音量一致化（只衰减）', { exact: true })
    assert.equal(await normalization.isChecked(), false, 'ReplayGain defaults off')
    await normalization.check()
    const prefetchNext = playbackOptions.getByLabel('提前缓存下一首', { exact: true })
    assert.equal(await prefetchNext.isChecked(), false, 'Prefetch defaults off')
    await prefetchNext.check()
    const crossfade = playbackOptions.getByLabel('交叉淡化', { exact: true })
    await crossfade.click()
    const crossfadeChoices = page.getByRole('listbox', { name: '交叉淡化', exact: true })
    await crossfadeChoices.waitFor({ timeout: 3000 })
    assert.deepEqual(await crossfadeChoices.getByRole('option').allTextContents(), ['关闭', '1 秒', '2 秒', '3 秒'],
      'Choices must be rendered inside the app, not delegated to an OEM select popup')
    await crossfadeChoices.getByRole('option', { name: '3 秒', exact: true }).click()
    await crossfade.click()
    await crossfadeChoices.getByRole('option', { name: '3 秒', exact: true }).waitFor()
    await page.waitForFunction(() => document.activeElement?.getAttribute('data-value') === '3')
    await page.keyboard.press('Home')
    await page.waitForFunction(() => document.activeElement?.getAttribute('data-value') === '0')
    assert.equal(await crossfadeChoices.getByRole('option', { name: '3 秒', exact: true }).getAttribute('aria-selected'), 'true',
      'Keyboard navigation moves focus without committing a setting')
    await page.keyboard.press('Escape')
    await crossfadeChoices.waitFor({ state: 'hidden' })
    assert.match(await crossfade.innerText(), /^3 秒/, 'Cancel must retain the previous setting')
    await crossfade.click()
    await crossfadeChoices.waitFor()
    await page.evaluate(() => history.back())
    await crossfadeChoices.waitFor({ state: 'hidden' })
    await playbackOptions.waitFor()
    assert.match(await crossfade.innerText(), /^3 秒/, 'Back must close only the picker and not commit a setting')
    await choose(playbackOptions.getByLabel('循环模式', { exact: true }), 'all')
    await playbackOptions.getByLabel('随机播放完整歌单', { exact: true }).check()
    await playbackOptions.getByLabel('恢复上次歌曲与进度', { exact: true }).uncheck()
    await choose(playbackOptions.getByLabel('播放速度', { exact: true }), '1.25')
    await choose(playbackOptions.getByLabel('睡眠定时器', { exact: true }), '30')
    await playbackOptions.getByRole('button', { name: '保存', exact: true }).click()
    await playbackOptions.getByText('播放设置已保存', { exact: true }).waitFor()
    assert.ok(await page.evaluate(() => window.__fixture.calls.some(call => call.method === 'setPlaybackOptions' && call.options.repeatMode === 'all' && call.options.shuffle && !call.options.rememberPlayback && call.options.speed === 1.25 && call.options.normalizeVolume && call.options.prefetch && call.options.crossfadeSeconds === 3 && call.options.volume === 1 && call.options.sleepMinutes === 30)))
    await playbackOptions.getByText('Fixture headphones', { exact: true }).waitFor()
    assert.ok((await playbackOptions.innerText()).includes('不代表当前实际输出路线'))
    assert.equal(await playbackOptions.evaluate(dialog => dialog.scrollWidth > dialog.clientWidth), false)
    await playbackOptions.evaluate(dialog => { dialog.scrollTop = 0 })
    await playbackOptions.getByRole('heading', { name: '播放设置', exact: true }).scrollIntoViewIfNeeded()
    const optionsHeader = await playbackOptions.locator('.sheet-header').boundingBox()
    assert.ok(optionsHeader.y >= 0 && optionsHeader.y + optionsHeader.height <= 852, JSON.stringify(optionsHeader))
    await page.screenshot({ path: path.join(guideImages, 'playback-options.png') })
    await playbackOptions.getByRole('button', { name: '关闭', exact: true }).click()
    await player.waitFor()
    await playerAction(player, '播放设置')
    assert.equal(await normalization.isChecked(), true, 'ReplayGain preference survives reopening its secondary dialog')
    assert.equal(await prefetchNext.isChecked(), true, 'Prefetch preference survives reopening its secondary dialog')
    assert.match(await crossfade.innerText(), /^3 秒/, 'Crossfade preference survives reopening its secondary dialog')
    await playbackOptions.getByRole('button', { name: '关闭', exact: true }).click()
    await player.getByRole('button', { name: '关闭', exact: true }).click()
    assert.equal(await page.getByText('ReplayGain（曲目标签）', { exact: true }).count(), 0, 'Technical tags stay out of the main interface')
    await nav('library').click()
    await page.getByRole('button', { name: '歌曲详情 · Song 001', exact: true }).click()
    const untaggedDetail = page.getByRole('dialog', { name: '歌曲详情', exact: true })
    assert.equal(await untaggedDetail.getByText('无可用标签', { exact: true }).count(), 2, 'Unknown tags remain unknown, not fabricated 0 dB')
    await untaggedDetail.getByRole('button', { name: '关闭', exact: true }).click()
    await page.getByRole('button', { name: '歌曲详情 · Song 002', exact: true }).click()
    const zeroGainDetail = page.getByRole('dialog', { name: '歌曲详情', exact: true })
    await zeroGainDetail.getByText('0.00 dB', { exact: true }).waitFor()
    await zeroGainDetail.getByRole('button', { name: '关闭', exact: true }).click()
    await page.getByRole('button', { name: '歌单', exact: true }).click()
    await page.getByRole('button', { name: '创建歌单', exact: true }).click()
    const editor = page.getByRole('dialog', { name: '创建歌单', exact: true })
    await editor.getByLabel('名称', { exact: true }).fill('On the train')
    await editor.getByRole('button', { name: '保存', exact: true }).click()
    await page.getByRole('button', { name: /On the train/ }).waitFor()
    await page.getByRole('button', { name: '歌曲', exact: true }).click()
    await page.getByRole('button', { name: '歌曲详情 · Song 000', exact: true }).click()
    await page.getByRole('dialog', { name: '歌曲详情', exact: true }).getByRole('button', { name: '加入歌单', exact: true }).click()
    await page.getByRole('dialog', { name: '加入歌单', exact: true }).getByRole('button', { name: 'On the train', exact: true }).click()
    await page.getByRole('dialog', { name: '歌曲详情', exact: true }).getByRole('button', { name: '关闭', exact: true }).click()
    await page.getByRole('button', { name: '歌单', exact: true }).click()
    await page.getByRole('button', { name: /On the train/ }).click()
    await page.getByRole('button', { name: '播放 · Song 000', exact: true }).waitFor()
    await page.getByRole('button', { name: '从歌单移除 · Song 000', exact: true }).click()
    await page.getByRole('button', { name: '播放 · Song 000', exact: true }).waitFor({ state: 'detached' })
    await nav('settings').click()
    await page.getByRole('button', { name: '深色', exact: true }).click()
    await page.waitForFunction(() => document.documentElement.dataset.dark === 'true')
    await page.evaluate(() => { window.__fixture.appearanceDelay = 150 })
    await choose(page.getByLabel('歌词字号', { exact: true }), '36')
    await page.getByLabel('界面过渡动画', { exact: true }).uncheck()
    await page.getByLabel('封面氛围背景', { exact: true }).uncheck()
    await page.waitForFunction(() => document.documentElement.style.getPropertyValue('--lyrics-size') === '36px' && document.documentElement.dataset.motion === 'false')
    await page.waitForFunction(() => {
      const saved = window.__fixture.calls.filter(call => call.method === 'setAppearance').at(-1)?.options
      return saved?.lyricsSize === 36 && saved.motion === false && saved.mode === 'dark' && saved.showBackdrop === false
    })
    assert.equal(await page.evaluate(() => document.documentElement.dataset.dark), 'true', 'Quick appearance changes must retain the theme')
    await page.evaluate(() => { window.__fixture.appearanceDelay = 0 })
    // Disabling removes the image-bearing layer, not just its opacity; no resource API request.
    const lookupsBefore = await page.evaluate(() => window.__fixture.calls.filter(call => ['lookupAssets', 'searchAssets', 'previewAsset'].includes(call.method)).length)
    await page.getByLabel('轻量视觉效果', { exact: true }).uncheck()
    await page.locator('.mini-track').click()
    assert.equal(await player.locator('.cover-background').count(), 0)
    assert.equal(await player.locator('.player-art img').count(), 1)
    await player.getByRole('button', { name: '关闭', exact: true }).click()
    await page.getByLabel('封面氛围背景', { exact: true }).check()
    await page.locator('.mini-track').click()
    await player.locator('.cover-background').waitFor()
    await page.setViewportSize({ width: 1200, height: 852 })
    await page.waitForFunction(() => document.querySelector('.tablet-player .cover-background'))
    assert.equal(await page.locator('.mini-player').count(), 0, 'Wide layout must not mount a hidden mini player')
    const resizePlayCalls = await page.evaluate(() => window.__fixture.calls.filter(call => ['play', 'toggle', 'next', 'previous', 'seek'].includes(call.method)).length)
    for (const width of [999, 1000, 840, 1200]) {
      await page.setViewportSize({ width, height: 852 })
      await page.waitForFunction(wide => Boolean(document.querySelector('.tablet-player')) === wide, width >= 1000)
      assert.equal(await page.locator(width >= 1000 ? '.mini-player' : '.tablet-player').count(), 0)
    }
    await page.setViewportSize({ width: 393, height: 852 })
    await page.locator('.mini-player').waitFor({ state: 'attached' })
    assert.equal(await page.locator('.tablet-player').count(), 0, 'Phones must not mount an invisible full lyric/artwork tree')
    assert.equal(await page.evaluate(() => window.__fixture.calls.filter(call => ['play', 'toggle', 'next', 'previous', 'seek'].includes(call.method)).length), resizePlayCalls, 'Layout changes must not send playback/seek commands')
    await page.evaluate(() => { window.__fixture.setPowerSave(true); document.dispatchEvent(new Event('visibilitychange')) })
    await page.waitForFunction(() => document.documentElement.dataset.lowPower === 'true')
    await player.locator('.cover-background').waitFor({ state: 'detached' })
    await page.evaluate(() => { window.__fixture.setPowerSave(false); document.dispatchEvent(new Event('visibilitychange')) })
    await player.locator('.cover-background').waitFor()
    await player.getByRole('button', { name: '关闭', exact: true }).click()
    await page.getByLabel('轻量视觉效果', { exact: true }).check()
    await page.locator('.mini-track').click()
    assert.equal(await player.locator('.cover-background').count(), 0, 'Power-saving visuals take priority over artwork preference')
    await player.getByRole('button', { name: '关闭', exact: true }).click()
    assert.equal(await page.evaluate(() => window.__fixture.calls.filter(call => ['lookupAssets', 'searchAssets', 'previewAsset'].includes(call.method)).length), lookupsBefore)
    const clearedBefore = await page.evaluate(() => window.__fixture.calls.filter(call => call.method === 'clearStreamCache').length)
    dismissNextDialog = true
    await page.getByRole('button', { name: '清理流式缓存', exact: true }).click()
    assert.equal(await page.evaluate(() => window.__fixture.calls.filter(call => call.method === 'clearStreamCache').length), clearedBefore, 'Cancel must make no clearing call')
    await page.getByRole('button', { name: '清理流式缓存', exact: true }).click()
    await page.waitForFunction(() => window.__fixture.calls.some(call => call.method === 'clearStreamCache'))
    await page.getByText('正在使用的缓存已保留', { exact: false }).waitFor()
    assert.equal(await page.evaluate(() => window.__fixture.calls.filter(call => call.method === 'removeDownload' || call.method === 'cancelDownload').length), 0, 'Stream-cache cleanup must not touch downloads')
    await page.locator('.toast').click()
    await page.getByRole('heading', { name: '离线与存储', exact: true }).scrollIntoViewIfNeeded()
    await page.screenshot({ path: path.join(guideImages, 'storage-settings.png') })
    await page.locator('.mini-track').click()
    await player.getByRole('button', { name: '歌词', exact: true }).click()
    assert.equal(await player.locator('.lyric-panel button').first().evaluate(node => getComputedStyle(node).fontSize), '36px')
    for (const width of [360, 600, 1200]) {
      await page.setViewportSize({ width, height: 852 })
      assert.equal(await player.evaluate(dialog => dialog.scrollWidth > dialog.clientWidth), false, '36px lyric size must fit at ' + width)
    }
    await page.setViewportSize({ width: 393, height: 852 })
    await player.getByRole('button', { name: '关闭', exact: true }).click()
    await page.getByRole('heading', { name: '设置', exact: true }).scrollIntoViewIfNeeded()
    await page.waitForTimeout(250)
    await page.screenshot({ path: path.join(output, 'mobile-phone-dark.png'), fullPage: true })
    await page.screenshot({ path: path.join(guideImages, 'phone-settings.png') })
    await choose(page.locator('[data-setting="language"]'), 'en')
    await page.getByRole('heading', { name: 'Settings', exact: true }).waitFor()
    assert.equal(await page.getByLabel('Artwork backdrop', { exact: true }).isChecked(), true)
    await choose(page.locator('[data-setting="language"]'), 'fr')
    await page.getByRole('heading', { name: 'Réglages', exact: true }).waitFor()
    assert.equal(await page.getByLabel('Fond de pochette', { exact: true }).isChecked(), true)
    await choose(page.locator('[data-setting="language"]'), 'zh-TW')
    await page.getByRole('heading', { name: '設定', exact: true }).waitFor()
    assert.equal(await page.getByLabel('封面氛圍背景', { exact: true }).isChecked(), true)
    await choose(page.locator('[data-setting="language"]'), 'zh-CN')
    // Errors must be localized and bounded; never display raw signed/private URLs.
    await page.evaluate(() => window.__fixture.setPlaybackError('AUDIO_HTTP_403', 2000))
    for (const [locale, text] of [['zh-CN', '服务器拒绝音频下载'], ['zh-TW', '伺服器拒絕音訊下載'], ['en', 'The server refused the audio download'], ['fr', 'Le serveur refuse le téléchargement audio']]) {
      await choose(page.locator('[data-setting="language"]'), locale)
      await page.locator('.mini-track').click()
      const errorPlayer = page.locator('.player-sheet')
      await errorPlayer.getByText(text, { exact: false }).waitFor()
      assert.ok((await errorPlayer.innerText()).includes('AUDIO_HTTP_403 · Media3 2000'))
      assert.equal(await errorPlayer.evaluate(dialog => dialog.scrollWidth > dialog.clientWidth), false, locale + ' error message must fit')
      await errorPlayer.locator('.sheet-header button').click()
    }
    await choose(page.locator('[data-setting="language"]'), 'zh-CN')
    await page.evaluate(() => window.__fixture.setPlaybackError('Source error https://fixture.invalid/private?signature=do-not-display', 2000))
    await page.locator('.mini-track').click()
    await player.getByText('音频读取失败', { exact: false }).waitFor()
    assert.ok(!(await player.innerText()).includes('do-not-display'))
    await page.evaluate(() => window.__fixture.setPlaybackError('AUDIO_TIMEOUT', 2002))
    await player.getByText('音频连接超时', { exact: false }).waitFor()
    await player.getByRole('button', { name: '播放', exact: true }).waitFor()
    const failedPolls = await page.evaluate(() => window.__fixture.calls.filter(call => call.method === 'playback').length)
    await page.waitForTimeout(2100)
    assert.equal(await page.evaluate(() => window.__fixture.calls.filter(call => call.method === 'playback').length), failedPolls, 'Failed player must not keep polling')
    await player.getByRole('button', { name: '播放', exact: true }).click()
    await player.getByRole('button', { name: '暂停', exact: true }).waitFor()
    assert.equal(await player.getByText('AUDIO_TIMEOUT', { exact: false }).count(), 0, 'Single retry should clear the stale error')
    await player.getByRole('button', { name: '关闭', exact: true }).click()
    for (const [locale, title] of [['en', 'Downloads'], ['fr', 'Téléchargements'], ['zh-TW', '下載工作']]) {
      await choose(page.locator('[data-setting="language"]'), locale)
      await page.locator('.mini-track').click()
      await playerAction(page.locator('.player-sheet'), locale === 'en' ? 'Playback settings' : locale === 'fr' ? 'Réglages de lecture' : '播放設定')
      const translatedOptions = page.locator('.playback-options-sheet')
      const label = locale === 'en' ? 'Tag-based normalization (attenuate only)' : locale === 'fr' ? 'Normalisation par tags (atténuation seule)' : '標籤音量一致化（只衰減）'
      assert.equal(await translatedOptions.getByLabel(label, { exact: true }).isChecked(), true)
      const prefetchLabel = locale === 'en' ? 'Cache the next track ahead' : locale === 'fr' ? 'Mettre le titre suivant en cache' : '提前快取下一首'
      assert.equal(await translatedOptions.getByLabel(prefetchLabel, { exact: true }).isChecked(), true)
      const crossfadeLabel = locale === 'en' ? 'Crossfade' : locale === 'fr' ? 'Fondu enchaîné' : '交叉淡化'
      assert.match(await translatedOptions.getByLabel(crossfadeLabel, { exact: true }).innerText(), /^3\s/)
      assert.equal(await translatedOptions.evaluate(dialog => dialog.scrollWidth > dialog.clientWidth), false, locale + ' playback settings must fit')
      await translatedOptions.locator('.sheet-header button').click()
      await page.locator('.player-sheet .sheet-header button').click()
      await page.getByRole('button', { name: title, exact: true }).click()
      const translatedDownloads = page.getByRole('dialog', { name: title, exact: true })
      await translatedDownloads.locator('.download-task').first().waitFor()
      assert.equal(await translatedDownloads.evaluate(dialog => dialog.scrollWidth > dialog.clientWidth), false, locale + ' download layout must fit')
      await translatedDownloads.locator('.sheet-header button').click()
    }
    await choose(page.locator('[data-setting="language"]'), 'zh-CN')
    await page.getByRole('button', { name: '下载任务', exact: true }).click()
    const downloads = page.getByRole('dialog', { name: '下载任务', exact: true })
    await downloads.locator('.download-task').nth(59).waitFor()
    assert.equal(await downloads.locator('.download-task').count(), 60, 'Download UI must use bounded pages')
    assert.equal(await downloads.getByRole('progressbar').first().getAttribute('value'), '20')
    assert.equal(await downloads.evaluate(dialog => dialog.scrollWidth > dialog.clientWidth), false)
    await downloads.getByRole('button', { name: '下一页', exact: true }).click()
    await downloads.getByRole('heading', { name: 'Song 064', exact: true }).waitFor()
    assert.equal(await downloads.locator('.download-task').count(), 5)
    await downloads.getByRole('button', { name: '上一页', exact: true }).click()
    const firstDownload = downloads.locator('.download-task').filter({ has: page.getByRole('heading', { name: 'Song 000', exact: true }) })
    await firstDownload.getByRole('button', { name: '暂停', exact: true }).click()
    await firstDownload.getByRole('button', { name: '继续下载', exact: true }).waitFor()
    await firstDownload.getByRole('button', { name: '继续下载', exact: true }).click()
    await firstDownload.getByText('等待网络或系统调度', { exact: true }).waitFor()
    await page.evaluate(() => window.__fixture.emit('appStateChanged', { active: false }))
    const downloadPolls = await page.evaluate(() => window.__fixture.calls.filter(call => call.method === 'downloadTasks').length)
    await page.waitForTimeout(4200)
    assert.equal(await page.evaluate(() => window.__fixture.calls.filter(call => call.method === 'downloadTasks').length), downloadPolls, 'Background download UI must not poll')
    await page.evaluate(() => window.__fixture.emit('appStateChanged', { active: true }))
    await firstDownload.getByRole('button', { name: '暂停', exact: true }).waitFor()
    await firstDownload.getByRole('button', { name: '取消并清理', exact: true }).click()
    await downloads.getByRole('heading', { name: 'Song 000', exact: true }).waitFor({ state: 'detached' })
    const failedDownload = downloads.locator('.download-task').filter({ has: page.getByRole('heading', { name: 'Song 001', exact: true }) })
    await failedDownload.getByRole('button', { name: '重试', exact: true }).click()
    await failedDownload.getByRole('button', { name: '暂停', exact: true }).waitFor()
    await downloads.evaluate(dialog => { dialog.scrollTop = 0 })
    await page.screenshot({ path: path.join(guideImages, 'download-tasks.png') })
    await downloads.getByRole('button', { name: '关闭', exact: true }).click()
    const closedPolls = await page.evaluate(() => window.__fixture.calls.filter(call => call.method === 'downloadTasks').length)
    await page.waitForTimeout(4200)
    assert.equal(await page.evaluate(() => window.__fixture.calls.filter(call => call.method === 'downloadTasks').length), closedPolls, 'Closed download dialog must not poll')
    const services = page.locator('.ai-settings')
    const resources = page.locator('.asset-settings')
    assert.equal(await resources.getByLabel('单独启用 iTunes 封面', { exact: true }).isChecked(), false, 'iTunes must be opt-in')
    dismissNextDialog = true
    await resources.getByLabel('单独启用 iTunes 封面', { exact: true }).click()
    assert.equal(await resources.getByLabel('单独启用 iTunes 封面', { exact: true }).isChecked(), false)
    await resources.getByRole('button', { name: '添加资源 API', exact: true }).click()
    await resources.getByLabel('名称', { exact: true }).fill('Fixture cover API')
    await choose(resources.getByLabel('资源类型', { exact: true }), 'cover')
    await resources.getByLabel('完整资源接口（HTTPS）', { exact: true }).fill('https://example.com/api/cover')
    await resources.getByLabel('Bearer 密钥（可选）', { exact: true }).fill('fixture-resource-key')
    await resources.getByRole('button', { name: '添加资源 API', exact: true }).click()
    const resource2 = resources.locator('.api-provider').nth(1)
    await resource2.getByLabel('名称', { exact: true }).fill('Fixture lyric API')
    await resource2.getByLabel('完整资源接口（HTTPS）', { exact: true }).fill('https://example.com/api/lyrics')
    await resource2.getByRole('button', { name: '提高优先级', exact: true }).click()
    await resources.getByRole('button', { name: '保存 API 与优先级', exact: true }).click()
    await page.waitForFunction(() => window.__fixture.calls.some(call => call.method === 'saveAssetProviders'))
    const savedResources = await page.evaluate(() => window.__fixture.calls.filter(call => call.method === 'saveAssetProviders').at(-1).options)
    assert.equal(savedResources.providers[0].kind, 'lyrics'); assert.equal(savedResources.providers[1].kind, 'cover'); assert.equal(savedResources.itunesEnabled, false)
    assert.equal(await resources.locator('input[type="password"]').nth(1).inputValue(), '', 'Resource keys must be cleared after saving')
    assert.ok(!JSON.stringify(await page.evaluate(() => window.Capacitor.nativePromise('AuroraMusic', 'assetProviders'))).includes('fixture-resource-key'))
    for (const item of await resources.locator('.api-provider').all()) if (await item.getAttribute('open') !== null) await item.locator('summary').click()
    if (await page.locator('.toast').count()) await page.locator('.toast').click()
    await resources.getByRole('heading', { name: '歌词、封面与背景来源', exact: true }).evaluate(heading => {
      heading.scrollIntoView({ block: 'start' }); window.scrollBy(0, -90)
    })
    await page.screenshot({ path: path.join(guideImages, 'asset-settings.png') })
    await services.getByRole('button', { name: '＋ Responses', exact: true }).click()
    await services.getByLabel('名称', { exact: true }).fill('Fixture Responses')
    await services.getByLabel('模型 ID（按服务提供方填写）', { exact: true }).fill('fixture-model')
    await services.getByLabel('API Key', { exact: true }).fill('fixture')
    await services.getByRole('button', { name: '＋ DeepSeek', exact: true }).click()
    await services.getByRole('button', { name: '提高优先级', exact: true }).nth(1).click()
    await services.getByRole('button', { name: '保存 API 与优先级', exact: true }).click()
    await page.waitForFunction(() => window.__fixture.calls.some(call => call.method === 'saveAiProviders'))
    const savedApis = await page.evaluate(() => window.__fixture.calls.filter(call => call.method === 'saveAiProviders').at(-1).options.providers)
    assert.equal(savedApis[0].protocol, 'openai'); assert.equal(savedApis[1].protocol, 'responses')
    assert.equal(await services.locator('input[type="password"]').nth(1).inputValue(), '', 'Saved keys must be cleared from UI')
    await services.locator('.api-provider').nth(1).locator('summary').click()
    await services.locator('.api-provider').nth(1).getByRole('button', { name: '保存并测试', exact: true }).click()
    await page.waitForFunction(() => window.__fixture.calls.some(call => call.method === 'testAiProvider'))
    await nav('listen').click()
    await page.getByRole('button', { name: '✧ 自然语言歌单', exact: true }).click()
    let generator = page.getByRole('dialog', { name: '自然语言歌单', exact: true })
    await generator.getByLabel('你想听什么？', { exact: true }).fill('1首 Song 359')
    const apiCallsBefore = await page.evaluate(() => window.__fixture.calls.filter(call => call.method === 'requestAi').length)
    await generator.getByRole('button', { name: '生成歌单', exact: true }).click()
    await generator.locator('.ai-track-list li').waitFor()
    assert.equal(await generator.locator('.ai-track-list strong').first().innerText(), 'Song 359')
    assert.equal(await page.evaluate(() => window.__fixture.calls.filter(call => call.method === 'requestAi').length), apiCallsBefore, 'Local mode must make no AI requests')
    assert.ok(await page.evaluate(() => window.__fixture.calls.some(call => call.method === 'catalogPage' && call.options.cursor === 200)))
    await generator.getByRole('button', { name: '保存到歌单', exact: true }).click()
    await generator.waitFor({ state: 'hidden' })
    assert.deepEqual(await page.evaluate(() => window.__fixture.calls.filter(call => call.method === 'savePlaylist').at(-1).options.songIds), ['song-359'])
    await page.getByRole('button', { name: '✧ 自然语言歌单', exact: true }).click()
    generator = page.getByRole('dialog', { name: '自然语言歌单', exact: true })
    await generator.getByLabel('你想听什么？', { exact: true }).fill('2首')
    await generator.getByLabel('结合已配置的 AI 服务', { exact: true }).check()
    await generator.getByRole('button', { name: '生成歌单', exact: true }).click()
    await generator.locator('.ai-track-list li').nth(1).waitFor()
    assert.equal(await generator.locator('.ai-track-list li').count(), 2)
    const selection = await page.evaluate(() => window.__fixture.calls.find(call => call.method === 'requestAi' && call.options.stage === 'select').options.user)
    assert.ok(!selection.includes('example.com/music') && !selection.includes('customLyrics'))
    await generator.getByRole('button', { name: '关闭', exact: true }).click()
    await page.getByRole('button', { name: '✧ 自然语言歌单', exact: true }).click()
    await generator.getByLabel('你想听什么？', { exact: true }).fill('2首')
    await generator.getByLabel('结合已配置的 AI 服务', { exact: true }).check()
    await page.evaluate(() => { window.__fixture.aiDelay = 800 })
    const planBefore = await page.evaluate(() => window.__fixture.calls.filter(call => call.method === 'requestAi' && call.options.stage === 'plan').length)
    await generator.getByRole('button', { name: '生成歌单', exact: true }).click()
    await page.waitForFunction(count => window.__fixture.calls.filter(call => call.method === 'requestAi' && call.options.stage === 'plan').length > count, planBefore)
    await generator.getByRole('button', { name: '关闭', exact: true }).click()
    await page.waitForTimeout(1000)
    assert.ok(await page.evaluate(() => window.__fixture.calls.some(call => call.method === 'cancelAi')))
    await nav('listen').click()
    await page.evaluate(() => window.__fixture.emit('appStateChanged', { active: false }))
    const pollsBefore = await page.evaluate(() => window.__fixture.calls.filter(call => call.method === 'playback').length)
    await page.waitForTimeout(2100)
    const pollsAfter = await page.evaluate(() => window.__fixture.calls.filter(call => call.method === 'playback').length)
    assert.equal(pollsAfter, pollsBefore, 'Background WebView must not poll playback')
    await page.evaluate(() => window.__fixture.emit('appStateChanged', { active: true }))
    await page.locator('.mini-player').getByRole('button', { name: '暂停', exact: true }).click()
    await page.locator('.mini-player').getByRole('button', { name: '播放', exact: true }).waitFor()
    const pausedBefore = await page.evaluate(() => window.__fixture.calls.filter(call => call.method === 'playback').length)
    await page.waitForTimeout(2100)
    assert.equal(await page.evaluate(() => window.__fixture.calls.filter(call => call.method === 'playback').length), pausedBefore, 'Paused player must not poll playback')
    // Stress actual rendered pixel-sized text, not just the unused root rem size.
    await page.evaluate(() => {
      const text = [...document.querySelectorAll('body *')].map(element => ({ element, size: parseFloat(getComputedStyle(element).fontSize) }))
      text.forEach(({ element, size }) => { if (size) element.style.fontSize = size * 2 + 'px' })
    })
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false, '200% text stress must not cause horizontal overflow')
    assert.deepEqual(errors, [])
    console.log('Mobile UI passed: responsive layouts, full library/queues/collections/playlists, lyrics/36px font, playback/restore/device dialog, opt-in ReplayGain/prefetch/crossfade and secondary-only tags in 4 languages, themes/backdrop/power priority, cache/download safety and hidden polling, AI/resource priorities/key clearing, opt-in iTunes, secondary asset search/preview/confirmation/reset/stale cancellation, full-library local/hybrid playlists. Native calls are mocked; not a hardware playback test.')
  } finally { await browser.close(); await new Promise(resolve => server.close(resolve)) }
}
main().catch(error => { console.error(error); process.exitCode = 1 })
