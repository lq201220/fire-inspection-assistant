param([string]$ApiBaseUrl)
$ErrorActionPreference='Stop'
$project=Split-Path -Parent $PSScriptRoot
Set-Location $project
if (-not $ApiBaseUrl) {
    $localConfig=Join-Path $project '.env.android.local'
    if (Test-Path -LiteralPath $localConfig) {
        $configured=Get-Content -LiteralPath $localConfig | Where-Object { $_ -match '^VITE_API_BASE_URL=.+$' } | Select-Object -Last 1
        if ($configured) { $ApiBaseUrl=$configured.Substring('VITE_API_BASE_URL='.Length).Trim() }
    }
}
$apiUri=$null
if (-not [Uri]::TryCreate($ApiBaseUrl,[UriKind]::Absolute,[ref]$apiUri) -or $apiUri.Scheme -notin @('http','https') -or -not $apiUri.Host -or $apiUri.UserInfo -or $apiUri.Query -or $apiUri.Fragment -or $apiUri.AbsolutePath.TrimEnd('/') -ne '/api') {
    throw '请指定 -ApiBaseUrl https://你的域名/api，或在本机 .env.android.local 中设置 VITE_API_BASE_URL。'
}
$jdk=Get-ChildItem -LiteralPath "$project/.tools/java21" -Directory | Where-Object { Test-Path -LiteralPath "$($_.FullName)/bin/java.exe" } | Select-Object -First 1
if (-not $jdk) { throw '需要 Java 21：设置 .tools/java21 中的 JDK。' }
$env:JAVA_HOME=$jdk.FullName
$env:PATH="$env:JAVA_HOME/bin;$env:PATH"
$toolCache=Join-Path $env:USERPROFILE '.cache/fire-assistant-build'
$env:ANDROID_HOME=Join-Path $toolCache 'android-sdk'
if (-not (Test-Path -LiteralPath "$env:ANDROID_HOME/platforms/android-36/android.jar")) { throw '需要 Android SDK 36。' }
$env:GRADLE_USER_HOME=Join-Path $toolCache 'gradle-cache'
$env:VITE_API_BASE_URL=$ApiBaseUrl
$env:APP_WEB_URL=$apiUri.GetLeftPart([UriPartial]::Authority)+'/'
$sdkProperty='sdk.dir='+$env:ANDROID_HOME.Replace('\','/')
Set-Content -LiteralPath "$project/android/local.properties" -Value $sdkProperty -Encoding ascii
New-Item -ItemType Directory -Path "$project/.runtime/android-signing" -Force | Out-Null
$signingFile=Join-Path $project '.runtime/android-signing/signing.json'
if (-not (Test-Path -LiteralPath $signingFile)) {
    $signing=@{storePassword=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24));keyPassword=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))}
    $signing | ConvertTo-Json | Set-Content -LiteralPath $signingFile -Encoding utf8
}
$signing=Get-Content -LiteralPath $signingFile -Raw | ConvertFrom-Json
$env:ANDROID_KEYSTORE=Join-Path $project '.runtime/android-signing/fire-assistant.p12'
$env:ANDROID_STORE_PASSWORD=$signing.storePassword
# PKCS12 requires the private key and store to use the same password.
$env:ANDROID_KEY_PASSWORD=$signing.storePassword
if (-not (Test-Path -LiteralPath $env:ANDROID_KEYSTORE)) {
    & "$env:JAVA_HOME/bin/keytool.exe" -genkeypair -keystore $env:ANDROID_KEYSTORE -storetype PKCS12 -alias fire-assistant -keyalg RSA -keysize 3072 -validity 10000 -storepass:env ANDROID_STORE_PASSWORD -keypass:env ANDROID_KEY_PASSWORD -dname 'CN=Fire Assistant, OU=Application, O=Fire Assistant, C=CN'
    if ($LASTEXITCODE -ne 0) { throw '应用签名生成失败。' }
}
& npm run android:sync
if ($LASTEXITCODE -ne 0) { throw 'App 页面构建失败。' }
Push-Location android
try {
    & ./gradlew.bat --no-daemon --max-workers=2 assembleRelease
    if ($LASTEXITCODE -ne 0) { throw 'APK 构建失败。' }
} finally { Pop-Location }
New-Item -ItemType Directory -Path "$project/releases" -Force | Out-Null
$appGradle=Get-Content -LiteralPath "$project/android/app/build.gradle" -Raw
$versionMatch=[regex]::Match($appGradle,'versionName\s+"([0-9]+\.[0-9]+\.[0-9]+)"')
if (-not $versionMatch.Success) { throw '无法读取应用版本号。' }
$target=Join-Path $project ('releases/防消助手-'+$versionMatch.Groups[1].Value+'.apk')
Copy-Item -LiteralPath "$project/android/app/build/outputs/apk/release/app-release.apk" -Destination $target -Force
& "$env:ANDROID_HOME/build-tools/36.0.0/apksigner.bat" verify --verbose $target
if ($LASTEXITCODE -ne 0) { throw 'APK 签名校验失败。' }
Get-FileHash -LiteralPath $target -Algorithm SHA256
