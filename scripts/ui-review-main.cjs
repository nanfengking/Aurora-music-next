// Test-only entry point. Never packaged. Isolates all UI review writes from real user data.
const { app } = require('electron')
const { mkdirSync } = require('node:fs')
const { resolve, sep } = require('node:path')
const root = resolve(__dirname, '..')
const profile = resolve(process.env.AURORA_UI_REVIEW_PROFILE || resolve(root, '.ui-review-profile'))
if (!profile.startsWith(root + sep)) throw new Error('UI review profile must be inside the project')
mkdirSync(profile, { recursive: true })
app.setPath('userData', profile)
globalThis.reviewRequire = require
// The packaged mode checks ASAR-relative renderer/preload paths and native modules
// with the same Electron runtime, without running the installer or real profile.
require(process.env.AURORA_UI_REVIEW_PACKAGED === '1'
  ? '../release/win-unpacked/resources/app.asar/out/main/index.js'
  : '../out/main/index.js')
