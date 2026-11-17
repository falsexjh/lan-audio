param(
    [string]$FlutterRoot = $env:FLUTTER_ROOT,
    [string]$GradleUserHome = $env:LAN_AUDIO_GRADLE_USER_HOME,
    [string]$PubCache = '',
    [ValidateSet('android-arm64', 'android-arm', 'android-x64')]
    [string]$TargetPlatform = 'android-arm64'
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path "$PSScriptRoot\..").Path
$app = Join-Path $repo 'apps/android_flutter'
$flutter = if ($FlutterRoot) { Join-Path $FlutterRoot 'bin/flutter.bat' } else { 'flutter' }
$saved = @{}
foreach ($name in @('LAN_AUDIO_PERF_BUILD', 'GRADLE_USER_HOME', 'PUB_CACHE')) {
    $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

function Invoke-Checked([scriptblock]$Action) {
    & $Action
    if ($LASTEXITCODE -ne 0) { throw "Build step failed (exit $LASTEXITCODE)" }
}

Push-Location $app
try {
    # Release AOT/R8 optimization with a local test certificate and separate
    # package ID, so it can be installed alongside the upstream application.
    $env:LAN_AUDIO_PERF_BUILD = 'true'
    if ($GradleUserHome) { $env:GRADLE_USER_HOME = $GradleUserHome }
    if ($PubCache) { $env:PUB_CACHE = $PubCache }
    Invoke-Checked { & $flutter pub get }
    Invoke-Checked { & $flutter test }
    Invoke-Checked { & $flutter build apk --release --target-platform $TargetPlatform }
    Push-Location (Join-Path $app 'android')
    try { Invoke-Checked { & .\gradlew.bat :app:testReleaseUnitTest "-Ptarget-platform=$TargetPlatform" --console=plain } }
    finally { Pop-Location }
    $abi = @{ 'android-arm64' = 'arm64-v8a'; 'android-arm' = 'armeabi-v7a'; 'android-x64' = 'x86_64' }[$TargetPlatform]
    $output = Join-Path $repo 'artifacts/android'
    New-Item -ItemType Directory -Path $output -Force | Out-Null
    $apk = Join-Path $output "lan-audio-perf3-$abi.apk"
    Copy-Item -LiteralPath (Join-Path $app 'build/app/outputs/flutter-apk/app-release.apk') -Destination $apk -Force
    Get-FileHash -LiteralPath $apk -Algorithm SHA256
    Write-Output "Performance test APK: $apk"
}
finally {
    Pop-Location
    foreach ($name in $saved.Keys) {
        [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process')
    }
}
