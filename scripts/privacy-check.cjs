// Heuristic release check, not a guarantee that arbitrary secrets can be recognized.
const fs = require('node:fs')
const { join } = require('node:path')
const { root, publicFiles } = require('./public-files.cjs')
const rules = [
  ['private-key', /-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----/],
  ['service-token', /\b(?:sk-[A-Za-z0-9_-]{20,}|gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,}|AIza[A-Za-z0-9_-]{30,})\b/],
  ['windows-personal-path', /[A-Z]:[\\/]+(?:Users|music_player)[\\/]/i],
  ['credential-in-url', /https?:\/\/[^\s/"'<>]+:[^\s/"'<>]+@/i],
  ['literal-secret', /(?:apiKey|api_key|password|secret)\s*[:=]\s*["'][A-Za-z0-9+/_=-]{24,}["']/i],
]
const findings = [], files = publicFiles()
for (const file of files) {
  if (file.endsWith('.png')) continue // Image review is a separate, required manual check.
  const lines = fs.readFileSync(join(root, file), 'utf8').split(/\r?\n/)
  lines.forEach((line, index) => {
    for (const [rule, expression] of rules) if (expression.test(line)) findings.push({ file, line: index + 1, rule })
  })
}
if (findings.length) { console.error(JSON.stringify(findings, null, 2)); process.exitCode = 1 }
else console.log(`PRIVACY_CHECK_PASS: ${files.length} allowlisted files; no matches. Manual image and release-history review still required.`)
