$ErrorActionPreference = 'Stop'
$projectRoot = Resolve-Path (Join-Path $PSScriptRoot '..')
$localJdk = Join-Path $projectRoot '.tooling\jdk-legacy\jdk-17.0.2+8'
$localAgent = Join-Path $projectRoot '.tooling\pipe-agent\pipe-fallback-agent.jar'
$localGradle = Join-Path $projectRoot '.tooling\gradle\gradle-8.13\bin\gradle.bat'
if ((Test-Path $localJdk) -and (Test-Path $localAgent) -and (Test-Path $localGradle)) {
    $env:JAVA_HOME = (Resolve-Path $localJdk).Path
    $agent = (Resolve-Path $localAgent).Path
    $env:JAVA_TOOL_OPTIONS = "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED `"-javaagent:$agent`""
    $env:Path = "$env:JAVA_HOME\bin;$env:Path"
    $gradle = (Resolve-Path $localGradle).Path
} else {
    if (-not $env:JAVA_HOME) { throw 'Install JDK 17 and set JAVA_HOME before running this script.' }
    $gradle = Join-Path $projectRoot 'gradlew.bat'
}
& $gradle ':shared:core:testDebugUnitTest' ':androidApp:testDebugUnitTest' ':androidApp:assembleDebug' ':androidApp:assembleDebugAndroidTest' '--no-daemon'
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
