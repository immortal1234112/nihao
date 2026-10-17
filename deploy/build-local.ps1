# =====================================================================
#  AI Agent Platform —— 本地构建 + 上传（Windows PowerShell）
#
#  ⚠️ 本文件必须存成 **UTF-8 with BOM**。
#     Windows PowerShell 5.1 在没有 BOM 时按系统 ANSI 代码页（中文机器上是
#     GBK）解析 .ps1，中文注释会被解码成乱码字节, 其中混进来的引号/花括号
#     会让脚本报出莫名其妙的语法错误（如"Try 语句缺少 Catch 块"）。
#     用 VS Code 保存时选 "UTF-8 with BOM"，或执行:
#       powershell -Command "$c=[IO.File]::ReadAllText('deploy\build-local.ps1',[Text.UTF8Encoding]::new($false)); [IO.File]::WriteAllText('deploy\build-local.ps1',$c,[Text.UTF8Encoding]::new($true))"
#
#  在**开发机**上运行。做三件事:
#     1. Maven 打包后端 jar
#     2. Vite 构建前端静态产物
#     3. 打成单个 tar.gz 上传到服务器并解包到部署根目录
#
#  用法（在仓库根目录执行）:
#     powershell -ExecutionPolicy Bypass -File deploy\build-local.ps1
#     powershell -ExecutionPolicy Bypass -File deploy\build-local.ps1 -SkipBuild   # 只上传
#     powershell -ExecutionPolicy Bypass -File deploy\build-local.ps1 -Deploy      # 上传后顺带跑部署
#
#  ⚠️ 为什么不直接在服务器上构建:
#     2C2G 上跑一次 Maven 编译要 1.5G+ 内存, 会和正在运行的 5 个 JVM 抢内存,
#     大概率把线上服务 OOM 掉。所以 deploy.conf 里 BUILD_ON_SERVER=false,
#     构建固定放在本地。
# =====================================================================
[CmdletBinding()]
param(
    # 跳过构建，只重新上传上次的产物（改配置/改 SQL 时用）
    [switch]$SkipBuild,
    # 上传完成后自动在服务器上执行 ./deploy.sh all
    [switch]$Deploy,
    # 跳过前端构建（只改了后端时能省一两分钟）
    [switch]$SkipWeb
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$RepoRoot   = Split-Path -Parent $PSScriptRoot
$DeployDir  = $PSScriptRoot
$StagingDir = Join-Path $RepoRoot '.deploy-staging'
$Tarball    = Join-Path $RepoRoot '.deploy-release.tar.gz'

function Say  ($m) { Write-Host "[build] $m" -ForegroundColor Cyan }
function Ok   ($m) { Write-Host "[build] $m" -ForegroundColor Green }
function Warn ($m) { Write-Host "[build] $m" -ForegroundColor Yellow }
function Die  ($m) { Write-Host "[build] $m" -ForegroundColor Red; exit 1 }

# =====================================================================
#  1. 读 deploy.conf
#
#  只解析顶层的 `KEY="value"` 赋值。故意**不**解析 `case "${PROFILE}"` 那些
#  条件分支 —— 服务开关的推导是服务器的 deploy.sh 的职责（那边是 bash，
#  天然能执行 deploy.conf）。本地只需要连接信息和路径。
#
#  ⚠️ 因此 $Conf 里的 ENABLE_* 和 JVM_OPTS_* 是**不可信**的（case 分支里对
#     同一个键有多次赋值，这里只会拿到最后一支，而且 ${...} 引用不会被展开）。
#     本脚本不使用它们，只认下面的连接参数。
# =====================================================================
$ConfPath = Join-Path $DeployDir 'deploy.conf'
if (-not (Test-Path $ConfPath)) { Die "找不到 $ConfPath" }

$Conf = @{}
foreach ($line in Get-Content $ConfPath -Encoding UTF8) {
    if ($line -match '^\s*#' -or $line -match '^\s*$') { continue }
    if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') {
        $key = $Matches[1]
        $val = $Matches[2]

        # 去掉行尾注释。只在 '#' 前面有空白时才当注释 —— deploy.conf 里的值
        # 不含 '#'，但这样写能避免以后有人填了带 '#' 的 URL 被截断。
        $val = ($val -replace '\s+#.*$', '').Trim()
        # 去掉包裹的引号（单双都处理）
        if ($val -match '^"(.*)"$') { $val = $Matches[1] }
        elseif ($val -match "^'(.*)'$") { $val = $Matches[1] }

        $Conf[$key] = $val
    }
}

