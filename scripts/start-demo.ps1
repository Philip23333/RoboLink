param([string]$JavaHome)
. "$PSScriptRoot/common.ps1"
Use-ProjectJava $JavaHome
try {
    $existing = Invoke-RestMethod 'http://127.0.0.1:19006/ready' -TimeoutSec 1
    if ($existing.ready -and $existing.root -eq $script:ProjectRoot) { Write-Host 'RoboLink is already running: http://127.0.0.1:5173'; return }
} catch { }
if (-not (Test-Path "$script:ProjectRoot/.venv/Scripts/python.exe") -or
    -not (Test-Path "$script:ProjectRoot/console/node_modules/vite/bin/vite.js")) {
    throw 'Run scripts/setup.ps1 first to prepare Python and Node dependencies.'
}
$protocolTime = (Get-Item "$script:ProjectRoot/protocol/robolink.proto").LastWriteTimeUtc
$needsGeneration = @('simulator/generated/robolink_pb2.py', 'console/src/generated/robolink.ts') | Where-Object {
    -not (Test-Path "$script:ProjectRoot/$_") -or (Get-Item "$script:ProjectRoot/$_").LastWriteTimeUtc -lt $protocolTime
}
if ($needsGeneration) {
    & "$PSScriptRoot/generate-protocol.ps1"
}
$mainClass = "$script:ProjectRoot/gateway/target/classes/dev/robolink/gateway/GatewayMain.class"
$needsBuild = -not (Test-Path $mainClass)
if (-not $needsBuild) {
    $buildTime = (Get-Item $mainClass).LastWriteTimeUtc
    $needsBuild = $protocolTime -gt $buildTime -or (Get-Item "$script:ProjectRoot/gateway/pom.xml").LastWriteTimeUtc -gt $buildTime -or
        @(Get-ChildItem "$script:ProjectRoot/gateway/src" -Recurse -File | Where-Object { $_.LastWriteTimeUtc -gt $buildTime }).Count -gt 0
}
if ($needsBuild -or
    -not (Test-Path "$script:ProjectRoot/gateway/target/dependency/netty-transport-4.1.135.Final.jar")) {
    Invoke-ProjectMaven @('package', 'dependency:copy-dependencies', '-DincludeScope=runtime')
}
$demoDirectory = "$script:ProjectRoot/artifacts/demo"
New-Item -ItemType Directory -Force $demoDirectory | Out-Null
$demoProcess = Start-Process -FilePath "$script:ProjectRoot/.venv/Scripts/python.exe" `
    -ArgumentList @('"' + "$PSScriptRoot/demo.py" + '"') -WorkingDirectory $script:ProjectRoot `
    -WindowStyle Hidden -RedirectStandardOutput "$demoDirectory/stdout.log" `
    -RedirectStandardError "$demoDirectory/stderr.log" -PassThru
for ($attempt = 0; $attempt -lt 80; $attempt++) {
    if ($demoProcess.HasExited) { throw 'Demo did not start; see artifacts/demo/stderr.log.' }
    try {
        $state = Invoke-RestMethod 'http://127.0.0.1:19006/ready' -TimeoutSec 1
        if ($state.ready -and $state.root -eq $script:ProjectRoot) { Write-Host 'RoboLink ready: http://127.0.0.1:5173'; return }
    } catch { }
    Start-Sleep -Milliseconds 250
}
throw 'Demo startup timed out; see artifacts/demo/stderr.log.'
