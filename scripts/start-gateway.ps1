param([string]$JavaHome, [string]$CredentialFile, [Nullable[int]]$Port,
      [string]$ControlCredentialFile, [Nullable[int]]$ControlPort)
. "$PSScriptRoot/common.ps1"
Use-ProjectJava $JavaHome
if ($CredentialFile) {
    $env:ROBOLINK_CREDENTIALS_FILE = (Resolve-Path -LiteralPath $CredentialFile).Path
}
if (-not $env:ROBOLINK_CREDENTIALS_FILE) {
    throw 'Pass -CredentialFile pointing to a local device credentials properties file.'
}
if ($null -ne $Port) { $env:ROBOLINK_PORT = $Port.ToString() }
if ($ControlCredentialFile) {
    $env:ROBOLINK_CONTROL_CREDENTIALS_FILE = (Resolve-Path -LiteralPath $ControlCredentialFile).Path
}
if ($null -ne $ControlPort) { $env:ROBOLINK_CONTROL_PORT = $ControlPort.ToString() }
Invoke-ProjectMaven @('package', 'dependency:copy-dependencies', '-DincludeScope=runtime')
$classpath = "$script:ProjectRoot/gateway/target/classes;$script:ProjectRoot/gateway/target/dependency/*"
Invoke-Checked "$env:JAVA_HOME/bin/java.exe" @('-cp', $classpath, 'dev.robolink.gateway.GatewayMain')
