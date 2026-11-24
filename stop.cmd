@echo off
rem 智慧心理健康评估系统 — 停止服务（双击运行）
setlocal
where pwsh >nul 2>nul
if %ERRORLEVEL% EQU 0 (
    pwsh -NoProfile -ExecutionPolicy Bypass -File "%~dp0stop.ps1" %*
) else (
    powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0stop.ps1" %*
)
echo.
pause
