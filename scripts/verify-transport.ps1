param([string]$JavaHome, [switch]$Clean)
. "$PSScriptRoot/common.ps1"
Use-ProjectJava $JavaHome
$started = [DateTimeOffset]::UtcNow
$outputDirectory = "$script:ProjectRoot/artifacts/transport"
New-Item -ItemType Directory -Force $outputDirectory | Out-Null
$reportFile = "$outputDirectory/report.json"
if (Test-Path -LiteralPath $reportFile) { Remove-Item -LiteralPath $reportFile -Force }

$goals = @('verify')
if ($Clean) { $goals = @('clean') + $goals }
Invoke-ProjectMaven $goals

$suites = @()
$totalTests = 0
foreach ($name in @('TcpEnvelopeCodecTest', 'TcpLoopbackTest')) {
    $path = "$script:ProjectRoot/gateway/target/surefire-reports/TEST-dev.robolink.gateway.transport.$name.xml"
    if (-not (Test-Path -LiteralPath $path)) { throw "Missing test report: $name" }
    [xml]$xml = Get-Content -LiteralPath $path -Raw
    $suite = $xml.testsuite
    if ([int]$suite.tests -le 0 -or [int]$suite.failures -ne 0 -or [int]$suite.errors -ne 0 -or [int]$suite.skipped -ne 0) {
        throw "Incomplete or failed suite: $name"
    }
    $totalTests += [int]$suite.tests
    $suites += [ordered]@{
        name = $name
        tests = [int]$suite.tests
        failures = [int]$suite.failures
        errors = [int]$suite.errors
        skipped = [int]$suite.skipped
        cases = @($suite.testcase | ForEach-Object { $_.name })
    }
}
$report = [ordered]@{
    status = 'PASS'
    started_at_utc = $started.ToString('o')
    duration_seconds = [math]::Round(([DateTimeOffset]::UtcNow - $started).TotalSeconds, 2)
    clean_build = [bool]$Clean
    total_tests = $totalTests
    suites = $suites
    random_streams = @{ seeds = 20; messages_per_seed = 100; min_chunk_bytes = 1; max_chunk_bytes = 300 }
    limits = @{ header_bytes = 4; max_body_bytes = 65536; max_frame_bytes = 65540 }
    scope = 'Netty codec and localhost TCP test fixture; no device registration, heartbeat, routing, WebSocket or UI'
}
$report | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $reportFile -Encoding utf8
Write-Host "PASS: $($report.total_tests) transport tests. Report: $reportFile"
