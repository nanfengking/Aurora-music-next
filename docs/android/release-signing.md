# Android signing and update continuity

[简体中文](#简体中文) · [繁體中文](#繁體中文) · [English](#english) · [Français](#français)

## 简体中文

这里只配置构建，不会替你上传密钥或自动公开 APK。手机版仍在预览测试，固定签名不代表播放、耗电或小米真机验收完成。

- 长期分发保持相同应用 ID／签名，并提高 `android/app/build.gradle` 的 `versionCode`。本机 debug 构建仍保持原方法；GitHub 临时 runner 的 debug 签名不能作为长期更新通道。
- 新 release 密钥通常不能覆盖旧 debug 包。签名不一致时不要卸载／清数据，先找原钥匙；数据导出／迁移仍未完成。
- 不要提交 JKS、密码、`.signing/`，也不要发给 AI。证书公开可见，创建时不要填写手机号、住址等私人信息。

### 本机／Android Studio

已有正式密钥就复用，不要重建。首次可在 **Build → Generate Signed Bundle/APK → APK → Create new** 创建，存入 `android/.signing/aurora-release.jks`，另存安全备份。别名可用 `aurora`，有效期至少 25 年。创建后关闭向导，按下方经过校验的命令构建。

运行 JDK 的 `keytool -list -v -keystore <你的文件> -alias aurora`，交互输入密码并记录公开 SHA-256 指纹。不要把密码放在命令参数或公共文件里。

项目根目录 PowerShell，JDK 21／SDK 36 与调试构建相同：

```powershell
$env:AURORA_ANDROID_KEYSTORE = (Resolve-Path '.\android\.signing\aurora-release.jks').Path
$env:AURORA_ANDROID_KEY_ALIAS = 'aurora'
$env:AURORA_ANDROID_STORE_PASSWORD = [Net.NetworkCredential]::new('', (Read-Host 'Keystore password' -AsSecureString)).Password
$env:AURORA_ANDROID_KEY_PASSWORD = [Net.NetworkCredential]::new('', (Read-Host 'Key password' -AsSecureString)).Password
$env:AURORA_ANDROID_CERT_SHA256 = '<公开的64位SHA-256指纹，可含冒号>'
npm run android:sync
if ($LASTEXITCODE -ne 0) { throw 'Web sync failed; do not package stale assets' }
.\android\gradlew.bat -p android :app:assembleRelease --no-daemon --no-configuration-cache
if ($LASTEXITCODE -ne 0) { throw 'Signed release build failed' }
node scripts/android-release-check.cjs
```

两项都须成功。产物为 `android/app/build/outputs/apk/release/app-release.apk`；旁边的 `release-verification.json` 记录实际应用 ID／版本／大小／APK 哈希／证书指纹。关闭终端清除本进程环境，不要导出环境到日志。配置缺失、密码／别名不对、debug 密钥或指纹不合都会失败，不退回其他签名。

### GitHub 构建（可选）

只在自己的可信任仓库设置；上传密钥到 GitHub 是你主动选择的保管副本，本任务没有替你上传。**Settings → Environments** 建立 `android-release`，支持时增加人工审批。为该环境添加 Secrets：

| Secret | 内容 |
| --- | --- |
| `AURORA_ANDROID_KEYSTORE_BASE64` | 同一 JKS 文件的 Base64，不是路径 |
| `AURORA_ANDROID_STORE_PASSWORD` | Keystore 密码 |
| `AURORA_ANDROID_KEY_ALIAS` | 同一别名 |
| `AURORA_ANDROID_KEY_PASSWORD` | 私钥密码 |

另加环境 Variable `AURORA_ANDROID_CERT_SHA256`，填同一公开指纹。Base64 不是加密，只能放 Secret。复制进 Secret 时避免终端输出或剪贴板同步／历史。

上传配置后，**Actions → Android signed APK → Run workflow** 选择已审核的 `main`。成功下载 `aurora-android-signed` Artifact，解压 APK 与校验 JSON。工作流不处理 PR、不自动发布 Releases／商店，也不上传密钥或 Gradle 缓存；只上传通过签名及实际清单校验的两个产物。实际 CI 执行仍须在仓库确认。

### 测试密钥

`npm run test:android-signing` 用忽略目录的一次性密钥，检查缺失／部分配置、错误指纹／密码、真实 debug 密钥拒绝，再构建并核验测试签名 APK。最后只删除本测试生成的两份 UUID 命名密钥。**测试生成的 release APK 不可分发**，不能替代你的正式构建或覆盖现有应用。该测试不运行手机安装／数据迁移。

## 繁體中文

固定簽章建立長期更新通道，不代表小米實機驗收完成。同一應用 ID／簽章及較高 `versionCode` 才能覆蓋；不同 CI 的 debug 簽章可能改變，新 release 金鑰通常不能覆蓋舊 debug。不要卸載／清資料來繞過衝突，先找原金鑰；資料匯出／遷移仍未完成。

已有正式金鑰就繼續使用。首次在 Android Studio **Build → Generate Signed Bundle/APK → APK → Create new** 建立，存放 `android/.signing/aurora-release.jks`，另存安全備份；別名可用 `aurora`。公開憑證不要填私人電話／地址，JKS 與密碼不可提交或傳給 AI。

沿用上方 PowerShell：四個簽章環境變數與公開 `AURORA_ANDROID_CERT_SHA256`，先同步 web，再 `:app:assembleRelease` 與 `android-release-check.cjs`。密碼安全互動輸入；缺失、錯誤、debug 金鑰及指紋不合都拒絕，無其他簽章回退。產物及 `release-verification.json` 在 `android/app/build/outputs/apk/release/`；關閉終端清除此次環境。

選用 GitHub：自己的可信任倉庫建立 `android-release` 環境，加入上表四個 Secrets 與指紋 Variable。JKS Base64 不是加密，不可當一般變數或貼進紀錄。執行 **Actions → Android signed APK** 的已審核 `main`，下載 `aurora-android-signed`，不自動公開。實際 CI 及覆蓋保留資料仍須驗證。

`test:android-signing` 只用可丟棄測試金鑰；測試產生的 release APK **不是正式版本、不可分發**，未操作使用者金鑰，亦未測裝置安裝／遷移。

## English

A fixed signing identity enables upgrades, not proof of Xiaomi acceptance. Keep application ID and signing key, and increment `versionCode`. Ephemeral CI debug keys are not a stable update channel. A new release key normally cannot update a debug installation. Do not uninstall/erase data to bypass a mismatch; find the original key. Data export/migration remains incomplete.

Reuse an existing release key. For a first key, Android Studio **Build → Generate Signed Bundle/APK → APK → Create new** can create `android/.signing/aurora-release.jks`; keep a separate secure backup. Use a public author identity, not private phone/address details. Never commit JKS/passwords or send them to an AI.

Use the PowerShell block above with JDK 21/SDK 36: four signing environment variables plus the public `AURORA_ANDROID_CERT_SHA256` fingerprint, obtained via interactive `keytool -list -v`. Sync web assets, assemble release, then run `android-release-check.cjs`. Passwords are prompted securely, not command arguments. Missing/bad configuration, debug keys or a different certificate fail closed. Both build and check must succeed. APK and `release-verification.json`: `android/app/build/outputs/apk/release/`. Close the terminal to discard its environment; do not log it.

Optional GitHub: create the protected `android-release` environment in your own trusted repository. Add the four Secrets in the table plus the fixed public fingerprint Variable. Base64 is not encryption. Run **Actions → Android signed APK** on reviewed `main`; download `aurora-android-signed`. No PR execution, automatic publication or key upload in artifacts. Actual CI execution and retained-data upgrades still need verification.

`test:android-signing` uses isolated disposable keys for refusal cases and actual APK/manifest checks, then removes only its UUID-named keys. The fixture-signed release APK is **not distributable**, not a replacement for your release build or a device migration test.

## Français

Une signature fixe permet les mises à jour, sans prouver la validation Xiaomi. Conservez identifiant d’application et clé, puis augmentez `versionCode`. Les clés debug éphémères de CI ne sont pas un canal stable. Une nouvelle clé release ne remplace normalement pas une installation debug. Ne désinstallez pas/n’effacez pas pour contourner un conflit ; retrouvez la clé initiale. Export/migration encore incomplets.

Réutilisez la clé existante. Pour la première, Android Studio **Build → Generate Signed Bundle/APK → APK → Create new** peut créer `android/.signing/aurora-release.jks` ; sauvegarde sécurisée séparée. Certificat public : identité d’auteur publique, pas de téléphone/adresse privés. Ne commettez pas JKS/mots de passe, ne les envoyez pas à une IA.

Bloc PowerShell ci-dessus, JDK 21/SDK 36 : quatre variables de signature et empreinte publique `AURORA_ANDROID_CERT_SHA256`, obtenue via `keytool -list -v` interactif. Synchronisez le web, compilez release puis `android-release-check.cjs`. Saisie sécurisée des mots de passe, aucun argument secret. Configuration incorrecte, clé debug ou empreinte différente : refus sans autre signature. Compilation et contrôle doivent réussir. APK et `release-verification.json` : `android/app/build/outputs/apk/release/`. Fermez le terminal pour éliminer son environnement sans le journaliser.

GitHub facultatif : environnement protégé `android-release` dans votre dépôt fiable. Quatre Secrets du tableau plus Variable d’empreinte publique fixe. Base64 n’est pas du chiffrement. **Actions → Android signed APK** sur `main` relu, puis télécharger `aurora-android-signed`. Aucun lancement pour PR, publication automatique ou clé dans les artefacts. CI réel et conservation des données sur appareil restent à vérifier.

`test:android-signing` utilise des clés jetables isolées, teste les refus et vérifie APK/manifeste, puis supprime ses seules clés UUID. APK release de test **non distribuable**, pas un remplacement du build de publication ni un test de migration sur appareil.

References: [Android app signing](https://developer.android.com/studio/publish/app-signing), [GitHub Actions Secrets](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets).
