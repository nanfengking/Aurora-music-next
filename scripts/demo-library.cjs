// Original, synthetic fixtures. Never reads an existing library or contacts a service.
module.exports = function seedDemo({ app, nativeImage }, root) {
  const require = globalThis.reviewRequire
  const fs = require('node:fs')
  const { join } = require('node:path')
  const Database = require(join(root, 'node_modules/better-sqlite3'))
  const profile = app.getPath('userData')
  const audioDir = join(profile, 'webdav-cache'), coverDir = join(profile, 'covers')
  fs.mkdirSync(audioDir, { recursive: true }); fs.mkdirSync(coverDir, { recursive: true })
  const rate = 8000, seconds = 60, pcm = Buffer.alloc(rate * seconds * 2)
  for (let i = 0; i < rate * seconds; i++) pcm.writeInt16LE(Math.round(400 * Math.sin(i * 2 * Math.PI * 220 / rate)), i * 2)
  const header = Buffer.alloc(44)
  header.write('RIFF'); header.writeUInt32LE(pcm.length + 36, 4); header.write('WAVEfmt ', 8)
  header.writeUInt32LE(16, 16); header.writeUInt16LE(1, 20); header.writeUInt16LE(1, 22)
  header.writeUInt32LE(rate, 24); header.writeUInt32LE(rate * 2, 28); header.writeUInt16LE(2, 32); header.writeUInt16LE(16, 34)
  header.write('data', 36); header.writeUInt32LE(pcm.length, 40)
  const audio = join(audioDir, 'original-demo.wav')
  fs.writeFileSync(audio, Buffer.concat([header, pcm]))
  const albums = ['Afterglow', 'Quiet Harbour', 'Blue Hour', 'Paper Skies', 'Moonlit Avenue']
  const artists = ['Studio North', 'Mira Vale', 'The Soft Lines', 'Juniper Field', 'Atlas Echo']
  const names = ['First Light', 'A Little Further', 'Warm September', 'Slow Motion', 'Window Seat', 'Still Water', 'Daydream', 'Homeward']
  const colors = [[176, 100, 70], [73, 139, 140], [55, 80, 132], [181, 134, 127], [109, 88, 141]]
  const covers = colors.map(([r, g, b], k) => {
    const size = 300, pixels = Buffer.alloc(size * size * 4)
    for (let y = 0; y < size; y++) for (let x = 0; x < size; x++) {
      const light = 0.5 + 0.5 * (1 - y / size), sun = Math.hypot(x - 180, y - 95) < 46
      const wave = y > 200 + 24 * Math.sin(x / 75 + k)
      const rgb = sun ? [243, 218, 169] : [r, g, b].map(v => Math.round(v * light * (wave ? 0.65 : 1)))
      const i = (y * size + x) * 4
      pixels[i] = rgb[2]; pixels[i + 1] = rgb[1]; pixels[i + 2] = rgb[0]; pixels[i + 3] = 255
    }
    const path = join(coverDir, `demo-${k}.png`)
    fs.writeFileSync(path, nativeImage.createFromBitmap(pixels, { width: size, height: size }).toPNG())
    return path
  })
  const db = new Database(join(profile, 'musicPlayer.db'))
  const insert = db.prepare('INSERT INTO songs (id,title,artist,album,genre,duration,filePath,sourceType,remotePath,localCachePath,coverPath,isFavorite,playCount,lastPlayedAt,format) VALUES (?,?,?,?,?,60,?,?,?,?,?,?,?,?,?)')
  db.transaction(() => {
    for (let i = 0; i < 125; i++) {
      const group = i % 5, remote = `/demo/track-${i}.wav`
      insert.run('demo-' + i, `${names[i % names.length]}${i > 7 ? ' ' + (Math.floor(i / 8) + 1) : ''}`, artists[group], albums[group], ['Jazz', 'Folk', 'Pop', 'Ambient', 'Jazz'][group], remote, 'webdav', remote, audio, covers[group], i < 10 ? 1 : 0, i < 10 ? 12 - i : 0, i < 10 ? Date.now() - i * 86400000 : null, 'WAV')
    }
    for (let i = 0; i < 18; i++) db.prepare('INSERT INTO play_history(songId,action,timestamp,progress) VALUES (?,?,?,?)').run('demo-' + (i % 5), 'complete', Date.now() - i * 86400000, 1)
    db.prepare('UPDATE songs SET customLyrics=? WHERE id=?').run('[00:00.00]一段音乐，留给此刻\n[00:05.00]慢慢听，慢慢走\n[00:10.00]时间停在旋律里\n[00:20.00]把今天温柔收藏', 'demo-0')
    db.prepare('INSERT INTO playlists(id,name,description,songIds) VALUES (?,?,?,?)').run('demo-list', 'Evening, softly', 'An original demonstration playlist.', JSON.stringify(Array.from({ length: 8 }, (_, i) => 'demo-' + i)))
  })()
  db.close()
  return { songs: 125, covers: 125, cached: 125, playable: { id: 'demo-0', title: 'First Light', artist: 'Studio North' } }
}
