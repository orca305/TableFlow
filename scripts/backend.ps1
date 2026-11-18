$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$validJdk = $env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\release") -and
    (Select-String -LiteralPath "$env:JAVA_HOME\release" -Pattern '^JAVA_VERSION="21[.\"]' -Quiet)
if (-not $validJdk) {
    $localJdk = Join-Path $env:USERPROFILE '.jdks\openjdk-21.0.2'
    if (Test-Path "$localJdk\bin\java.exe") { $env:JAVA_HOME = $localJdk }
    else { throw 'Set JAVA_HOME to a JDK 21 installation.' }
}
if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to a JDK 21 installation.' }
Push-Location (Join-Path $projectRoot 'backend')
try { & .\gradlew.bat bootRun; if ($LASTEXITCODE -ne 0) { throw "Backend exited with code $LASTEXITCODE" } }
finally { Pop-Location }
