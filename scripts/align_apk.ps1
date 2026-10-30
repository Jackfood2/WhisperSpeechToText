# Aligns an APK's uncompressed native libraries to 16 KB and re-signs it.
#
# AGP 8.2 does not align uncompressed .so files, so `zipalign -c -P 16` fails and
# Android shows the "This app isn't 16 KB-compatible" dialog. zipalign rewrites
# the archive (which invalidates the signature), so the APK must be re-signed
# afterwards; alignment survives apksigner.
#
# Usage:
#   .\scripts\align_apk.ps1 -Apk app\build\outputs\apk\debug\app-debug.apk `
#                           -Out app\build\outputs\apk\debug\app-debug-aligned.apk `
#                           -Keystore "$env:USERPROFILE\.android\debug.keystore" `
#                           -StorePass android -KeyAlias androiddebugkey -KeyPass android

param(
    [Parameter(Mandatory = $true)][string]$Apk,
    [string]$Out,
    [Parameter(Mandatory = $true)][string]$Keystore,
    [string]$StorePass = "android",
    [string]$KeyAlias = "androiddebugkey",
    [string]$KeyPass = "android"
)

$ErrorActionPreference = "Stop"

$btRoot = "C:\Android\Sdk\build-tools"
$bt = Get-ChildItem $btRoot -Directory |
    Sort-Object { [int]($_.Name -split '\.')[0] } -Descending |
    Select-Object -First 1
$zipalign = Join-Path $bt.FullName "zipalign.exe"
$apksigner = Join-Path $bt.FullName "apksigner.bat"

if (-not $Out) {
    $dir = Split-Path $Apk -Parent
    $name = [IO.Path]::GetFileNameWithoutExtension($Apk)
    $Out = Join-Path $dir "$name-aligned.apk"
}

& $zipalign -f -P 16 4 $Apk $Out
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

& $apksigner sign --ks $Keystore --ks-pass "pass:$StorePass" `
    --ks-key-alias $KeyAlias --key-pass "pass:$KeyPass" $Out
if ($LASTEXITCODE -ne 0) { throw "apksigner sign failed" }

& $zipalign -c -P 16 4 $Out
if ($LASTEXITCODE -ne 0) { throw "APK is still not 16 KB aligned after signing" }

Write-Host "OK: $Out"
