param([string]$Apk = (Join-Path (Split-Path -Parent $PSScriptRoot) 'dist\SecureNotes-1.0.1.apk'))
$ErrorActionPreference = 'Stop'
$toolsDirectory = Join-Path $env:LOCALAPPDATA 'Android\Sdk\build-tools\37.0.0'
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
& (Join-Path $toolsDirectory 'apksigner.bat') verify --verbose --print-certs $Apk
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
$badging = & (Join-Path $toolsDirectory 'aapt2.exe') dump badging $Apk
if ($LASTEXITCODE -ne 0) { throw 'APK metadata could not be inspected.' }
if ($badging -match "uses-permission.*android.permission.(INTERNET|ACCESS_NETWORK_STATE|CAMERA|MANAGE_EXTERNAL_STORAGE|READ_EXTERNAL_STORAGE|WRITE_EXTERNAL_STORAGE)") {
    throw 'Unexpected network, camera, or broad storage permission.'
}
if ($badging -match 'application-debuggable') { throw 'Release APK is debuggable.' }
if (!($badging -match "sdkVersion:'35'")) { throw 'Unexpected minimum SDK.' }
if (!($badging -match "targetSdkVersion:'37'")) { throw 'Unexpected target SDK.' }
$manifest = & (Join-Path $toolsDirectory 'aapt2.exe') dump xmltree $Apk --file AndroidManifest.xml
if ($LASTEXITCODE -ne 0) { throw 'Manifest could not be inspected.' }
if (!($manifest -match 'allowBackup.*(=false|0x0+)$')) { throw 'Automatic app backup is not disabled.' }
if (!($manifest -match 'dataExtractionRules')) { throw 'Data extraction rules are missing.' }
if (!($manifest -match 'fullBackupContent')) { throw 'Backup exclusion rules are missing.' }
$badging | Select-String -Pattern '^package:|^sdkVersion:|^targetSdkVersion:|^uses-permission:|^native-code:'
Get-FileHash -LiteralPath $Apk -Algorithm SHA256
Write-Output 'Release signature, SDK, permissions, and backup declarations verified.'
