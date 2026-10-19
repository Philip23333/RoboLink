param([string]$JavaHome, [switch]$Clean)
. "$PSScriptRoot/common.ps1"
Use-ProjectJava $JavaHome
$started = [DateTimeOffset]::UtcNow
$reportFile = "$script:ProjectRoot/artifacts/protocol/report.json"
# A failed attempt must not leave an older PASS report looking current.
if (Test-Path -LiteralPath $reportFile) { Remove-Item -LiteralPath $reportFile -Force }

if ($Clean) {
    Invoke-ProjectMaven @('clean')
    # Validate exact generated directory targets before recursive deletion.
    foreach ($relative in @('simulator/generated', 'console/src/generated', 'artifacts/protocol')) {
        $target = [IO.Path]::GetFullPath((Join-Path $script:ProjectRoot $relative))
        $prefix = [IO.Path]::GetFullPath($script:ProjectRoot).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
        if (-not $target.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
            throw "Refusing to clean outside workspace: $target"
        }
        if (Test-Path -LiteralPath $target) { Remove-Item -LiteralPath $target -Recurse -Force }
    }
}
& "$PSScriptRoot/generate-protocol.ps1"
Invoke-ProjectMaven @('verify', 'dependency:copy-dependencies')
Invoke-Checked 'npm.cmd' @('--prefix', "$script:ProjectRoot/console", 'run', 'typecheck')

$fixtureRoot = "$script:ProjectRoot/artifacts/protocol"
$javaClasspath = "$script:ProjectRoot/gateway/target/test-classes;$script:ProjectRoot/gateway/target/classes;$script:ProjectRoot/gateway/target/dependency/*"
$java = "$env:JAVA_HOME/bin/java.exe"
$python = "$script:ProjectRoot/.venv/Scripts/python.exe"
$tsx = "$script:ProjectRoot/console/node_modules/.bin/tsx.cmd"
$pythonFixture = "$script:ProjectRoot/simulator/scripts/protocol_fixture.py"
$tsFixture = "$script:ProjectRoot/console/scripts/protocol-fixture.ts"

Invoke-Checked $java @('-cp', $javaClasspath, 'dev.robolink.gateway.ProtocolFixture', 'encode', "$fixtureRoot/java")
Invoke-Checked $python @($pythonFixture, 'encode', "$fixtureRoot/python")
Invoke-Checked $tsx @($tsFixture, 'encode', "$fixtureRoot/typescript")

$pairs = @()
foreach ($encoder in @('java', 'python', 'typescript')) {
    $source = "$fixtureRoot/$encoder"
    Invoke-Checked $java @('-cp', $javaClasspath, 'dev.robolink.gateway.ProtocolFixture', 'verify', $source)
    Invoke-Checked $python @($pythonFixture, 'verify', $source)
    Invoke-Checked $tsx @($tsFixture, 'verify', $source)
    foreach ($decoder in @('java', 'python', 'typescript')) {
        $pairs += [ordered]@{ encoder = $encoder; decoder = $decoder; fixtures = 6; status = 'PASS' }
    }
}
$report = [ordered]@{
    status = 'PASS'
    started_at_utc = $started.ToString('o')
    duration_seconds = [math]::Round(([DateTimeOffset]::UtcNow - $started).TotalSeconds, 2)
    clean_generation = [bool]$Clean
    checks = @('Java build', 'TypeScript typecheck', 'uint64 precision', 'oneof branches')
    fixture_names = @('telemetry', 'command', 'control-auth', 'control-auth-result', 'command-trace', 'snapshot-diagnostics')
    seq = '18446744073709551615'
    timestamp_ms = '9007199254740993'
    pairs = $pairs
    scope = 'serialization only; no TCP, WebSocket, simulator motion or Vue UI'
}
$report | ConvertTo-Json -Depth 5 | Set-Content "$fixtureRoot/report.json" -Encoding utf8
Write-Host 'PASS: 9 encoder/decoder pairs x 6 fixtures = 54 message comparisons.'
Write-Host "Report: $fixtureRoot/report.json"
