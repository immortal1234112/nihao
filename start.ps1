# =====================================================================
#  AI Agent Platform 一键启动 (单窗口控制台版)
#
#  由 start.bat 调用。所有微服务在后台静默运行，日志统一写入 logs\ 目录，
#  不再弹出 8 个杂乱的控制台窗口。本窗口提供状态查看 / 日志查看 /
#  错误搜索 / 一键停止 等功能。
#
#  启动流程: 清理旧进程 → Docker 基础设施 → 数据库迁移 (sql\*.sql 自动应用, 幂等)
#           → Maven 编译 → 后台启动服务
#
#  用法:
#    .\start.bat                 完整启动 (Docker + 迁移 + 编译 + 服务)
#    .\start.bat -SkipInfra      跳过 Docker 基础设施 (MySQL 不在时同时跳过迁移)
#    .\start.bat -SkipBuild      跳过 Maven 编译
#    .\start.bat -NoMenu         启动后不进入控制台菜单, 直接退出 (脚本/后台场景)
#
#  注意: 项目路径不能包含空格 (后台启动命令依赖)。
# =====================================================================
[CmdletBinding()]
param(
    [switch]$SkipInfra,
    [switch]$SkipBuild,
    [switch]$NoMenu
)

# ---- 基础设置 ----
try { chcp 65001 | Out-Null } catch { }
try { [Console]::OutputEncoding = [Text.Encoding]::UTF8 } catch { }

$PROJ_DIR = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $PROJ_DIR
$LOG_DIR  = Join-Path $PROJ_DIR 'logs'

# ---- 可调参数 (与旧 start.bat 保持一致) ----
$env:MYSQL_PORT      = '3307'
$env:REQUIRE_API_KEY = 'false'

# ---- 基础设施凭据 (.env, 已 gitignore; 与 .jwt-secret 同理: 首次运行自动生成随机凭据) ----
# docker compose 与 Java 服务统一读取 .env 中的值; 已存在时复用, 避免与已初始化数据卷密码不一致。
$envFile = Join-Path $PROJ_DIR '.env'
if (-not (Test-Path $envFile)) {
    $rand32 = { -join ((48..57) + (65..90) + (97..122) | Get-Random -Count 32 | ForEach-Object { [char]$_ }) }
    $lines = @(
        'MYSQL_ROOT_PASSWORD=' + (& $rand32),
        'RABBITMQ_USER=agent-mq',
        'RABBITMQ_PASSWORD=' + (& $rand32),
        'MINIO_ROOT_USER=agent-minio',
        'MINIO_ROOT_PASSWORD=' + (& $rand32),
        'REDIS_PASSWORD=' + (& $rand32)
    )
    [System.IO.File]::WriteAllLines($envFile, $lines)   # UTF-8 无 BOM, docker compose 可正常解析
    Write-Host '已生成基础设施凭据文件 .env (随机凭据)'
}
Get-Content $envFile | Where-Object { $_ -match '^[A-Z_][A-Z0-9_]*=' } | ForEach-Object {
    $kv = $_ -split '=', 2
    if ($kv[0] -and $kv[1]) { Set-Item -Path "Env:$($kv[0])" -Value $kv[1] }
}
# Java 侧沿用各自变量名 (8 个服务 application.yml 占位符已覆盖, 无需改 yml)
$env:MYSQL_PASS       = $env:MYSQL_ROOT_PASSWORD
$env:RABBITMQ_PASS    = $env:RABBITMQ_PASSWORD
$env:MINIO_ACCESS_KEY = $env:MINIO_ROOT_USER
$env:MINIO_SECRET_KEY = $env:MINIO_ROOT_PASSWORD

