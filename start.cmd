@echo off
rem 智慧心理健康评估系统 — 一键启动（双击运行）
rem 透传所有参数，例如： start.cmd -Rebuild -NoBrowser
setlocal
where pwsh >nul 2>nul
if %ERRORLEVEL% EQU 0 (
    pwsh -NoProfile -ExecutionPolicy Bypass -File "%~dp0start.ps1" %*
) else (
    powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start.ps1" %*
)
echo.
pause
