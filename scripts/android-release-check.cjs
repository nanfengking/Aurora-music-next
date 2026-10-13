// Verify the actual APK before uploading. No signing passwords reach verification tools.
const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const { execFileSync } = require('node:child_process')

function verify() {
  const root = path.resolve(__dirname, '..')
  const apk = path.join(root, 'android/app/build/outputs/apk/release/app-release.apk')
  const source = fs.readFileSync(path.join(root, 'android/app/build.gradle'), 'utf8')
  const applicationId = source.match(/applicationId\s+["']([^"']+)["']/)?.[1]
  const versionCode = Number(source.match(/versionCode\s+(\d+)/)?.[1])
  const versionName = source.match(/versionName\s+["']([^"']+)["']/)?.[1]
  const expected = (process.env.AURORA_ANDROID_CERT_SHA256 || '').trim().replace(/:/g, '').toLowerCase()
  if (!applicationId || !Number.isSafeInteger(versionCode) || versionCode < 1 || !versionName || !/^[a-f0-9]{64}$/.test(expected))
    throw new Error('Expected app/version metadata and fixed certificate fingerprint are required.')
  let sdk = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT
  if (!sdk) {
    const properties = fs.readFileSync(path.join(root, 'android/local.properties'), 'utf8')
    sdk = properties.match(/^sdk\.dir=(.+)$/m)?.[1].trim().replace(/\\(.)/g, '$1')
  }
  if (!sdk) throw new Error('Android SDK location is unavailable.')
  const tools = path.join(sdk, 'build-tools/36.0.0')
  const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java'
  const aapt = path.join(tools, process.platform === 'win32' ? 'aapt2.exe' : 'aapt2')
  const env = { ...process.env }
  for (const name of Object.keys(env)) if (name.startsWith('AURORA_ANDROID_')) delete env[name]
  const run = (file, args) => execFileSync(file, args, { cwd: root, env, encoding: 'utf8', maxBuffer: 64 * 1024, timeout: 30000, windowsHide: true })
  const signature = run(java, ['-jar', path.join(tools, 'lib/apksigner.jar'), 'verify', '--verbose', '--print-certs', apk])
  const signers = [...signature.matchAll(/Signer #\d+ certificate SHA-256 digest: ([a-f0-9]{64})/gi)]
  if (signers.length !== 1 || signers[0][1].toLowerCase() !== expected || /CN=Android Debug/i.test(signature))
    throw new Error('APK signature is invalid, debug-signed or differs from the fixed release certificate.')
  const manifest = run(aapt, ['dump', 'badging', apk])
  const metadata = manifest.match(/^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'/m)
  if (!metadata || metadata[1] !== applicationId || Number(metadata[2]) !== versionCode || metadata[3] !== versionName || /^application-debuggable/m.test(manifest))
    throw new Error('APK package/version/debuggable state differs from the release configuration.')
  const bytes = fs.statSync(apk).size
  // Hash in chunks; verification must not load an entire APK into the Node heap.
  const hash = crypto.createHash('sha256'), buffer = Buffer.alloc(64 * 1024), descriptor = fs.openSync(apk, 'r')
  try { for (let count; (count = fs.readSync(descriptor, buffer, 0, buffer.length, null)) > 0;) hash.update(buffer.subarray(0, count)) }
  finally { fs.closeSync(descriptor) }
  const result = { applicationId, versionCode, versionName, bytes, sha256: hash.digest('hex'), certificateSha256: expected }
  fs.writeFileSync(path.join(path.dirname(apk), 'release-verification.json'), JSON.stringify(result, null, 2) + '\n')
  console.log('ANDROID_RELEASE_CHECK_PASS: ' + JSON.stringify(result))
}
try { verify() } catch { console.error('ANDROID_RELEASE_CHECK_FAILED: APK, SDK or signing identity could not be verified. No credentials are printed.'); process.exitCode = 1 }
