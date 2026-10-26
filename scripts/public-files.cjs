const fs = require('node:fs')
const { resolve, join } = require('node:path')
const root = resolve(__dirname, '..')
const roots = ['src', 'electron', 'scripts', 'docs', 'mobile', 'android', '.github', 'resources/licenses']
const singles = ['.gitignore', 'package.json', 'package-lock.json', 'electron.vite.config.ts', 'capacitor.config.ts', 'index.html', 'tsconfig.json', 'tsconfig.node.json', 'tsconfig.web.json', 'LICENSE', 'README.md', 'README.android.md', 'README.zh-TW.md', 'README.en.md', 'README.fr.md', 'CONTRIBUTING.md', 'PRIVACY.md', 'SECURITY.md', 'THIRD_PARTY_NOTICES.md', 'resources/icon.png', 'resources/ffmpeg/LICENSE', 'resources/ffmpeg/README.txt', 'resources/ffmpeg/SOURCE.md']
const privateBuildDirs = new Set(['.gradle', '.gradle-user', '.android-user', '.build-tools', '.build-temp', '.signing', '.sdk', '.idea', '.kotlin', 'build'])
function publicFiles() {
  const files = [...singles]
  function walk(relative) {
    for (const entry of fs.readdirSync(join(root, relative), { withFileTypes: true })) {
      const name = relative + '/' + entry.name
      // Keep signing keys, downloaded runtimes, machine settings and build caches private.
      if (relative.startsWith('android') && entry.isDirectory() && privateBuildDirs.has(entry.name)) continue
      if (entry.isSymbolicLink()) throw new Error('Symlinks are not allowed in source export: ' + name)
      if (name === 'docs/screenshots' || name === 'docs/superpowers' || name === 'docs/security' || name === 'mobile/dist' || name === 'android/.gradle' || name === 'android/build' || name === 'android/app/build' || name === 'android/app/src/main/assets' || name === 'android/app/src/main/res/xml/config.xml' || name === 'android/local.properties') continue
      if (entry.isDirectory()) walk(name)
      else if (/\.(ts|tsx|css|json|cjs|ps1|md|d\.ts)$/.test(name) || /^resources\/licenses\/[A-Za-z0-9._-]+\.txt$/.test(name) || /^docs\/images\/(zh-CN|zh-TW|en|fr)\/(home|settings|playlist|player)\.png$/.test(name) || /^docs\/images\/android\/(phone-listen|phone-player|tablet-listen|phone-settings|playback-options|download-tasks|storage-settings|asset-preview|asset-settings)\.png$/.test(name) || /^mobile\/.*\.html$/.test(name) || /^\.github\/.*\.yml$/.test(name) || /^android\/.*\.(java|gradle|properties|pro|xml|png|jar|bat|gitignore|gitkeep)$/.test(name) || name === 'android/gradlew') files.push(name)
      else throw new Error('Unexpected public source file; review before export: ' + name)
    }
  }
  roots.forEach(walk)
  for (const file of files) if (!fs.statSync(join(root, file)).isFile() || fs.lstatSync(join(root, file)).isSymbolicLink()) throw new Error('Missing or unsafe source file: ' + file)
  return files.sort()
}
module.exports = { root, publicFiles }
if (require.main === module) process.stdout.write(JSON.stringify(publicFiles()))
