# Starts XVid (run setup.cmd once first). If XVid is already running, it restarts it.
# Updates yt-dlp to the nightly build on every start, since X breaks it often.
param(
    [switch]$Background  # no console output; logs go to %LOCALAPPDATA%\XVid\xvid.log (used at Windows startup)
)
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
. "$PSScriptRoot\tools\common.ps1"

if (-not (Test-Path $VenvPython)) {
    Write-Host "XVid isn't set up yet. Run setup.cmd first."
    exit 1
}

Stop-XVid
& $VenvPython -m pip install -q -U --pre "yt-dlp[default]"

if ($Background) {
    & $VenvPython -m app --log $LogFile
} else {
    & $VenvPython -m app
}
