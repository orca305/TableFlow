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
# 상태 DB(필수): 환경 변수가 없으면 개발용 컨테이너(compose.yaml의 state-db)와 .env 값을 쓴다.
if (-not $env:TABLEFLOW_STATE_DB_PASSWORD) {
    $configPath = Join-Path $projectRoot '.env'
    $line = if (Test-Path $configPath) { Get-Content -LiteralPath $configPath | Where-Object { $_ -match '^TABLEFLOW_STATE_DB_PASSWORD=' } | Select-Object -First 1 }
    if (-not $line) { throw 'Set TABLEFLOW_STATE_DB_PASSWORD in .env (see .env.example) and run: docker compose up -d --wait state-db' }
    $env:TABLEFLOW_STATE_DB_PASSWORD = ($line -split '=', 2)[1]
}
if (-not $env:TABLEFLOW_STATE_DB_URL) { $env:TABLEFLOW_STATE_DB_URL = 'jdbc:postgresql://127.0.0.1:15442/tableflow' }
if (-not $env:TABLEFLOW_STATE_DB_USER) { $env:TABLEFLOW_STATE_DB_USER = 'tableflow' }
Push-Location (Join-Path $projectRoot 'backend')
try { & .\gradlew.bat bootRun; if ($LASTEXITCODE -ne 0) { throw "Backend exited with code $LASTEXITCODE" } }
finally { Pop-Location }
