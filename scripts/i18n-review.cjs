const fs = require('node:fs'), path = require('node:path'), Module = require('node:module')
const ts = require('typescript'), assert = require('node:assert/strict')
const file = path.resolve(__dirname, '../src/i18n/index.ts')
const compiled = ts.transpileModule(fs.readFileSync(file, 'utf8'), { compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.CommonJS, esModuleInterop: true } })
const instance = new Module(file, module); instance.filename = file; instance.paths = module.paths
instance._compile(compiled.outputText, file)
const { t, tm, setLocale, isLocale, artistLabel } = instance.exports
const messages = require('../src/i18n/messages.json')
assert.equal(isLocale('de'), false)
for (const locale of ['zh-CN', 'zh-TW', 'en', 'fr']) {
  setLocale(locale)
  for (const [source, translated] of Object.entries(messages)) {
    const values = ['USER_TITLE', '41', '42', '43', '44']
    const expected = (translated[locale] || source).replace(/\{(\d+)\}/g, (_, n) => values[n])
    assert.equal(t(source, values), expected)
  }
  assert.equal(artistLabel('Studio North'), 'Studio North')
  assert.equal(artistLabel('未知艺术家'), t('未知艺术家'))
  assert.equal(tm('连接失败（HTTP 401）'), t('连接失败（HTTP {0}）', [401]))
  assert.equal(tm('服务甲：超时、网络失败或返回格式无效'), '服务甲: ' + t('超时、网络失败或返回格式无效'))
  const nested = tm('按优先级尝试后仍未得到有效响应。Service A：HTTP 503；Service B：超时、网络失败或返回格式无效')
  if (locale === 'en' || locale === 'fr') assert.ok(!/[\u3400-\u9fff]/.test(nested))
  assert.equal(tm('Unknown external response'), 'Unknown external response')
}
console.log('I18N_PASS: four catalogues, placeholders, runtime errors, fallback chains and user-name preservation')
