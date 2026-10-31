[CmdletBinding()]
param(
    [Parameter(Position = 0, ValueFromRemainingArguments = $true)]
    [string[]]$Tasks = @('assembleDebug')
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$toolsRoot = Join-Path $projectRoot '.tools'
$localJdk = Get-ChildItem -LiteralPath $toolsRoot -Directory -Filter 'jdk-17*' -ErrorAction SilentlyContinue |
    Sort-Object Name -Descending | Select-Object -First 1

if ($localJdk) {
    $env:JAVA_HOME = $localJdk.FullName
}
if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/java.exe'))) {
    throw 'Java 17 is required. Install a JDK and set JAVA_HOME, or place it in .tools/jdk-17*.'
}

$localSdk = Join-Path $toolsRoot 'android-sdk'
if (Test-Path -LiteralPath (Join-Path $localSdk 'platforms/android-36/android.jar')) {
    $env:ANDROID_HOME = $localSdk
}
if (-not $env:ANDROID_HOME -or -not (Test-Path -LiteralPath $env:ANDROID_HOME)) {
    throw 'Android SDK 36 is required. Set ANDROID_HOME, or install it in .tools/android-sdk.'
}

$env:GRADLE_USER_HOME = Join-Path $toolsRoot 'gradle-user-home'
$env:PATH = (Join-Path $env:JAVA_HOME 'bin') + ';' + (Join-Path $env:ANDROID_HOME 'platform-tools') + ';' + $env:PATH

Push-Location $projectRoot
try {
    & (Join-Path $projectRoot 'gradlew.bat') @Tasks --console=plain
    $buildExitCode = $LASTEXITCODE
} finally {
    Pop-Location
}
exit $buildExitCode
