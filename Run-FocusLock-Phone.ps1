$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$android = Join-Path $root "android"
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
$java = "C:\Users\krith\Documents\Codex\2026-10-10\stage-1-build-the-foundation-help\work\jdk17\jdk-17.0.20.1+1"

if (-not (Test-Path $adb)) { throw "ADB not found at $adb. Install Android SDK Platform-Tools or update this path." }
if (-not (Test-Path $android)) { throw "Android project not found at $android." }
if (Test-Path $java) { $env:JAVA_HOME = $java; $env:Path = "$java\bin;$env:Path" }

& $adb start-server | Out-Null
$device = $null
Write-Host "Connect one Android phone, unlock it, and accept the USB debugging prompt."
while (-not $device) {
    $rows = @(& $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "^\S+\s+(device|unauthorized|offline)$" })
    if ($rows.Count -gt 1) { throw "More than one Android device is connected. Disconnect extras and run again." }
    if ($rows.Count -eq 1) {
        $parts = $rows[0] -split "\s+"
        if ($parts[1] -eq "device") { $device = $parts[0]; break }
        Write-Host "Device $($parts[0]) is $($parts[1]). Unlock the phone and accept USB debugging; checking again..."
    } else { Write-Host "No authorized device detected; checking again..." }
    Start-Sleep -Seconds 3
}

& $adb -s $device reverse --remove-all | Out-Null
& $adb -s $device reverse tcp:8000 tcp:8000 | Out-Null
Push-Location $android
try {
    .\gradlew.bat installDebug -PAPI_BASE_URL=http://127.0.0.1:8000
    if ($LASTEXITCODE -ne 0) { throw "Android build/install failed with exit code $LASTEXITCODE." }
} finally { Pop-Location }

& $adb -s $device shell am force-stop org.focuslock.app
& $adb -s $device shell am start -n org.focuslock.app/.MainActivity | Out-Host
Write-Host "FocusLock launched on $device."
