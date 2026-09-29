# XVid setup. Double-click setup.cmd to run it. Safe to run again at any time
# (for example after the PC's Wi-Fi address changes, or to change your answers).
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
. "$PSScriptRoot\tools\common.ps1"

function Step($title) { Write-Host "`n== $title" -ForegroundColor Cyan }

function Ask($question, $default = $true) {
    $hint = if ($default) { "[Y/n]" } else { "[y/N]" }
    $answer = Read-Host "$question $hint"
    if ([string]::IsNullOrWhiteSpace($answer)) { return $default }
    return $answer.Trim() -match '^(y|yes|s|si)$'
}

function Have($command) { [bool](Get-Command $command -ErrorAction SilentlyContinue) }

function Check($what) {
    if ($LASTEXITCODE -ne 0) { throw "Something went wrong $what (exit code $LASTEXITCODE)." }
}

function Update-PathFromRegistry {
    # winget installs update PATH in the registry; reload it so this window sees the new programs.
    $env:Path = [Environment]::GetEnvironmentVariable("Path", "Machine") + ";" +
                [Environment]::GetEnvironmentVariable("Path", "User") + ";" +
                (Join-Path $env:LOCALAPPDATA "Microsoft\WinGet\Links")
}

function Install-Package($id, $name) {
    if (-not (Have "winget")) {
        throw "Can't install $name automatically: winget is missing. Install 'App Installer' from the Microsoft Store, or install $name yourself, then run setup again."
    }
    Write-Host "Installing $name..."
    winget install --id $id -e --silent --accept-source-agreements --accept-package-agreements
    Update-PathFromRegistry
}

function Find-Python {
    # "py" is the official launcher; "python" may be the Microsoft Store placeholder, so test that it really runs.
    $ErrorActionPreference = "Continue"
    $candidates = @(@{ Exe = "py"; Args = @("-3") }, @{ Exe = "python"; Args = @() })
    foreach ($c in $candidates) {
        if (-not (Have $c.Exe)) { continue }
        $pyArgs = $c.Args
        $path = & $c.Exe @pyArgs -c "import sys; print(sys.executable if sys.version_info >= (3, 10) else '')" 2>$null
        if ($LASTEXITCODE -eq 0 -and $path) { return ([string]$path).Trim() }
    }
    return $null
}

function Wait-Port($port, $seconds) {
    $deadline = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $deadline) {
        $client = New-Object Net.Sockets.TcpClient
        try { $client.Connect("127.0.0.1", $port); return $true }
        catch { Start-Sleep -Milliseconds 500 }
        finally { $client.Dispose() }
    }
    return $false
}

Write-Host "XVid setup" -ForegroundColor Cyan
Write-Host "Press Enter to accept the default answer (the capital letter)."

# ---------------------------------------------------------------- Python
Step "Python"
$python = Find-Python
if (-not $python) {
    Install-Package "Python.Python.3.13" "Python"
    $python = Find-Python
    if (-not $python) { throw "Python was installed but this window can't see it yet. Close it and run setup.cmd again." }
}
Write-Host "Using $python"

# ---------------------------------------------------------------- packages
Step "XVid's Python packages"
if (-not (Test-Path $VenvPython)) {
    & $python -m venv $Venv
    Check "creating the Python environment"
}
& $VenvPython -m pip install -q --disable-pip-version-check -r requirements.txt
Check "installing packages"
& $VenvPython -m pip install -q --disable-pip-version-check -U --pre "yt-dlp[default]"
Check "installing yt-dlp"
Write-Host "Done."

# ---------------------------------------------------------------- ffmpeg
Step "ffmpeg (best video quality)"
if (Have "ffmpeg") {
    Write-Host "Already installed."
} elseif (Ask "Install ffmpeg? Some videos only download in the best quality with it (~100 MB)." $true) {
    Install-Package "Gyan.FFmpeg" "ffmpeg"
}

