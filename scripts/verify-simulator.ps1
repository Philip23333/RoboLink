param([string]$JavaHome)
. "$PSScriptRoot/common.ps1"
Use-ProjectJava $JavaHome
$reportFile = "$script:ProjectRoot/artifacts/simulator/report.json"
if (Test-Path -LiteralPath $reportFile) { Remove-Item -LiteralPath $reportFile -Force }
# Generate protocol classes and run existing Java tests before launching the real main.
Invoke-ProjectMaven @('package', 'dependency:copy-dependencies', '-DincludeScope=runtime')
Invoke-Checked "$script:ProjectRoot/.venv/Scripts/python.exe" @("$script:ProjectRoot/simulator/tests/verify.py")