foreach ($k in @('SERVER_HOST', 'SERVER_USER', 'REMOTE_ROOT')) {
    if (-not $Conf.ContainsKey($k) -or [string]::IsNullOrWhiteSpace($Conf[$k])) {
        Die "deploy.conf 里的 $k 没有填。先填好再跑本脚本。"
    }
}
$SshPort = if ($Conf.ContainsKey('SERVER_SSH_PORT') -and $Conf['SERVER_SSH_PORT']) { $Conf['SERVER_SSH_PORT'] } else { '22' }
$Root    = $Conf['REMOTE_ROOT']
$Target  = "$($Conf['SERVER_USER'])@$($Conf['SERVER_HOST'])"

# SSH 参数集中在这里，避免每个调用点重复
$SshArgs = @('-p', $SshPort, '-o', 'ServerAliveInterval=30')
$ScpArgs = @('-P', $SshPort, '-o', 'ServerAliveInterval=30')
if ($Conf.ContainsKey('SSH_KEY') -and $Conf['SSH_KEY']) {
    $SshArgs += @('-i', $Conf['SSH_KEY'])
    $ScpArgs += @('-i', $Conf['SSH_KEY'])
}

Say "目标: $Target`:$Root  (端口 $SshPort, 档位 $($Conf['PROFILE']))"

# =====================================================================
#  2. 构建
# =====================================================================
if (-not $SkipBuild) {
    # ---- 后端 ----
    Say "Maven 打包（跳过测试）…"
    Push-Location $RepoRoot
    try {
        # -T 1C 并行构建: 多模块项目在本地能快一倍左右
        & mvn -q -DskipTests -T 1C clean package
        if ($LASTEXITCODE -ne 0) { Die "Maven 构建失败（退出码 $LASTEXITCODE）" }
    } finally { Pop-Location }
    Ok "后端构建完成"

    # ---- 前端 ----
    if (-not $SkipWeb) {
        $WebDir = Join-Path $RepoRoot 'web-admin'
        Say "前端构建…"

        # ★ 显式用 npm.cmd，不用 `npm`
        #   Windows 上 npm 有三种形态：npm（→npm.ps1）、npm.cmd、npm.exe。
        #   PowerShell 默认执行策略是 Restricted 时，调用 `& npm` 会解析到
        #   npm.ps1 并报「在此系统上禁止运行脚本」——构建直接失败，而且这个
        #   报错看起来像是"npm 没装"，很容易误判。
        #   用 .cmd 绕过 PS 脚本策略，不要求用户改机器级设置。
        $NpmExe = (Get-Command npm.cmd -ErrorAction SilentlyContinue)
        if (-not $NpmExe) {
            # 少数环境只有 npm.exe / 或 npm 在 PATH 别处
            $NpmExe = (Get-Command npm -CommandType Application -ErrorAction SilentlyContinue)
        }
        if (-not $NpmExe) {
            Die "找不到 npm。先装 Node.js 18+ 并确认 'npm.cmd --version' 可用。"
        }
        $Npm = $NpmExe.Source
        Say "npm: $Npm"

        Push-Location $WebDir
        try {
            if (-not (Test-Path 'node_modules')) {
                Say "首次构建，安装依赖（npm ci）…"
                & $Npm ci
            } else {
                & $Npm install --no-audit --no-fund
            }
            if ($LASTEXITCODE -ne 0) { Die "npm install 失败（退出码 $LASTEXITCODE）" }

            # build 脚本是 `tsc && vite build`：类型错误会在这里挡下来，
            # 这正是我们想要的 —— 别把带类型错误的前端发到线上。
            & $Npm run build
            if ($LASTEXITCODE -ne 0) { Die "前端构建失败（类型错误或 Vite 报错）" }
        } finally { Pop-Location }
        Ok "前端构建完成"
    }
}

