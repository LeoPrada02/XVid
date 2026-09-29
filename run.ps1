# Starts XVid on http://127.0.0.1:8000 (exposed to your phone through `tailscale serve`).
# Updates yt-dlp to the nightly build on every start, since X breaks it often.
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$venv = Join-Path $env:LOCALAPPDATA "XVid\venv"  # outside OneDrive so it doesn't get synced
$python = Join-Path $venv "Scripts\python.exe"

if (-not (Test-Path $python)) {
    python -m venv $venv
    & $python -m pip install -q -r requirements.txt
}
& $python -m pip install -q -U --pre "yt-dlp[default]"

& $python -m uvicorn app.main:app --host 127.0.0.1 --port 8000
