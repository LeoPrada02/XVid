@echo off
rem Stops XVid if it is running.
powershell -NoProfile -ExecutionPolicy Bypass -Command ". '%~dp0tools\common.ps1'; Stop-XVid; Write-Host 'XVid stopped.'"
pause
