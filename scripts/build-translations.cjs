const fs = require('node:fs')
const path = require('node:path')
const OpenCC = require('opencc-js')
const root = path.resolve(__dirname, '..')
const catalog = require('../src/i18n/catalog.json')
const convert = OpenCC.Converter({ from: 'cn', to: 'twp' })
const messages = {}
for (const [source, [en, fr]] of Object.entries(catalog)) {
  if (!en || !fr) throw new Error(`Missing translation: ${source}`)
  const placeholders = value => [...value.matchAll(/\{(\d+)\}/g)].map(match => match[1]).sort().join(',')
  if (placeholders(source) !== placeholders(en) || placeholders(source) !== placeholders(fr)) throw new Error(`Placeholder mismatch: ${source}`)
  messages[source] = { 'zh-TW': convert(source), en, fr }
}
const output = path.join(root, 'src/i18n/messages.json')
fs.writeFileSync(output, JSON.stringify(messages, null, 2) + '\n')
console.log(`Generated ${Object.keys(messages).length} messages for four locales.`)
