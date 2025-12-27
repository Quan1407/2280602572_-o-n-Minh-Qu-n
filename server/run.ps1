$ErrorActionPreference = "Stop"

Write-Host "Building Java server..." -ForegroundColor Cyan
Push-Location (Split-Path -Parent $MyInvocation.MyCommand.Path)

try {
  if (-not (Test-Path ".\out")) { New-Item -ItemType Directory -Path ".\out" | Out-Null }
  javac -encoding UTF-8 -d .\out .\Json.java .\Main.java

  if (-not $env:GEMINI_API_KEY) {
    Write-Host ""
    Write-Host "WARNING: GEMINI_API_KEY is not set. /api/chat will return 501 until you set it." -ForegroundColor Yellow
    Write-Host "Set it for this terminal:" -ForegroundColor Yellow
    Write-Host '  $env:GEMINI_API_KEY="YOUR_KEY_HERE"' -ForegroundColor Yellow
    Write-Host ""
  }

  Write-Host "Starting server on http://localhost:8000 ..." -ForegroundColor Green
  java -cp .\out Main
}
finally {
  Pop-Location
}