# ---- JWT 密钥 (服务拒绝弱/默认密钥启动, 必须显式配置) ----
# 首次运行自动生成 ≥32 位随机串并持久化到 .jwt-secret (已 gitignore),
# 之后重启复用同一密钥, 避免存量 token 全部失效。
$jwtSecretFile = Join-Path $PROJ_DIR '.jwt-secret'
if (Test-Path $jwtSecretFile) {
    $env:JWT_SECRET = (Get-Content $jwtSecretFile -Raw).Trim()
} else {
    $env:JWT_SECRET = -join ((48..57) + (65..90) + (97..122) | Get-Random -Count 48 | ForEach-Object { [char]$_ })
    Set-Content -Path $jwtSecretFile -Value $env:JWT_SECRET -NoNewline
}

# ---- 服务间内部接口鉴权令牌 (与 JWT 密钥同理: 首次运行自动生成并持久化到 .internal-token, 已 gitignore) ----
# 各服务 internal.auth-token 统一读取该值; 未配置时内部接口不启用校验 (本地模式), 生产必须配置
$internalTokenFile = Join-Path $PROJ_DIR '.internal-token'
if (Test-Path $internalTokenFile) {
    $env:INTERNAL_AUTH_TOKEN = (Get-Content $internalTokenFile -Raw).Trim()
} else {
    $env:INTERNAL_AUTH_TOKEN = -join ((48..57) + (65..90) + (97..122) | Get-Random -Count 32 | ForEach-Object { [char]$_ })
    Set-Content -Path $internalTokenFile -Value $env:INTERNAL_AUTH_TOKEN -NoNewline
}

# ---- model_config API key 加密密钥 (首次运行自动生成并持久化到 .crypto-key, 已 gitignore) ----
# agent-core model-config.crypto-key 读取该值; 未配置时 API key 明文落库 (兼容存量部署)
$cryptoKeyFile = Join-Path $PROJ_DIR '.crypto-key'
if (Test-Path $cryptoKeyFile) {
    $env:MODEL_CONFIG_CRYPTO_KEY = (Get-Content $cryptoKeyFile -Raw).Trim()
} else {
    $env:MODEL_CONFIG_CRYPTO_KEY = -join ((48..57) + (65..90) + (97..122) | Get-Random -Count 32 | ForEach-Object { [char]$_ })
    Set-Content -Path $cryptoKeyFile -Value $env:MODEL_CONFIG_CRYPTO_KEY -NoNewline
}

# mvn 不在 PATH 时补充 (本机 Maven 安装路径)
if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    $mavenBin = 'C:\Users\immortal\tools\apache-maven-3.9.16\bin'
    if (Test-Path $mavenBin) { $env:PATH = "$mavenBin;$env:PATH" }
    else {
        Write-Host '[错误] 未找到 Maven，请安装或修正 start.ps1 中的 Maven 路径。'
        exit 1
    }
}

# ---- 服务定义 (数组顺序即启动顺序) ----
$SERVICES = @(
    @{ Name = 'Gateway';          Port = 8080; Cmd = 'mvn spring-boot:run -pl gateway';           Log = 'gateway.log';          Dir = '' },
    @{ Name = 'User-Service';     Port = 8081; Cmd = 'mvn spring-boot:run -pl user-service';      Log = 'user-service.log';     Dir = '' },
    @{ Name = 'Storage-Service';  Port = 8084; Cmd = 'mvn spring-boot:run -pl storage-service';   Log = 'storage-service.log';  Dir = '' },
    @{ Name = 'Agent-Core';       Port = 8082; Cmd = 'mvn spring-boot:run -pl agent-core';        Log = 'agent-core.log';       Dir = '' },
    @{ Name = 'Security-Service'; Port = 8083; Cmd = 'mvn spring-boot:run -pl security-service';  Log = 'security-service.log'; Dir = '' },
    @{ Name = 'Image-Engine';     Port = 8085; Cmd = 'mvn spring-boot:run -pl image-engine';      Log = 'image-engine.log';     Dir = '' },
    @{ Name = 'Video-Engine';     Port = 8086; Cmd = 'mvn spring-boot:run -pl video-engine';      Log = 'video-engine.log';     Dir = '' },
    @{ Name = 'WebAdmin';         Port = 3000; Cmd = 'npm run dev';                               Log = 'web-admin.log';        Dir = 'web-admin' }
)