# ---------------------------------------------------------------- phone access
Step "Phone access over your Wi-Fi"
$phone = Ask "Set up phone access? (needed to use XVid from your phone)" $true
if ($phone) {
    if (-not (Have "mkcert")) { Install-Package "FiloSottile.mkcert" "mkcert" }
    if (-not (Have "mkcert")) { throw "mkcert was installed but this window can't see it yet. Close it and run setup.cmd again." }
    & $VenvPython tools\setup_https.py
    Check "creating the certificate"

    # Changes that need administrator rights, collected so Windows asks only once.
    $adminCommands = @()
    $rule = Get-NetFirewallRule -DisplayName "XVid" -ErrorAction SilentlyContinue
    $ports = if ($rule) { @(($rule | Get-NetFirewallPortFilter).LocalPort) } else { @() }
    if (-not ($ports -contains "8000" -and $ports -contains "8443")) {
        $adminCommands += "Remove-NetFirewallRule -DisplayName XVid -ErrorAction SilentlyContinue"
        $adminCommands += "New-NetFirewallRule -DisplayName XVid -Direction Inbound -Protocol TCP -LocalPort 8000,8443 -Action Allow -Profile Private | Out-Null"
    }
    foreach ($net in @(Get-NetConnectionProfile | Where-Object NetworkCategory -eq "Public")) {
        $q = "Your network '$($net.Name)' is set to Public, which blocks phones from reaching this PC. " +
             "Set it to Private? (Only do this for your home network.)"
        if (Ask $q $true) {
            $adminCommands += "Set-NetConnectionProfile -InterfaceIndex $($net.InterfaceIndex) -NetworkCategory Private"
        }
    }
    if ($adminCommands) {
        Write-Host "Windows will ask for permission to let your phone through the firewall..."
        try {
            $proc = Start-Process powershell -Verb RunAs -Wait -PassThru -ArgumentList @(
                "-NoProfile", "-Command", ("`$ErrorActionPreference = 'Stop'; " + ($adminCommands -join "; ")))
            if ($proc.ExitCode -ne 0) { Write-Warning "The firewall change failed. Phones may not be able to connect." }
            else { Write-Host "Firewall updated." }
        } catch {
            Write-Warning "Permission was declined, so the firewall wasn't changed. Phones may not be able to connect. Run setup again to retry."
        }
    } else {
        Write-Host "Firewall already set up."
    }
}

# ---------------------------------------------------------------- start with Windows
Step "Start automatically"
$shortcut = Join-Path ([Environment]::GetFolderPath("Startup")) "XVid.lnk"
if (Ask "Start XVid in the background every time you log in to Windows?" $true) {
    $lnk = (New-Object -ComObject WScript.Shell).CreateShortcut($shortcut)
    $lnk.TargetPath = "powershell.exe"
    $lnk.Arguments = "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$PSScriptRoot\run.ps1`" -Background"
    $lnk.WorkingDirectory = $PSScriptRoot
    $lnk.WindowStyle = 7  # minimized
    $lnk.Save()
    Write-Host "Added to Windows startup."
} elseif (Test-Path $shortcut) {
    Remove-Item $shortcut
    Write-Host "Removed from Windows startup."
}

# ---------------------------------------------------------------- start now
Step "Starting XVid"
Stop-XVid
Start-Process powershell -WindowStyle Hidden -ArgumentList @(
    "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "`"$PSScriptRoot\run.ps1`"", "-Background")

$https = Test-Path "certs\xvid.pem"
$port = if ($https) { 8443 } else { 8000 }
$url = if ($https) { "https://localhost:8443/#add-phone" } else { "http://127.0.0.1:8000/" }
if (Wait-Port $port 90) {
    Start-Process $url
    Write-Host "XVid is running and open in your browser."
} else {
    Write-Warning "XVid didn't start. See the log: $LogFile"
}

Write-Host ""
Write-Host "All set." -ForegroundColor Green
if ($https) {
    $ip = (Get-Content "certs\ip.txt").Trim()
    Write-Host " - Add a phone: in XVid, click 'Add a phone' and scan the QR code with the phone."
    Write-Host " - Tip: in your router, reserve $ip for this PC so the address never changes."
}
Write-Host " - Your videos: $env:USERPROFILE\Videos\XVid (unless XVID_LIBRARY is set)"
Write-Host " - start.cmd restarts XVid with a visible window, stop.cmd stops it. Log: $LogFile"