# =====================================================================
#  3. 组织暂存目录
#
#  ★ 目录结构必须和服务器上的 ${REMOTE_ROOT} **完全一致** ——
#    解包后 deploy.sh 就在 ROOT 根上，它的 SCRIPT_DIR 等于 REMOTE_ROOT，
#    于是 sql/、bin/、systemd/、nginx/ 这几个相对路径才能解析到。
#    （deploy.sh 用 SCRIPT_DIR 而不是硬编码路径，就是为了支持这种摆法）
# =====================================================================
Say "组织上传产物…"
if (Test-Path $StagingDir) { Remove-Item $StagingDir -Recurse -Force }
foreach ($d in @('apps', 'web', 'sql', 'bin', 'systemd', 'nginx')) {
    New-Item -ItemType Directory -Path (Join-Path $StagingDir $d) -Force | Out-Null
}

# 要上传哪些服务的 jar —— 与 deploy.sh 的 enabled_services() 保持一致。
#
# 为什么不解析 deploy.conf 里的 ENABLE_* 开关：那些开关写在 case 分支里，
# 本脚本的简易解析器（只认 KEY=value）抓不到，而且同名键在三个分支里各出现
# 一次，会拿到最后一支（full）的值。所以只按 PROFILE 推导。
#
# 少传的 jar 不会导致问题：deploy.sh 的 preflight 会提示"缺少 X 的 jar"，
# 升档时重跑一次本脚本即可。
$Profile = if ($Conf.ContainsKey('PROFILE') -and $Conf['PROFILE']) { $Conf['PROFILE'] } else { 'lite' }
switch ($Profile) {
    'lite' {
        $Services = @('gateway', 'user-service', 'agent-core', 'image-engine', 'storage-service')
    }
    'medium' {
        $Services = @('gateway', 'user-service', 'agent-core', 'security-service',
                      'image-engine', 'video-engine', 'storage-service')
    }
    'full' {
        $Services = @('gateway', 'user-service', 'agent-core', 'security-service',
                      'image-engine', 'video-engine', 'storage-service')
    }
    default {
        Die "deploy.conf 的 PROFILE 非法: '$Profile'（只能是 lite / medium / full）"
    }
}
Say "档位 $Profile → 上传 $($Services.Count) 个服务的 jar"

foreach ($svc in $Services) {
    $jar = Get-ChildItem -Path (Join-Path $RepoRoot "$svc\target") -Filter "$svc-*.jar" -ErrorAction SilentlyContinue |
           Where-Object { $_.Name -notmatch '\.original$' } |
           Select-Object -First 1
    if ($jar) {
        Copy-Item $jar.FullName (Join-Path $StagingDir 'apps') -Force
        Say ("  {0,-18} {1,8:N1} MB" -f $svc, ($jar.Length / 1MB))
    } else {
        Warn "  $svc 没有找到 jar（该模块可能被跳过）"
    }
}

$Dist = Join-Path $RepoRoot 'web-admin\dist'
if (Test-Path $Dist) {
    Copy-Item (Join-Path $Dist '*') (Join-Path $StagingDir 'web') -Recurse -Force
    Ok "  前端产物已就位"
} else {
    Warn "  没有 web-admin\dist，前端不会被更新"
}

Copy-Item (Join-Path $RepoRoot 'sql\*.sql') (Join-Path $StagingDir 'sql') -Force

