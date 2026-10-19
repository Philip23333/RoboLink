param([string]$Python = 'python', [string]$JavaHome)
. "$PSScriptRoot/common.ps1"
Use-ProjectJava $JavaHome
Push-Location $script:ProjectRoot
try {
    Invoke-Checked "$env:JAVA_HOME/bin/java.exe" @('-version')
    Invoke-Checked 'node' @('--version')
    if (-not (Test-Path '.venv/Scripts/python.exe')) {
        Invoke-Checked $Python @('-m', 'venv', '.venv')
    }
    Invoke-Checked '.venv/Scripts/python.exe' @(
        '-m', 'pip', 'install', '--index-url', 'https://pypi.org/simple',
        '--cache-dir', '.tools/pip-cache', '-r', 'simulator/requirements.txt'
    )
    Invoke-Checked 'npm.cmd' @(
        'ci', '--prefix', 'console', '--registry=https://registry.npmjs.org',
        '--cache', '.tools/npm-cache', '--no-audit', '--no-fund'
    )
    Write-Host 'Dependencies ready. Run scripts/verify-protocol.ps1 next.'
} finally { Pop-Location }
