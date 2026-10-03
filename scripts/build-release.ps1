param(
    [string]$SigningDirectory = (Join-Path $env:USERPROFILE '.android\secure-notes-signing'),
    [string]$BuildRoot = (Join-Path $env:TEMP 'secure-notes-build'),
    [switch]$Initialize
)
$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path -Parent $PSScriptRoot
$signingPath = [IO.Path]::GetFullPath($SigningDirectory)
if ($signingPath.StartsWith([IO.Path]::GetFullPath($projectDirectory), [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Signing material must be stored outside the project.'
}
$keyFile = Join-Path $signingPath 'secure-notes-release.p12'
$credentialFile = Join-Path $signingPath 'credential.xml'
$javaDirectory = 'C:\Program Files\Android\Android Studio\jbr'
$env:JAVA_HOME = $javaDirectory
$env:PATH = "$javaDirectory\bin;$env:PATH"
$oldEnvironment = @{}
foreach ($name in @('NOTES_KEYSTORE','NOTES_STORE_PASSWORD','NOTES_KEY_ALIAS','NOTES_KEY_PASSWORD')) {
    $oldEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
try {
    if ($Initialize) {
        if ((Test-Path -LiteralPath $keyFile) -or (Test-Path -LiteralPath $credentialFile)) {
            throw 'Signing files already exist. Omit -Initialize to reuse them.'
        }
        New-Item -ItemType Directory -Path $signingPath -Force | Out-Null
        $randomBytes = New-Object byte[] 32
        $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
        try { $rng.GetBytes($randomBytes) } finally { $rng.Dispose() }
        $password = [Convert]::ToBase64String($randomBytes)
        $secret = ConvertTo-SecureString -String $password -AsPlainText -Force
        $credential = New-Object Management.Automation.PSCredential('secure-notes', $secret)
        $env:NOTES_STORE_PASSWORD = $password
        $env:NOTES_KEY_PASSWORD = $password
        & (Join-Path $javaDirectory 'bin\keytool.exe') -genkeypair -keystore $keyFile -storetype PKCS12 -alias secure-notes -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=Secure Notes Personal' -storepass:env NOTES_STORE_PASSWORD -keypass:env NOTES_KEY_PASSWORD
        if ($LASTEXITCODE -ne 0) { throw 'Signing key generation failed.' }
        $credential | Export-Clixml -LiteralPath $credentialFile
        [Array]::Clear($randomBytes, 0, $randomBytes.Length)
        $password = $null
    }
    if (!(Test-Path -LiteralPath $keyFile) -or !(Test-Path -LiteralPath $credentialFile)) {
        throw 'Signing material is missing. Run once with -Initialize.'
    }
    $credential = Import-Clixml -LiteralPath $credentialFile
    $env:NOTES_KEYSTORE = $keyFile
    $env:NOTES_KEY_ALIAS = 'secure-notes'
    $env:NOTES_STORE_PASSWORD = $credential.GetNetworkCredential().Password
    $env:NOTES_KEY_PASSWORD = $env:NOTES_STORE_PASSWORD
    Push-Location -LiteralPath $projectDirectory
    try {
        & '.\gradlew.bat' "-Pnotes.buildRoot=$BuildRoot" ':app:assembleRelease' ':app:lintRelease' '--console=plain'
        if ($LASTEXITCODE -ne 0) { throw 'Release build failed.' }
        $destination = Join-Path $projectDirectory 'dist'
        New-Item -ItemType Directory -Path $destination -Force | Out-Null
        Copy-Item -LiteralPath (Join-Path $BuildRoot 'app\outputs\apk\release\app-release.apk') -Destination (Join-Path $destination 'SecureNotes-1.0.1.apk')
        & (Join-Path $PSScriptRoot 'verify-apk.ps1') -Apk (Join-Path $destination 'SecureNotes-1.0.1.apk')
        Write-Output (Join-Path $destination 'SecureNotes-1.0.1.apk')
    } finally { Pop-Location }
} finally {
    foreach ($name in $oldEnvironment.Keys) { [Environment]::SetEnvironmentVariable($name, $oldEnvironment[$name], 'Process') }
    $credential = $null
}
