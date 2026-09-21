const fs = require('node:fs')
const { resolve, join } = require('node:path')
const root = resolve(__dirname, '..')
const roots = ['src', 'electron', 'scripts', 'docs']
const singles = ['.gitignore', 'package.json', 'package-lock.json', 'electron.vite.config.ts', 'index.html', 'tsconfig.json', 'tsconfig.node.json', 'tsconfig.web.json', 'LICENSE', 'README.md', 'README.zh-TW.md', 'README.en.md', 'README.fr.md', 'CONTRIBUTING.md', 'PRIVACY.md', 'SECURITY.md', 'THIRD_PARTY_NOTICES.md', 'resources/icon.png', 'resources/ffmpeg/LICENSE', 'resources/ffmpeg/README.txt', 'resources/ffmpeg/SOURCE.md']
function publicFiles() {
  const files = [...singles]
  function walk(relative) {
    for (const entry of fs.readdirSync(join(root, relative), { withFileTypes: true })) {
      const name = relative + '/' + entry.name
      if (entry.isSymbolicLink()) throw new Error('Symlinks are not allowed in source export: ' + name)
      if (name === 'docs/screenshots') continue
      if (entry.isDirectory()) walk(name)
      else if (/\.(ts|tsx|css|json|cjs|ps1|md|d\.ts)$/.test(name) || /^docs\/images\/(zh-CN|zh-TW|en|fr)\/(home|settings|playlist|player)\.png$/.test(name)) files.push(name)
      else throw new Error('Unexpected public source file; review before export: ' + name)
    }
  }
  roots.forEach(walk)
  for (const file of files) if (!fs.statSync(join(root, file)).isFile() || fs.lstatSync(join(root, file)).isSymbolicLink()) throw new Error('Missing or unsafe source file: ' + file)
  return files.sort()
}
module.exports = { root, publicFiles }
if (require.main === module) process.stdout.write(JSON.stringify(publicFiles()))
