# Shared by setup.ps1, run.ps1 and stop.cmd.

$XVidData = Join-Path $env:LOCALAPPDATA "XVid"
$Venv = Join-Path $XVidData "venv"  # outside the repo (and OneDrive) so it isn't synced
$VenvPython = Join-Path $Venv "Scripts\python.exe"
$LogFile = Join-Path $XVidData "xvid.log"

function Get-XVidProcesses {
    Get-CimInstance Win32_Process -Filter "Name = 'python.exe'" | Where-Object {
        $_.CommandLine -match 'XVid\\venv\\Scripts\\python\.exe"?\s+-m\s+(app\b|uvicorn\s+app\.main:app)'
    }
}

function Stop-XVid {
    foreach ($p in @(Get-XVidProcesses)) {
        cmd /c "taskkill /T /F /PID $($p.ProcessId) >nul 2>&1"  # /T also stops its child process
    }
}