# =====================================================================
#  辅助函数
# =====================================================================

function Test-PortOpen([int]$port) {
    $client = $null
    try { $client = [Net.Sockets.TcpClient]::new('127.0.0.1', $port) } catch { }
    if (-not $client) {
        # Vite 等前端服务可能只绑定 IPv6 回环 (::1)
        try { $client = [Net.Sockets.TcpClient]::new('::1', $port) } catch { }
    }
    if ($client) { $client.Close(); return $true }
    return $false
}

function Wait-Port([int]$port, [int]$timeoutSec) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        if (Test-PortOpen $port) { return $true }
        Start-Sleep -Seconds 3
    }
    return $false
}

# 读取日志文本: 优先按 UTF-8 解码, 失败则按 GBK 兼容中文系统上 Maven 的输出
# 用 FileShare.ReadWrite 打开, 避免后台进程正在写入时文件被独占锁住
function Read-LogText([string]$path) {
    if (-not (Test-Path $path)) { return "(日志文件不存在: $path)" }
    $bytes = $null
    try {
        $fs = [IO.File]::Open($path, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite)
        try {
            $bytes = [byte[]]::new($fs.Length)
            [void]$fs.Read($bytes, 0, $bytes.Length)
        } finally { $fs.Dispose() }
    } catch { return "(日志读取失败: $($_.Exception.Message))" }
    if ($bytes.Length -eq 0) { return '(日志为空)' }
    try { return ([Text.UTF8Encoding]::new($false, $true)).GetString($bytes) }
    catch {
        try { return ([Text.Encoding]::GetEncoding(936)).GetString($bytes) }
        catch { return [Text.Encoding]::Default.GetString($bytes) }
    }
}

function Get-SvcStatus($svc) {
    $log = Join-Path $LOG_DIR $svc.Log
    if (Test-PortOpen $svc.Port) { return '运行中' }
    if ((Test-Path $log) -and ((Get-Item $log).Length -gt 0)) { return '启动中' }
    return '未启动'
}

function Show-Status {
    Write-Host ''
    Write-Host ('  {0,-16} {1,6}   状态' -f '服务', '端口')
    Write-Host ('  ' + ('-' * 34))
    foreach ($svc in $SERVICES) {
        $st = Get-SvcStatus $svc
        $mark = switch ($st) { '运行中' { 'OK ' } '启动中' { '.. ' } default { '!! ' } }
        Write-Host ('  {0,-16} {1,6}  {2}{3}' -f $svc.Name, $svc.Port, $mark, $st)
    }
    Write-Host ('  ' + ('-' * 34))
}

