@echo off
setlocal
cd /d "%~dp0"
powershell.exe -NoProfile -NonInteractive -Command "$t=$null; $e=$null; [void][System.Management.Automation.Language.Parser]::ParseFile((Join-Path (Get-Location) 'phone_ui.ps1'),[ref]$t,[ref]$e); if($e.Count -gt 0){$e | ForEach-Object { Write-Host $_.Message }; exit 1}; Write-Host 'Phone UI syntax: OK'"
if errorlevel 1 (
    echo Bridge not started: PowerShell syntax check failed.
    pause
    exit /b 1
)
where py >nul 2>&1
if errorlevel 1 (
    python server.py
) else (
    py -3 server.py
)
if errorlevel 1 echo Bridge exited with an error.
pause
