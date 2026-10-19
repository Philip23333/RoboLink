param([Parameter(Mandatory)][string]$CredentialFile,
      [string]$GatewayHost = '127.0.0.1', [int]$Port = 9000,
      [int]$Count = 3, [string]$IdPrefix = 'RB-', [double]$TelemetryHz = 10,
      [string]$FaultRobot, [double]$DisconnectAfter = 0, [double]$AckDelayMs = 0,
      [switch]$DropNextAck, [switch]$DropNextResult)
. "$PSScriptRoot/common.ps1"
$credentialPath = (Resolve-Path -LiteralPath $CredentialFile).Path
Push-Location "$script:ProjectRoot/simulator"
try {
    $simulatorArguments = @(
        '-m', 'robolink_sim', '--credential-file', $credentialPath,
        '--host', $GatewayHost, '--port', "$Port", '--count', "$Count",
        '--id-prefix', $IdPrefix, '--telemetry-hz', $TelemetryHz.ToString([cultureinfo]::InvariantCulture)
    )
    if ($FaultRobot) { $simulatorArguments += @('--fault-robot', $FaultRobot) }
    $simulatorArguments += @('--disconnect-after', $DisconnectAfter.ToString([cultureinfo]::InvariantCulture),
        '--ack-delay-ms', $AckDelayMs.ToString([cultureinfo]::InvariantCulture))
    if ($DropNextAck) { $simulatorArguments += '--drop-next-ack' }
    if ($DropNextResult) { $simulatorArguments += '--drop-next-result' }
    Invoke-Checked "$script:ProjectRoot/.venv/Scripts/python.exe" $simulatorArguments
} finally { Pop-Location }
