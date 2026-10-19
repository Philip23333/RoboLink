param([string]$JavaHome)
. "$PSScriptRoot/common.ps1"
Use-ProjectJava $JavaHome
Push-Location "$script:ProjectRoot/console"
try {
    Invoke-Checked 'npm.cmd' @('run', 'test')
    Invoke-Checked 'npm.cmd' @('run', 'build')
    Invoke-Checked 'npm.cmd' @('run', 'test:browser')
} finally { Pop-Location }
