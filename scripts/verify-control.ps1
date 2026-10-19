param([string]$JavaHome, [switch]$Clean)
. "$PSScriptRoot/common.ps1"
Use-ProjectJava $JavaHome
$started = [DateTimeOffset]::UtcNow
$directory = "$script:ProjectRoot/artifacts/control"
New-Item -ItemType Directory -Force $directory | Out-Null
$reportFile = "$directory/report.json"
if (Test-Path -LiteralPath $reportFile) { Remove-Item -LiteralPath $reportFile -Force }
$goals = @('verify')
if ($Clean) { $goals = @('clean') + $goals }
Invoke-ProjectMaven $goals

$suites = @()
$total = 0
$newTests = 0
foreach ($path in Get-ChildItem "$script:ProjectRoot/gateway/target/surefire-reports" -Filter 'TEST-*.xml') {
    [xml]$xml = Get-Content -LiteralPath $path.FullName -Raw
    $suite = $xml.testsuite
    if ([int]$suite.tests -le 0 -or [int]$suite.failures -ne 0 -or [int]$suite.errors -ne 0 -or [int]$suite.skipped -ne 0) {
        throw "Incomplete or failed suite: $($path.Name)"
    }
    $total += [int]$suite.tests
    if ($suite.name.StartsWith('dev.robolink.gateway.control.')) {
        $newTests += [int]$suite.tests
        $suites += [ordered]@{
            name = [string]$suite.name; tests = [int]$suite.tests; failures = [int]$suite.failures
            errors = [int]$suite.errors; skipped = [int]$suite.skipped
            cases = @($suite.testcase | ForEach-Object { $_.name })
        }
    }
}
if ($suites.Count -ne 2) { throw 'Missing control test suites' }
[ordered]@{
    status = 'PASS'; started_at_utc = $started.ToString('o'); clean_build = [bool]$Clean
    duration_seconds = [math]::Round(([DateTimeOffset]::UtcNow - $started).TotalSeconds, 2)
    new_tests = $newTests; total_java_tests = $total; suites = $suites
    limits = @{ max_pending = 256; max_pending_plus_history = 1024; history_ttl_ms = 60000
        reliable_queue_per_client = 64; telemetry_slots_per_client = 64; control_task_queue = 8192
        max_clients = 32; stalled_write_timeout_ms = 3000; max_ws_message_bytes = 65536 }
    scope = 'Authenticated binary WebSocket/TCP bridge, command state machine and bounded output; no physical robot, motion simulation or Vue UI'
} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $reportFile -Encoding utf8
Write-Host "PASS: $newTests new control tests; $total Java tests total. Report: $reportFile"
