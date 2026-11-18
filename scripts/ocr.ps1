param([ValidateSet('mock', 'local', 'external')] [string] $Provider = 'mock')
$ErrorActionPreference = 'Stop'
$serviceRoot = Join-Path (Split-Path $PSScriptRoot -Parent) 'ai-service'
$pythonPath = Join-Path $serviceRoot '.venv\Scripts\python.exe'
if (-not (Test-Path $pythonPath)) { throw 'Create ai-service/.venv and install requirements.txt first.' }
$env:TABLEFLOW_OCR_PROVIDER = $Provider
Push-Location $serviceRoot
try { & $pythonPath -m uvicorn app.main:app --host 127.0.0.1 --port 8000; if ($LASTEXITCODE -ne 0) { throw 'OCR service failed.' } }
finally { Pop-Location }
