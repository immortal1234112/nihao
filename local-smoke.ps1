[CmdletBinding()]
param(
    [switch]$IncludeInference,
    [int]$TimeoutSeconds = 2,
    [switch]$Quiet
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot

$checks = @(
    @{ Name = 'Gateway';          Port = 8080; Required = $true },
    @{ Name = 'User-Service';     Port = 8081; Required = $true },
    @{ Name = 'Agent-Core';       Port = 8082; Required = $true },
    @{ Name = 'Security-Service'; Port = 8083; Required = $true },
    @{ Name = 'Storage-Service';  Port = 8084; Required = $true },
    @{ Name = 'Image-Engine';     Port = 8085; Required = $true },
    @{ Name = 'Video-Engine';     Port = 8086; Required = $true },
    @{ Name = 'WebAdmin';         Port = 3000; Required = $true },
    @{ Name = 'MySQL';            Port = 3307; Required = $true },
    @{ Name = 'Nacos';            Port = 8848; Required = $true },
    @{ Name = 'Redis';            Port = 6379; Required = $true },
    @{ Name = 'RabbitMQ';         Port = 5672; Required = $true },
    @{ Name = 'MinIO';            Port = 9000; Required = $true }
)

if ($IncludeInference) {
    $checks += @{ Name = 'Inference'; Port = 7860; Required = $true }
}

function Test-Port([string]$hostName, [int]$port, [int]$timeoutMs) {
    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $async = $client.BeginConnect($hostName, $port, $null, $null)
        if (-not $async.AsyncWaitHandle.WaitOne($timeoutMs)) { return $false }
        $client.EndConnect($async)
        return $true
    } catch {
        return $false
    } finally {
        $client.Dispose()
    }
}

if (-not $Quiet) {
    Write-Host 'Local service smoke check'
    Write-Host ('Working directory: {0}' -f $root)
    Write-Host ''
}

$timeoutMs = [Math]::Max(250, $TimeoutSeconds * 1000)
$failed = @()

foreach ($check in $checks) {
    $ok = Test-Port '127.0.0.1' $check.Port $timeoutMs
    if ($ok) {
        if (-not $Quiet) { Write-Host ('[OK]   {0,-16} 127.0.0.1:{1}' -f $check.Name, $check.Port) -ForegroundColor Green }
    } else {
        $failed += $check
        if (-not $Quiet) { Write-Host ('[FAIL] {0,-16} 127.0.0.1:{1}' -f $check.Name, $check.Port) -ForegroundColor Red }
    }
}

if (-not $Quiet) {
    Write-Host ''
    if ($failed.Count -eq 0) {
        Write-Host 'Local environment check passed.' -ForegroundColor Green
    } else {
        Write-Host ('Check failed: {0}/{1} services are not ready.' -f $failed.Count, $checks.Count) -ForegroundColor Red
        Write-Host 'Run start.bat first, then inspect the matching log under logs.'
    }
}

if ($failed.Count -gt 0) { exit 1 }
exit 0