# 在后台启动一个服务: 隐藏窗口, 输出重定向到 logs\<name>.log
function Start-Svc($svc) {
    $log = Join-Path $LOG_DIR $svc.Log
    $dir = if ($svc.Dir) { Join-Path $PROJ_DIR $svc.Dir } else { $PROJ_DIR }
    $cmd = 'chcp 65001 >nul 2>&1 & ' + $svc.Cmd + ' >> ' + $log + ' 2>&1'
    Start-Process -FilePath 'cmd.exe' -ArgumentList @('/c', $cmd) `
                  -WorkingDirectory $dir -WindowStyle Hidden | Out-Null
    Write-Host ('  {0,-16} 已启动  (日志: logs\{1})' -f $svc.Name, $svc.Log)
}

# 读取按键: 正常返回键名 (如 D1 / V / Q); 无键盘输入时退回 Read-Host
function Read-Key {
    try {
        return ([Console]::ReadKey($true).Key).ToString()
    } catch {
        $line = Read-Host '   (无键盘输入, 请直接键入选择)'
        $line = $line.Trim()
        if ($line -match '^[0-9]$') { return ('D' + $line) }
        return $line.ToUpper()
    }
}

# 数据库迁移: 将 sql\*.sql 按固定顺序应用到 Docker MySQL。
# 全部文件幂等 (CREATE ... IF NOT EXISTS / INSERT IGNORE / information_schema 条件 ALTER),
# 每次启动重复执行无副作用, 新环境首跑即建全表。
# 顺序: init(建库) → model_config(模型种子) → rag → chat → prompt_template → phase1(项目/资产)
function Invoke-SqlMigrations {
    $containerId = docker compose ps -q mysql 2>$null
    if (-not $containerId) {
        Write-Host '  未检测到 MySQL 容器, 跳过数据库迁移 (-SkipInfra 时属正常)。'
        return
    }
    # 容器的端口映射先于 mysqld 就绪, 用 mysqladmin ping 等真正可查询 (最长 60 秒)
    $env:MYSQL_PWD = $env:MYSQL_ROOT_PASSWORD
    $ready = $false
    for ($i = 0; $i -lt 20; $i++) {
        & docker exec -e MYSQL_PWD $containerId mysqladmin ping -uroot --silent 2>$null
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        Start-Sleep -Seconds 3
    }
    if (-not $ready) {
        Write-Host '  MySQL 未就绪, 跳过数据库迁移 (服务启动后可能缺表, 请重跑 start.bat)。'
        Remove-Item Env:\MYSQL_PWD -ErrorAction SilentlyContinue
        return
    }
    $files = @('init.sql', 'model_config.sql', 'rag.sql', 'chat.sql', 'prompt_template.sql', 'phase1_project_asset.sql')
    $tmpOut = Join-Path $env:TEMP 'sql_migrate_out.txt'
    $tmpErr = Join-Path $env:TEMP 'sql_migrate_err.txt'
    foreach ($f in $files) {
        $path = Join-Path $PROJ_DIR ('sql\' + $f)
        if (-not (Test-Path $path)) { Write-Host ('  跳过 (文件不存在): {0}' -f $f); continue }
        # Start-Process 重定向 stdin: SQL 文件字节原样传入 mysql,
        # 中文不走 PowerShell 管道/命令行, 不会被系统编码重编码
        $proc = Start-Process -FilePath 'docker' `
            -ArgumentList @('exec', '-i', '-e', 'MYSQL_PWD', $containerId, 'mysql', '-uroot', '--default-character-set=utf8mb4') `
            -RedirectStandardInput $path -RedirectStandardOutput $tmpOut -RedirectStandardError $tmpErr `
            -Wait -PassThru -NoNewWindow
        $errText = if (Test-Path $tmpErr) { (Get-Content $tmpErr -Raw -Encoding UTF8) } else { '' }
        if ($proc.ExitCode -ne 0 -or $errText -match 'ERROR ') {
            if ($f -eq 'init.sql') {
                Write-Host ('  [错误] 基础建库脚本 init.sql 应用失败, 中止启动: {0}' -f (($errText.Trim() -split "`n" | Select-Object -Last 2) -join ' | '))
                Remove-Item Env:\MYSQL_PWD -ErrorAction SilentlyContinue
                exit 1
            }
            Write-Host ('  [迁移警告] {0}: {1}' -f $f, (($errText.Trim() -split "`n" | Select-Object -Last 2) -join ' | '))
        } else {
            Write-Host ('  已应用: {0}' -f $f)
        }
    }
    Remove-Item $tmpOut, $tmpErr -ErrorAction SilentlyContinue
    Remove-Item Env:\MYSQL_PWD -ErrorAction SilentlyContinue
}

# 停止所有服务: 按 spring-boot:run / 已知端口清理
function Stop-All {
    Write-Host '停止所有后台服务...'
    Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -ieq 'java.exe' -and $_.CommandLine -match 'spring-boot:run' } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -ieq 'node.exe' -and $_.CommandLine -match 'vite|web-admin' } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    foreach ($p in @(3000, 8080, 8081, 8082, 8083, 8084, 8085, 8086)) {
        Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue |
            ForEach-Object { Stop-Process -Id $_.OwningProcess -Force -ErrorAction SilentlyContinue }
    }
    Start-Sleep -Seconds 3
    Write-Host '清理完成。'
}

