@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\pull-reports.ps1" -OpenFolder %*
set "reportExitCode=%errorlevel%"
echo.
pause
exit /b %reportExitCode%
