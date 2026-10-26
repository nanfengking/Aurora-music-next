const fs = require('node:fs')
const path = require('node:path')
const root = path.resolve(__dirname, '..')
const output = path.join(root, 'android/app/src/main/assets/licenses')
fs.mkdirSync(output, { recursive: true })
for (const name of ['LICENSE', 'THIRD_PARTY_NOTICES.md']) fs.copyFileSync(path.join(root, name), path.join(output, name))
for (const name of fs.readdirSync(path.join(root, 'resources/licenses'))) {
  if (!/^[A-Za-z0-9._-]+\.txt$/.test(name)) throw new Error('Unexpected license file')
  fs.copyFileSync(path.join(root, 'resources/licenses', name), path.join(output, name))
}
console.log('Android open-source license texts included.')
