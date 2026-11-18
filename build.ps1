param([switch]$SkipChecks)
$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    $localGradle = Join-Path $PSScriptRoot '.tools\gradle-8.11.1\bin\gradle.bat'
    $gradle = if (Test-Path -LiteralPath $localGradle) { $localGradle } else { Join-Path $PSScriptRoot 'gradlew.bat' }
    if (-not (Test-Path -LiteralPath 'local.properties') -and -not $env:ANDROID_HOME) {
        throw 'Android SDK not configured. Run: python tools/bootstrap.py'
    }
    $tasks = @(':app:assembleDebug')
    if (-not $SkipChecks) { $tasks += @(':app:testDebugUnitTest', ':app:lintDebug', ':app:writeProbeClasspath') }
    & $gradle --no-daemon @tasks
    if ($LASTEXITCODE -ne 0) { throw 'Android build or checks failed.' }
    $output = Join-Path $PSScriptRoot 'outputs'
    New-Item -ItemType Directory -Path $output -Force | Out-Null
    $metadata = Get-Content -LiteralPath 'app\build\outputs\apk\debug\output-metadata.json' -Raw | ConvertFrom-Json
    $apkName = 'OffworkPet-Receiver-v' + $metadata.elements[0].versionName + '.apk'
    Copy-Item -LiteralPath 'app\build\outputs\apk\debug\app-debug.apk' -Destination (Join-Path $output $apkName) -Force
    Get-FileHash -LiteralPath (Join-Path $output $apkName) -Algorithm SHA256
} finally { Pop-Location }
