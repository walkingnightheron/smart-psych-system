#Requires -Version 7.0
<#
.SYNOPSIS
    智慧心理健康评估系统 — 一键启动脚本（后端 + PC 管理端 + H5 参与端）

.DESCRIPTION
    默认使用 demo 配置档启动：无需 MySQL / Redis / RabbitMQ，后端内置 H2 内存库并自动灌入
    教学模拟数据（7 个账号、3 人名单 2 份答卷 → 完成率 66.67%），适合本地演示与异地复现。

.EXAMPLE
    .\start.ps1
    .\start.ps1 -Rebuild            # 强制重新打包后端
    .\start.ps1 -NoBrowser          # 不自动打开浏览器
    .\start.ps1 -Profile default    # 使用完整中间件模式（需本机 MySQL/Redis/RabbitMQ）
#>
[CmdletBinding()]
param(
    [switch]$Rebuild,
    [switch]$NoBrowser,
    [switch]$SkipInstall,
    [string]$Profile = 'demo',
    [int]$BackendPort = 8080,
    [int]$PcPort = 5173,
    [int]$H5Port = 5174,
    [int]$TimeoutSeconds = 240
)

$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

$Root       = $PSScriptRoot
$BackendDir = Join-Path $Root 'backend'
$PcDir      = Join-Path $Root 'pc-web'
$H5Dir      = Join-Path $Root 'h5-app'
$LogDir     = Join-Path $Root 'logs'
$RunDir     = Join-Path $Root '.run'
$JarPath    = Join-Path $BackendDir 'target\psych-backend-0.1.0-SNAPSHOT.jar'

foreach ($d in @($LogDir, $RunDir)) {
    if (-not (Test-Path -LiteralPath $d)) { New-Item -ItemType Directory -Path $d | Out-Null }
}

function Write-Step { param([string]$Msg) Write-Host "==> $Msg" -ForegroundColor Cyan }
function Write-Ok   { param([string]$Msg) Write-Host "  [OK] $Msg" -ForegroundColor Green }
function Write-Warn { param([string]$Msg) Write-Host "  [!!] $Msg" -ForegroundColor Yellow }
function Write-Err  { param([string]$Msg) Write-Host "  [XX] $Msg" -ForegroundColor Red }

function Resolve-Tool {
    param([string]$Name, [string[]]$Candidates)
    foreach ($c in $Candidates) {
        $cmd = Get-Command $c -ErrorAction SilentlyContinue
        if ($cmd) { return $cmd.Source }
    }
    return $null
}

function Test-PortInUse {
    param([int]$Port)
    try {
        $conn = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction Stop
        return ($null -ne $conn)
    } catch { return $false }
}

function Test-HttpUp {
    param([string]$Url)
    try {
        $resp = Invoke-WebRequest -Uri $Url -TimeoutSec 4 -SkipHttpErrorCheck -ErrorAction Stop
        return ($resp.StatusCode -lt 500)
    } catch { return $false }
}

function Wait-HttpUp {
    param([string]$Url, [string]$Label, [string]$LogFile)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-HttpUp -Url $Url) { return $true }
        Start-Sleep -Milliseconds 800
    }
    Write-Err "$Label 在 $TimeoutSeconds 秒内未就绪"
    if ($LogFile -and (Test-Path -LiteralPath $LogFile)) {
        Write-Host "---- $LogFile (尾部 25 行) ----" -ForegroundColor DarkGray
        Get-Content -LiteralPath $LogFile -Tail 25 | ForEach-Object { Write-Host "  $_" -ForegroundColor DarkGray }
        Write-Host "--------------------------------" -ForegroundColor DarkGray
    }
    return $false
}

Write-Host ''
Write-Host '  智慧心理健康评估系统 · 一键启动' -ForegroundColor White
Write-Host '  ------------------------------------------------' -ForegroundColor DarkGray

# ---------- 1. 环境预检 ----------
Write-Step '环境预检'
$java = Resolve-Tool 'java' @('java.exe', 'java')
if (-not $java) { throw '未找到 java，请安装 JDK 17 并配置 PATH。' }
Write-Ok "java: $java"

$node = Resolve-Tool 'node' @('node.exe', 'node')
if (-not $node) { throw '未找到 node，请安装 Node.js 18+ 并配置 PATH。' }
Write-Ok "node: $node ($(& $node -v))"

$npm = Resolve-Tool 'npm' @('npm.cmd', 'npm')
if (-not $npm) { throw '未找到 npm。' }
Write-Ok "npm : $npm"

Write-Ok "配置档: $Profile"

# ---------- 2. 后端打包 ----------
Write-Step '准备后端'
$needBuild = $Rebuild -or (-not (Test-Path -LiteralPath $JarPath))
if (-not $needBuild) {
    $srcNewest = Get-ChildItem -LiteralPath (Join-Path $BackendDir 'src') -Recurse -File -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($srcNewest -and $srcNewest.LastWriteTime -gt (Get-Item -LiteralPath $JarPath).LastWriteTime) {
        $needBuild = $true
        Write-Warn "检测到源码更新（$($srcNewest.Name)），重新打包"
    }
}

if ($needBuild) {
    $mvn = Resolve-Tool 'mvn' @('mvn.cmd', 'mvn')
    if (-not $mvn) { throw '需要重新打包但未找到 mvn，请安装 Maven 3.9+ 并配置 PATH。' }
    Write-Host '    正在执行 mvn -DskipTests package ...' -ForegroundColor DarkGray
    Push-Location -LiteralPath $BackendDir
    try { & $mvn '-q' '-DskipTests' 'package' } finally { Pop-Location }
    if ($LASTEXITCODE -ne 0) { throw "后端打包失败（exit $LASTEXITCODE），请查看上方输出。" }
    Write-Ok '后端 jar 打包完成'
} else {
    Write-Ok '已有可用 jar，跳过打包（如需强制重建请加 -Rebuild）'
}

