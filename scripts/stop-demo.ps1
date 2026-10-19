. "$PSScriptRoot/common.ps1"
try {
    $state = Invoke-RestMethod 'http://127.0.0.1:19006/ready' -TimeoutSec 2
    if ($state.root -ne $script:ProjectRoot) { throw 'Refusing to stop a supervisor from another workspace.' }
    $null = Invoke-RestMethod -Method Post 'http://127.0.0.1:19006/stop' -TimeoutSec 3
    for ($attempt = 0; $attempt -lt 40; $attempt++) {
        if (-not (Test-Path "$script:ProjectRoot/artifacts/demo/ready.json")) {
            Write-Host 'RoboLink demo stopped.'
            return
        }
        Start-Sleep -Milliseconds 250
    }
    throw 'Shutdown still in progress; check artifacts/demo logs.'
} catch [System.Net.Http.HttpRequestException] {
    Write-Host 'No RoboLink demo supervisor is listening.'
}
