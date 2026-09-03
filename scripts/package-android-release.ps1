$ErrorActionPreference = 'Stop'

$projectRoot = Resolve-Path (Join-Path $PSScriptRoot '..')
$propertiesFile = Join-Path $projectRoot 'release-private\keystore.properties'
if (-not (Test-Path $propertiesFile)) {
    throw 'Missing release-private/keystore.properties. Do not create a new key when preparing an upgrade.'
}

& (Join-Path $PSScriptRoot 'test-windows.ps1')
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$localJdk = Join-Path $projectRoot '.tooling\jdk-legacy\jdk-17.0.2+8'
$localAgent = Join-Path $projectRoot '.tooling\pipe-agent\pipe-fallback-agent.jar'
$localGradle = Join-Path $projectRoot '.tooling\gradle\gradle-8.13\bin\gradle.bat'
$localSdk = Join-Path $projectRoot '.tooling\android-sdk'
if ((Test-Path $localJdk) -and (Test-Path $localAgent) -and (Test-Path $localGradle) -and (Test-Path $localSdk)) {
    $env:JAVA_HOME = (Resolve-Path $localJdk).Path
    $env:ANDROID_HOME = (Resolve-Path $localSdk).Path
    $agent = (Resolve-Path $localAgent).Path
    $env:JAVA_TOOL_OPTIONS = "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED `"-javaagent:$agent`""
    $gradle = (Resolve-Path $localGradle).Path
} else {
    $gradle = Join-Path $projectRoot 'gradlew.bat'
}

& $gradle ':androidApp:lintRelease' ':androidApp:assembleRelease' '--no-daemon' '--stacktrace'
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$sourceApk = Join-Path $projectRoot 'apps\android\app\build\outputs\apk\release\androidApp-release.apk'
if (-not (Test-Path $sourceApk)) { throw 'Signed release APK was not produced.' }

$dist = Join-Path $projectRoot 'dist'
New-Item -ItemType Directory -Force -Path $dist | Out-Null
$assetName = 'HealthTimeline-1.2.0.apk'
$assetPath = Join-Path $dist $assetName
Copy-Item -LiteralPath $sourceApk -Destination $assetPath -Force
$hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $assetPath).Hash.ToLowerInvariant()
[System.IO.File]::WriteAllText((Join-Path $dist 'SHA256SUMS.txt'), "$hash  $assetName`n", [System.Text.UTF8Encoding]::new($false))

Write-Host "Release asset: $assetPath"
Write-Host "SHA-256: $hash"
