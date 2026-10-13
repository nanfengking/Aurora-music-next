// Real Gradle/APK checks with disposable fixture keys, never a user's signing key.
const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const assert = require('node:assert/strict')
const { spawnSync } = require('node:child_process')

const root = path.resolve(__dirname, '..'), android = path.join(root, 'android')
const directory = path.join(android, '.build-temp')
const id = crypto.randomUUID(), key = path.join(directory, 'signing-' + id + '.jks'), debugKey = path.join(directory, 'debug-signing-' + id + '.jks')
const base = { ...process.env }
for (const name of Object.keys(base)) if (name.startsWith('AURORA_ANDROID_')) delete base[name]
const password = crypto.randomBytes(24).toString('hex')
const fixture = { ...base, AURORA_FIXTURE_PASSWORD: password }
const executable = name => process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', name + (process.platform === 'win32' ? '.exe' : '')) : name
const run = (file, args, env = base) => {
  const result = spawnSync(file, args, { cwd: root, env, encoding: 'utf8', maxBuffer: 1024 * 1024, timeout: 120000, windowsHide: true })
  if (result.error || result.signal) throw new Error('Signing fixture subprocess did not finish normally.')
  return result
}
const gradle = (task, env) => run(executable('java'), ['-cp', path.join(android, 'gradle/wrapper/gradle-wrapper.jar'),
  'org.gradle.wrapper.GradleWrapperMain', '-p', android, task, '--offline', '--console=plain', '--no-daemon', '--no-configuration-cache', '--quiet'], env)
function createKey(file, alias, subject) {
  assert.equal(fs.existsSync(file), false)
  const result = run(executable('keytool'), ['-genkeypair', '-noprompt', '-storetype', 'JKS', '-keystore', file, '-alias', alias,
    '-storepass:env', 'AURORA_FIXTURE_PASSWORD', '-keypass:env', 'AURORA_FIXTURE_PASSWORD', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '2', '-dname', subject], fixture)
  assert.equal(result.status, 0, 'Disposable key generation must succeed')
  const cert = spawnSync(executable('keytool'), ['-exportcert', '-keystore', file, '-alias', alias, '-storepass:env', 'AURORA_FIXTURE_PASSWORD'],
    { cwd: root, env: fixture, maxBuffer: 16384, timeout: 30000, windowsHide: true })
  assert.equal(cert.status, 0)
  return crypto.createHash('sha256').update(cert.stdout).digest('hex')
}
function rejected(env, message) {
  const result = gradle(':app:verifyReleaseSigning', env)
  assert.notEqual(result.status, 0, 'An invalid identity must not pass release signing')
  assert.ok((result.stdout + result.stderr).includes(message), 'Expected a safe, actionable signing error')
  assert.ok(!(result.stdout + result.stderr).includes(password), 'No signing password may appear in diagnostics')
}

try {
  const workflow = require('js-yaml').load(fs.readFileSync(path.join(root, '.github/workflows/android-release.yml'), 'utf8'))
  assert.deepEqual(Object.keys(workflow.on), ['workflow_dispatch'])
  assert.deepEqual(workflow.permissions, { contents: 'read' })
  const job = workflow.jobs.build, steps = job.steps
  assert.equal(job.environment, 'android-release')
  assert.equal(job.if, "github.ref == 'refs/heads/main'")
  assert.ok(!JSON.stringify(job.env).includes('secrets.'), 'Signing passwords must not enter dependency/setup steps')
  for (const step of steps) if (step.uses) assert.match(step.uses, /@[0-9a-f]{40}$/)
  const restore = steps.findIndex(step => step.name === 'Restore private signing key')
  const install = steps.findIndex(step => step.run?.includes('npm ci'))
  const nativeChecks = steps.findIndex(step => step.name === 'Check native code without signing secrets')
  const clear = steps.findIndex(step => step.name === 'Remove only the restored CI key')
  const upload = steps.findIndex(step => step.uses?.startsWith('actions/upload-artifact@'))
  assert.ok(install >= 0 && install < restore && nativeChecks > install && nativeChecks < restore && restore < clear && clear < upload)
  assert.equal(steps[clear].if, 'always()')
  assert.equal(steps[upload].with.path.trim(), 'android/app/build/outputs/apk/release/app-release.apk\nandroid/app/build/outputs/apk/release/release-verification.json')
  for (const step of steps) if (JSON.stringify(step.env || {}).includes('secrets.'))
    assert.ok(['Restore private signing key', 'Build signed APK'].includes(step.name))
  console.log('Signed workflow parsed: manual/main-only, read-only, pinned actions, narrow secret scope, key cleanup before artifact upload.')
  fs.mkdirSync(directory, { recursive: true })
  rejected(base, 'requires all four')
  rejected({ ...base, AURORA_ANDROID_KEY_ALIAS: 'fixture' }, 'requires all four')
  console.log('Signing guards passed: missing and partial configuration.')
  const fingerprint = createKey(key, 'fixture', 'CN=Aurora Disposable Test')
  const signing = { ...base, AURORA_ANDROID_KEYSTORE: key, AURORA_ANDROID_KEY_ALIAS: 'fixture',
    AURORA_ANDROID_STORE_PASSWORD: password, AURORA_ANDROID_KEY_PASSWORD: password, AURORA_ANDROID_CERT_SHA256: fingerprint }
  rejected({ ...signing, AURORA_ANDROID_CERT_SHA256: '0'.repeat(64) }, 'certificate differs')
  rejected({ ...signing, AURORA_ANDROID_STORE_PASSWORD: 'wrong-fixture-password' }, 'Cannot open the release signing key')
  const debugFingerprint = createKey(debugKey, 'androiddebugkey', 'CN=Android Debug')
  rejected({ ...signing, AURORA_ANDROID_KEYSTORE: debugKey, AURORA_ANDROID_KEY_ALIAS: 'androiddebugkey', AURORA_ANDROID_CERT_SHA256: debugFingerprint }, 'Debug keys are not accepted')
  console.log('Signing guards passed: wrong certificate/password and real debug key rejection.')
  const build = gradle(':app:assembleRelease', signing)
  assert.equal(build.status, 0, 'Isolated fixture-signed release must build')
  assert.ok(!(build.stdout + build.stderr).includes(password))
  const verified = run(process.execPath, [path.join(root, 'scripts/android-release-check.cjs')], signing)
  assert.equal(verified.status, 0, 'Actual APK signature/manifest must match the fixture identity')
  const mismatch = run(process.execPath, [path.join(root, 'scripts/android-release-check.cjs')], { ...signing, AURORA_ANDROID_CERT_SHA256: '0'.repeat(64) })
  assert.notEqual(mismatch.status, 0, 'Upload verifier must reject a different certificate')
  console.log('ANDROID_SIGNING_FIXTURE_PASS: 5 configuration guards; signed APK/manifest verified; mismatch rejected. Test-only APK, do not distribute.')
} catch { console.error('ANDROID_SIGNING_FIXTURE_FAILED: isolated signing/build verification failed. Credentials and raw subprocess output are suppressed.'); process.exitCode = 1 }
finally {
  // Exact UUID-named files created above, not a directory or user keystore.
  for (const file of [key, debugKey]) if (fs.existsSync(file)) {
    if (fs.lstatSync(file).isSymbolicLink() || path.dirname(fs.realpathSync(file)) !== fs.realpathSync(directory)) throw new Error('Unsafe fixture cleanup target')
    fs.unlinkSync(file)
  }
}
