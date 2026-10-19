param([Parameter(Mandatory)][string]$CredentialFile,
      [string]$GatewayHost = '127.0.0.1', [int]$Port = 9000,
      [int]$Count = 3, [string]$IdPrefix = 'RB-', [double]$TelemetryHz = 10)
. "$PSScriptRoot/common.ps1"
$credentialPath = (Resolve-Path -LiteralPath $CredentialFile).Path
Push-Location "$script:ProjectRoot/simulator"
try {
    Invoke-Checked "$script:ProjectRoot/.venv/Scripts/python.exe" @(
        '-m', 'robolink_sim', '--credential-file', $credentialPath,
        '--host', $GatewayHost, '--port', "$Port", '--count', "$Count",
        '--id-prefix', $IdPrefix, '--telemetry-hz', $TelemetryHz.ToString([cultureinfo]::InvariantCulture)
    )
} finally { Pop-Location }
