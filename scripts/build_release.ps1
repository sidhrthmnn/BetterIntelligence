param(
    [Parameter(Mandatory = $true)][string]$SigningCredentials,
    [string[]]$Tasks = @(':app:bundleRelease')
)
$ErrorActionPreference = 'Stop'
$credentials = Get-Content -LiteralPath $SigningCredentials -Raw | ConvertFrom-Json
if ($credentials.alias -ne 'upload') { throw 'The release signing configuration expects alias upload.' }
if (-not (Test-Path -LiteralPath $credentials.keystore -PathType Leaf)) { throw 'Upload keystore was not found.' }
$securePassword = ConvertTo-SecureString $credentials.protectedPassword
$plainPassword = [System.Net.NetworkCredential]::new('', $securePassword).Password
$previousSigningEnvironment = @{}
foreach ($name in @('KEYSTORE_PATH', 'STORE_PASSWORD', 'KEY_PASSWORD')) {
    $previousSigningEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
try {
    $env:KEYSTORE_PATH = $credentials.keystore
    $env:STORE_PASSWORD = $plainPassword
    $env:KEY_PASSWORD = $plainPassword
    Push-Location (Split-Path $PSScriptRoot -Parent)
    try {
        & .\gradlew.bat @Tasks --no-configuration-cache
        $buildExitCode = $LASTEXITCODE
    } finally { Pop-Location }
} finally {
    foreach ($name in $previousSigningEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $previousSigningEnvironment[$name], 'Process')
    }
    $plainPassword = $null
}
exit $buildExitCode
