param([string]$JavaHome, [switch]$Clean)
. "$PSScriptRoot/common.ps1"
Use-ProjectJava $JavaHome
$started = [DateTimeOffset]::UtcNow
$outputDirectory = "$script:ProjectRoot/artifacts/session"
New-Item -ItemType Directory -Force $outputDirectory | Out-Null
$reportFile = "$outputDirectory/report.json"
if (Test-Path -LiteralPath $reportFile) { Remove-Item -LiteralPath $reportFile -Force }
$goals = @('verify')
if ($Clean) { $goals = @('clean') + $goals }
Invoke-ProjectMaven $goals

$suites = @()
$total = 0
foreach ($name in @('DeviceSessionTest', 'GatewayLifecycleTest')) {
    $path = "$script:ProjectRoot/gateway/target/surefire-reports/TEST-dev.robolink.gateway.session.$name.xml"
    if (-not (Test-Path -LiteralPath $path)) { throw "Missing test suite: $name" }
    [xml]$xml = Get-Content -LiteralPath $path -Raw
    $suite = $xml.testsuite
    if ([int]$suite.tests -le 0 -or [int]$suite.failures -ne 0 -or [int]$suite.errors -ne 0 -or [int]$suite.skipped -ne 0) {
        throw "Incomplete or failed suite: $name"
    }
    $total += [int]$suite.tests
    $suites += [ordered]@{
        name = $name; tests = [int]$suite.tests; failures = [int]$suite.failures
        errors = [int]$suite.errors; skipped = [int]$suite.skipped
        cases = @($suite.testcase | ForEach-Object { $_.name })
    }
}
[ordered]@{
    status = 'PASS'; started_at_utc = $started.ToString('o'); clean_build = [bool]$Clean
    duration_seconds = [math]::Round(([DateTimeOffset]::UtcNow - $started).TotalSeconds, 2)
    new_tests = $total; suites = $suites
    scope = 'Device authentication, session replacement, liveness and actual TCP server lifecycle; no command routing or UI'
} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $reportFile -Encoding utf8
Write-Host "PASS: $total session/lifecycle tests. Report: $reportFile"
