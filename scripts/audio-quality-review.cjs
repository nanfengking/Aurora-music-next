const fs = require('node:fs')
const path = require('node:path')
const Module = require('node:module')
const ts = require('typescript')
const assert = require('node:assert/strict')
const file = path.resolve(__dirname, '../src/services/audioQuality.ts')
const compiled = ts.transpileModule(fs.readFileSync(file, 'utf8'), {
  compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.CommonJS },
})
const instance = new Module(file, module)
instance.filename = file
instance.paths = module.paths
instance._compile(compiled.outputText, file)
const { qualityTier } = instance.exports
assert.equal(qualityTier(null), 'unknown')
assert.equal(qualityTier({ format: 'M4A', codec: 'ALAC', bitrate: 900000 }), 'lossless')
assert.equal(qualityTier({ format: 'M4A/MP42', codec: 'AAC', bitrate: 320000 }), 'high-bitrate')
assert.equal(qualityTier({ format: 'MP3', codec: 'MP3', bitrate: 128000 }), 'compressed')
assert.equal(qualityTier({ format: 'M4A', codec: null, bitrate: null }), 'unknown')
console.log('AUDIO_QUALITY_PASS: lossless, high-bitrate lossy, compressed and unknown source labels')