function Show-SvcLog($svc) {
    $log = Join-Path $LOG_DIR $svc.Log
    while ($true) {
        Clear-Host
        Write-Host ('===== {0} 日志  (端口 {1}) =====' -f $svc.Name, $svc.Port)
        Write-Host ('日志文件: {0}' -f $log)
        Write-Host ''
        $text = Read-LogText $log
        ($text -split "`n" | Select-Object -Last 50) | ForEach-Object { Write-Host $_ }
        Write-Host ''
        Write-Host '  [回车] 刷新    [V] 打开完整日志(记事本)    [Q] 返回菜单'
        $k = Read-Key
        if ($k -eq 'V') { Start-Process notepad $log }
        elseif ($k -eq 'Q') { break }
    }
}

function Select-Svc {
    Clear-Host
    Write-Host '===== 选择要查看日志的服务 ====='
    Write-Host ''
    for ($i = 0; $i -lt $SERVICES.Count; $i++) {
        $svc = $SERVICES[$i]
        Write-Host ('  [{0}] {1,-16} 端口 {2}  (logs\{3})' -f ($i + 1), $svc.Name, $svc.Port, $svc.Log)
    }
    Write-Host '  [Q] 返回'
    Write-Host -NoNewline '  请按键选择: '
    $k = Read-Key
    Write-Host ''
    if ($k -eq 'Q') { return }
    if ($k -match '^D([0-9])$') {
        $num = [int]$Matches[1]
        if ($num -ge 1 -and $num -le $SERVICES.Count) { Show-SvcLog $SERVICES[$num - 1] }
    }
}

function Find-Errors {
    Clear-Host
    Write-Host '===== 搜索所有日志中的错误 (ERROR / Exception / FAILED) ====='
    Write-Host ''
    $hits = @()
    Get-ChildItem (Join-Path $LOG_DIR '*.log') -ErrorAction SilentlyContinue | ForEach-Object {
        $hits += Select-String -Path $_.FullName -Pattern 'ERROR|Exception|Caused by|BUILD FAILURE' |
            Select-Object -Last 15 |
            ForEach-Object { ('  {0} 第{1}行: {2}' -f $_.Filename, $_.LineNumber, $_.Line.Trim()) }
    }
    if ($hits.Count -eq 0) {
        Write-Host '  未发现错误，所有日志正常。'
    } else {
        $hits | Select-Object -Last 40 | ForEach-Object { Write-Host $_ }
        Write-Host ''
        Write-Host ('  共发现 {0} 处疑似错误。完整日志在: {1}' -f $hits.Count, $LOG_DIR)
    }
    Write-Host ''
    Write-Host '  按任意键返回菜单...'
    [void](Read-Key)
}

function Show-Menu {
    Clear-Host
    Write-Host '============================================================'
    Write-Host '   AI Agent Platform 控制台'
    Write-Host '============================================================'
    Show-Status
    Write-Host ''
    Write-Host '  [1] 查看服务日志        [2] 搜索日志中的错误'
    Write-Host '  [3] 刷新状态            [4] 停止所有服务并退出'
    Write-Host '  [5] 退出 (服务继续在后台运行, 可随时重新运行 start.bat 管理)'
    Write-Host ''
    Write-Host ('  日志目录: {0}' -f $LOG_DIR)
    Write-Host '  (推理服务需单独运行 inference\start.bat, 端口 7860)'
    Write-Host ''
    Write-Host -NoNewline '  请按键选择: '
    $key = Read-Key
    Write-Host ''
    switch ($key) {
        'D1' { Select-Svc }
        'D2' { Find-Errors }
        'D3' { }
        'D4' { Stop-All; Write-Host '已停止所有服务。按任意键退出...'; [void](Read-Key); exit 0 }
        'D5' { Write-Host '服务在后台继续运行。关闭本窗口不影响服务。'; Start-Sleep -Seconds 2; exit 0 }
    }
}

