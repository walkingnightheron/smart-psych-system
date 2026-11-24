#Requires -Version 7.0
<#
.SYNOPSIS
    智慧心理健康评估系统 — 停止一键启动的全部服务
.EXAMPLE
    .\stop.ps1
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Continue'
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

$Root   = $PSScriptRoot
$RunDir = Join-Path $Root '.run'

Write-Host ''
Write-Host '  停止服务' -ForegroundColor White
Write-Host '  ------------------------------------------------' -ForegroundColor DarkGray

if (-not (Test-Path -LiteralPath $RunDir)) {
    Write-Host '  未找到 .run 目录，可能没有通过 start.ps1 启动过服务。' -ForegroundColor Yellow
    Write-Host ''
    return
}

$targets = @(
    @{ PidFile = 'backend.pid'; Name = '后端' },
    @{ PidFile = 'pc-web.pid';  Name = 'PC 管理端' },
    @{ PidFile = 'h5-app.pid';  Name = 'H5 参与端' }
)

foreach ($t in $targets) {
    $pidFile = Join-Path $RunDir $t.PidFile
    if (-not (Test-Path -LiteralPath $pidFile)) {
        Write-Host ("  [--] {0} 无记录，跳过" -f $t.Name) -ForegroundColor DarkGray
        continue
    }

    $raw = (Get-Content -LiteralPath $pidFile -ErrorAction SilentlyContinue | Select-Object -First 1)
    $procId = 0
    if (-not [int]::TryParse("$raw".Trim(), [ref]$procId)) {
        Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
        Write-Host ("  [--] {0} 进程号无效，已清理记录" -f $t.Name) -ForegroundColor DarkGray
        continue
    }

    $proc = Get-Process -Id $procId -ErrorAction SilentlyContinue
    if (-not $proc) {
        Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
        Write-Host ("  [--] {0} 已停止 (PID {1})" -f $t.Name, $procId) -ForegroundColor DarkGray
        continue
    }

    # 用 taskkill 连同子进程一起结束（vite / java 会派生进程）
    & taskkill /PID $procId /T /F 2>&1 | Out-Null
    Start-Sleep -Milliseconds 300

    if (Get-Process -Id $procId -ErrorAction SilentlyContinue) {
        Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
    }
    Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
    Write-Host ("  [OK] {0} 已停止 (PID {1})" -f $t.Name, $procId) -ForegroundColor Green
}

Write-Host '  ------------------------------------------------' -ForegroundColor DarkGray
Write-Host '  完成' -ForegroundColor White
Write-Host ''
