param([string]$JavaHome)
. "$PSScriptRoot/common.ps1"
Use-ProjectJava $JavaHome

# Maven obtains the pinned protoc executable and generates Java from ../protocol.
Invoke-ProjectMaven @('generate-sources')
$protoc = "$script:ProjectRoot/gateway/target/protoc-plugins/protoc-4.33.5-windows-x86_64.exe"
if (-not (Test-Path $protoc)) { throw 'This orchestration script currently supports Windows x64 only.' }
$pythonOut = "$script:ProjectRoot/simulator/generated"
$tsOut = "$script:ProjectRoot/console/src/generated"
New-Item -ItemType Directory -Force $pythonOut, $tsOut | Out-Null
$plugin = "$script:ProjectRoot/console/node_modules/.bin/protoc-gen-ts_proto.cmd"
if (-not (Test-Path $plugin)) { throw 'Run scripts/setup.ps1 before generating protocol code.' }
Invoke-Checked $protoc @(
    "--proto_path=$script:ProjectRoot/protocol",
    "--python_out=$pythonOut",
    "--plugin=protoc-gen-ts_proto=$plugin",
    "--ts_proto_out=$tsOut",
    '--ts_proto_opt=forceLong=bigint,oneof=unions-value,env=browser,outputServices=none',
    'robolink.proto'
)
Write-Host 'Java / Python / TypeScript generation PASS'