# 部署脚本自身也一起上传：改了 deploy.conf 或 systemd 单元之后，
# 只需重跑本脚本就能生效，不用单独再 scp 一次
$DeployFiles = @('deploy.conf', 'deploy.sh', 'remote-install.sh', 'docker-compose.infra.yml')
foreach ($f in $DeployFiles) {
    $p = Join-Path $DeployDir $f
    if (Test-Path $p) { Copy-Item $p $StagingDir -Force } else { Warn "  缺少 deploy/$f" }
}
# 这三个是"有就上传、没有就警告"的，但 run-service.sh 不一样：
# 它是 systemd 的 ExecStart 目标，缺了会导致所有服务起不来。
# 所以单独用 Die 硬失败，而不是 Warn 后继续打出个残缺的包。
$RequiredFiles = @(
    @{ Path = 'bin\run-service.sh';           Dst = 'bin'     },
    @{ Path = 'systemd\ai-agent@.service';    Dst = 'systemd' },
    @{ Path = 'nginx\ai-agent.conf';          Dst = 'nginx'   }
)
foreach ($rf in $RequiredFiles) {
    $src = Join-Path $DeployDir $rf.Path
    if (-not (Test-Path $src)) {
        Die "缺少 deploy/$($rf.Path) —— 它是部署必需文件（systemd 启动器/单元/nginx 站点），不能打残缺包"
    }
    Copy-Item $src (Join-Path $StagingDir $rf.Dst) -Force
}

# =====================================================================
#  4. 打包
#
#  为什么不是直接 scp 一堆文件: 5 个 jar 加起来 300MB+, 逐个 scp 要反复
#  建连握手; 而且 Windows 的 NTFS 没有 Unix 权限位, scp 过去之后 .sh 会失
#  去可执行位。打成 tar 再解包能把权限位一起带过去, 后面再兜一次 chmod。
# =====================================================================
Say "打包…"
if (Test-Path $Tarball) { Remove-Item $Tarball -Force }
Push-Location $StagingDir
try {
    # Windows 自带的 tar 是 bsdtar，支持 -z
    & tar -czf $Tarball -C $StagingDir .
    if ($LASTEXITCODE -ne 0) { Die "tar 打包失败" }
} finally { Pop-Location }
$sizeMB = (Get-Item $Tarball).Length / 1MB
Ok ("包体 {0:N1} MB" -f $sizeMB)

# =====================================================================
#  5. 上传并解包
#
#  先传到 /tmp 再解包: 避免解到一半断线留下半个 jar（那比上传失败更糟 ——
#  服务会被 systemd 拉起来但跑的是损坏的包）。
# =====================================================================
$remoteTmp = "/tmp/ai-agent-release.tar.gz"
Say "上传到 $Target`:$remoteTmp（300MB 左右，视上行带宽可能要几分钟）…"
& scp @ScpArgs $Tarball "${Target}:${remoteTmp}"
if ($LASTEXITCODE -ne 0) { Die "scp 上传失败。检查: SERVER_HOST 是否正确、SSH_KEY、安全组是否放行 $SshPort" }

$remoteScript = @"
set -e
mkdir -p '$Root'
tar -xzf '$remoteTmp' -C '$Root'
chmod +x '$Root/deploy.sh' '$Root/remote-install.sh' '$Root/bin/run-service.sh'
rm -f '$remoteTmp'
echo "解包完成: `$(ls -1 '$Root/apps' | wc -l) 个 jar"
"@

Say "解包…"
& ssh @SshArgs $Target $remoteScript
if ($LASTEXITCODE -ne 0) { Die "远程解包失败" }
Ok "上传完成"

# 暂存目录清掉，tar 留着 —— 回滚时要重新上传同一个包
Remove-Item $StagingDir -Recurse -Force -ErrorAction SilentlyContinue
Say "暂存目录已清理，包保留在 $Tarball"

# =====================================================================
#  6. 可选: 直接触发服务器端部署
# =====================================================================
if ($Deploy) {
    Say "在服务器上执行 ./deploy.sh all …"
    # -t 分配 TTY: deploy.sh 的输出带颜色，也会打印进度，没有 TTY 会难读
    & ssh @SshArgs -t $Target "cd '$Root' && ./deploy.sh all"
    if ($LASTEXITCODE -ne 0) { Die "远程部署失败，登录服务器看: ./deploy.sh status" }
    Ok "部署完成"
} else {
    Write-Host ""
    Ok "下一步（在服务器上）:"
    Write-Host "    cd $Root && ./deploy.sh all"
    Write-Host ""
}