# =====================================================================
#  主流程
# =====================================================================
function Main {
    Write-Host ''
    Write-Host '============================================================'
    Write-Host '  AI Agent Platform - 一键启动 (单窗口控制台)'
    Write-Host '============================================================'
    Write-Host ''

    # [1/4] 清理旧进程
    Write-Host '[1/4] 清理旧进程...'
    Stop-All

    # [2/4] Docker 基础设施
    if ($SkipInfra) {
        Write-Host '[2/4] 跳过 Docker 基础设施 (-SkipInfra)'
    } else {
        Write-Host '[2/4] 启动 Docker 基础设施...'
        docker compose up -d
        if ($LASTEXITCODE -ne 0) {
            Write-Host ''
            Write-Host '[错误] Docker 启动失败! 请确认 Docker Desktop 正在运行后重试。'
            Write-Host '       或使用 start.bat -SkipInfra 跳过 (服务将无法连接数据库)。'
            Write-Host '按任意键退出...'
            [void](Read-Key)
            exit 1
        }
        Write-Host '等待基础设施就绪 (最长 90 秒)...'
        $infra = @(
            @{ Name = 'MySQL';    Port = 3307 },
            @{ Name = 'Nacos';    Port = 8848 },
            @{ Name = 'Redis';    Port = 6379 },
            @{ Name = 'RabbitMQ'; Port = 5672 },
            @{ Name = 'MinIO';    Port = 9000 }
        )
        foreach ($it in $infra) {
            $ok = Test-PortOpen $it.Port
            if (-not $ok) { $ok = Wait-Port $it.Port 90 }
            if ($ok) { Write-Host ('  {0,-10} 就绪' -f $it.Name) }
            else     { Write-Host ('  {0,-10} 超时! 请检查 Docker 容器状态' -f $it.Name) }
        }
    }

    # [3/5] 数据库迁移
    Write-Host '[3/5] 应用数据库迁移 (sql\*.sql, 幂等可重复)...'
    Invoke-SqlMigrations

    # [4/5] 编译
    if ($SkipBuild) {
        Write-Host '[4/5] 跳过 Maven 编译 (-SkipBuild)'
    } else {
        Write-Host '[4/5] 编译项目 (mvn install -DskipTests -q) ...'
        & mvn install -DskipTests -q
        if ($LASTEXITCODE -ne 0) {
            Write-Host '[错误] 编译失败! 按任意键退出...'
            [void](Read-Key)
            exit 1
        }
        Write-Host '编译完成。'
    }

    # [5/5] 启动服务
    Write-Host '[5/5] 后台启动所有服务...'
    if (-not (Test-Path $LOG_DIR)) { New-Item -ItemType Directory -Path $LOG_DIR -Force | Out-Null }
    Get-ChildItem (Join-Path $LOG_DIR '*.log') -ErrorAction SilentlyContinue | Remove-Item -Force
    foreach ($svc in $SERVICES) {
        Start-Svc $svc
        Start-Sleep -Seconds 2
    }

    Write-Host ''
    Write-Host '等待 Gateway (8080) 就绪 (最长 120 秒)...'
    if (Wait-Port 8080 120) {
        Write-Host 'Gateway 就绪! 前端: http://localhost:3000'
    } else {
        Write-Host 'Gateway 等待超时! 请查看 logs\gateway.log。'
    }

    if ($NoMenu) {
        Write-Host ''
        Write-Host '服务已在后台启动 (-NoMenu 模式, 不进入控制台菜单)。'
        Write-Host ('日志目录: {0}' -f $LOG_DIR)
        Write-Host '如需控制台面板, 直接运行 start.bat 即可 (会自动重启服务)。'
        Start-Sleep -Seconds 3
    } else {
        while ($true) { Show-Menu }
    }
}

Main -SkipInfra:$SkipInfra -SkipBuild:$SkipBuild -NoMenu:$NoMenu
