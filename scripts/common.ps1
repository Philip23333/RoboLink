$ErrorActionPreference = 'Stop'
$script:ProjectRoot = Split-Path $PSScriptRoot -Parent

function Invoke-Checked {
    param([string]$File, [string[]]$Arguments)
    & $File @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$File exited with code $LASTEXITCODE" }
}

function Use-ProjectJava {
    param([string]$JavaHome)
    if ($JavaHome) { $env:JAVA_HOME = $JavaHome }
    if (-not $env:JAVA_HOME) {
        $localJdk = Get-ChildItem "$script:ProjectRoot/.tools/java" -Directory -ErrorAction SilentlyContinue |
            Where-Object { Test-Path (Join-Path $_.FullName 'bin/java.exe') } |
            Sort-Object Name -Descending | Select-Object -First 1
        if ($localJdk) { $env:JAVA_HOME = $localJdk.FullName }
    }
    if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME/bin/javac.exe")) {
        throw 'Set JAVA_HOME to a JDK 21 installation, or pass -JavaHome.'
    }
    $env:PATH = "$env:JAVA_HOME/bin;$env:PATH"
}

function Invoke-ProjectMaven {
    param([string[]]$MavenArguments)
    # Keep dependency and wrapper downloads inside the project.
    $env:MAVEN_USER_HOME = "$script:ProjectRoot/.tools/maven-user"
    Push-Location "$script:ProjectRoot/gateway"
    try {
        Invoke-Checked "$script:ProjectRoot/gateway/mvnw.cmd" (@(
            '-B', '-ntp', "-Dmaven.repo.local=$script:ProjectRoot/.tools/m2"
        ) + $MavenArguments)
    } finally { Pop-Location }
}
