# Dot-source this file before starting the backend: . .\scripts\enable-local-db.ps1
$ErrorActionPreference = 'Stop'
$rootPath = Split-Path $PSScriptRoot -Parent
$configPath = Join-Path $rootPath '.env'
if (-not (Test-Path $configPath)) { throw 'Copy .env.example to .env and replace the passwords first.' }
$passwordLine = Get-Content -LiteralPath $configPath | Where-Object { $_ -match '^TABLEFLOW_MYSQL_PASSWORD=' } | Select-Object -First 1
if (-not $passwordLine) { throw 'TABLEFLOW_MYSQL_PASSWORD is missing in .env.' }
$env:TABLEFLOW_LOAD_ENABLED='true'
$env:TABLEFLOW_LOAD_URL='jdbc:mysql://localhost:3307/tableflow'
$env:TABLEFLOW_LOAD_USER='tableflow'
$env:TABLEFLOW_LOAD_PASSWORD=($passwordLine -split '=',2)[1]
$env:TABLEFLOW_LOAD_TABLES='TB_EMPLOYEE'
# Encrypt localhost traffic. Remote deployments must verify their trusted certificate/hostname.
$env:TABLEFLOW_LOAD_SSL_MODE='REQUIRED'