# ---------- 3. 前端依赖 ----------
if (-not $SkipInstall) {
    Write-Step '检查前端依赖'
    foreach ($pair in @(@{ Dir = $PcDir; Name = 'pc-web' }, @{ Dir = $H5Dir; Name = 'h5-app' })) {
        if (Test-Path -LiteralPath (Join-Path $pair.Dir 'node_modules')) {
            Write-Ok "$($pair.Name) 依赖已安装"
        } else {
            Write-Warn "$($pair.Name) 缺少 node_modules，执行 npm install ..."
            Push-Location -LiteralPath $pair.Dir
            try { & $npm 'install' } finally { Pop-Location }
            if ($LASTEXITCODE -ne 0) { throw "$($pair.Name) 依赖安装失败。" }
            Write-Ok "$($pair.Name) 依赖安装完成"
        }
    }
}

# ---------- 4. 启动后端 ----------
Write-Step "启动后端 (端口 $BackendPort)"
if (Test-PortInUse -Port $BackendPort) {
    Write-Warn "端口 $BackendPort 已被占用，跳过启动（如需重启请先执行 .\stop.ps1）"
} else {
    $backendLog = Join-Path $LogDir 'backend.log'
    $backendErr = Join-Path $LogDir 'backend.err.log'
    $proc = Start-Process -FilePath $java `
        -ArgumentList @('-jar', "`"$JarPath`"", "--spring.profiles.active=$Profile", "--server.port=$BackendPort") `
        -WorkingDirectory $BackendDir `
        -RedirectStandardOutput $backendLog `
        -RedirectStandardError  $backendErr `
        -PassThru -WindowStyle Hidden
    $proc.Id | Set-Content -LiteralPath (Join-Path $RunDir 'backend.pid')
    Write-Ok "已启动 (PID $($proc.Id))，日志 logs\backend.log"
}

# ---------- 5. 启动前端 ----------
function Start-Frontend {
    param([string]$Name, [string]$WorkDir, [string]$Script, [int]$Port, [string]$LogName)
    if (Test-PortInUse -Port $Port) {
        Write-Warn "$Name 端口 $Port 已被占用，跳过启动"
        return
    }
    $log = Join-Path $LogDir "$LogName.log"
    $err = Join-Path $LogDir "$LogName.err.log"
    $proc = Start-Process -FilePath $npm `
        -ArgumentList @('run', $Script, '--', '--port', "$Port") `
        -WorkingDirectory $WorkDir `
        -RedirectStandardOutput $log `
        -RedirectStandardError  $err `
        -PassThru -WindowStyle Hidden
    $proc.Id | Set-Content -LiteralPath (Join-Path $RunDir "$LogName.pid")
    Write-Ok "$Name 已启动 (PID $($proc.Id))，端口 $Port"
}

Write-Step "启动 PC 管理端 (端口 $PcPort)"
Start-Frontend -Name 'PC 管理端' -WorkDir $PcDir -Script 'dev' -Port $PcPort -LogName 'pc-web'

Write-Step "启动 H5 参与端 (端口 $H5Port)"
Start-Frontend -Name 'H5 参与端' -WorkDir $H5Dir -Script 'dev:h5' -Port $H5Port -LogName 'h5-app'

# ---------- 6. 等待就绪 ----------
Write-Step '等待服务就绪'
$ok = $true
if (-not (Wait-HttpUp -Url "http://localhost:$BackendPort/api/health" -Label '后端' -LogFile (Join-Path $LogDir 'backend.log'))) { $ok = $false }
else { Write-Ok '后端健康检查通过' }

if (-not (Wait-HttpUp -Url "http://localhost:$PcPort" -Label 'PC 管理端' -LogFile (Join-Path $LogDir 'pc-web.log'))) { $ok = $false }
else { Write-Ok 'PC 管理端已就绪' }

if (-not (Wait-HttpUp -Url "http://localhost:$H5Port" -Label 'H5 参与端' -LogFile (Join-Path $LogDir 'h5-app.log'))) { $ok = $false }
else { Write-Ok 'H5 参与端已就绪' }

# ---------- 7. 汇总 ----------
Write-Host ''
if ($ok) {
    Write-Host '  启动完成' -ForegroundColor Green
} else {
    Write-Host '  部分服务未就绪，请查看 logs\ 目录下的日志' -ForegroundColor Yellow
}
Write-Host '  ------------------------------------------------' -ForegroundColor DarkGray
Write-Host ("  PC 管理端   http://localhost:{0,-5}  admin01 / 123456    (admin02 已授权, admin03 未授权对照)" -f $PcPort)
Write-Host ("  H5 参与端   http://localhost:{0,-5}  20260003 / 123456   (20260001/20260002 已交卷, 20260004 名单外)" -f $H5Port)
Write-Host ("  后端接口    http://localhost:{0}/api/health" -f $BackendPort)
Write-Host '  ------------------------------------------------' -ForegroundColor DarkGray
Write-Host '  日志  logs\        停止  .\stop.ps1' -ForegroundColor DarkGray
Write-Host ''

if ($ok -and -not $NoBrowser) {
    try { Start-Process "http://localhost:$PcPort" | Out-Null } catch { }
    try { Start-Process "http://localhost:$H5Port" | Out-Null } catch { }
}
