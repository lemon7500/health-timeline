param(
    [Parameter(Mandatory = $true)][string]$KeytoolPath
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$privateDir = Join-Path $projectRoot 'release-private'
$keystorePath = Join-Path $privateDir 'healthtimeline-release.jks'
$propertiesPath = Join-Path $privateDir 'keystore.properties'

if (Test-Path -LiteralPath $keystorePath) {
    Write-Host 'Release keystore already exists; leaving it unchanged.'
    exit 0
}

New-Item -ItemType Directory -Path $privateDir -Force | Out-Null
$passwordBytes = New-Object byte[] 24
[Security.Cryptography.RandomNumberGenerator]::Fill($passwordBytes)
$password = [Convert]::ToBase64String($passwordBytes).Replace('+','A').Replace('/','B').Replace('=','C')

& $KeytoolPath -genkeypair -v -keystore $keystorePath -storepass $password -keypass $password `
    -alias healthtimeline -keyalg RSA -keysize 4096 -validity 10000 `
    -dname 'CN=Health Timeline, OU=Personal Health, O=HealthTimeline, L=Local, C=CN'
if ($LASTEXITCODE -ne 0) { throw 'keytool failed' }

$relativeStore = 'release-private/healthtimeline-release.jks'
@(
    "storeFile=$relativeStore"
    "storePassword=$password"
    'keyAlias=healthtimeline'
    "keyPassword=$password"
) | Set-Content -LiteralPath $propertiesPath -Encoding UTF8

Write-Host "Created release keystore at $keystorePath"
Write-Host 'Back up the entire release-private directory securely. Losing it prevents future in-place upgrades.'
