# 公共配置：其他脚本都 dot-source 这一个文件，换机器不用改路径
$ErrorActionPreference = 'Stop'

# 项目根目录 = tools 的上一级
$PROJ = Split-Path -Parent $PSScriptRoot

# 中间件目录：优先环境变量 SECKILL_MW，否则 用户目录\middleware
if ($env:SECKILL_MW) { $MW = $env:SECKILL_MW } else { $MW = Join-Path $env:USERPROFILE 'middleware' }

$RedisExe   = Join-Path $MW 'redis\redis-server.exe'
$RedisCli   = Join-Path $MW 'redis\redis-cli.exe'
$MariaBase  = Join-Path $MW 'mariadb\mariadb-10.11.9-winx64'
$MysqlExe   = Join-Path $MariaBase 'bin\mysql.exe'
$MysqldExe  = Join-Path $MariaBase 'bin\mysqld.exe'
$ErlHome    = Join-Path $MW 'erl26'
$RabbitSbin = Join-Path $MW 'rabbitmq\rabbitmq_server-3.13.7\sbin'
$MavenCmd   = Join-Path $MW 'apache-maven-3.9.9\bin\mvn.cmd'
$JarPath    = Join-Path $PROJ 'target\seckill-1.0.0.jar'
$Settings   = Join-Path $PROJ 'maven-settings.xml'
$MyIni      = Join-Path $MW 'my.ini'

$DBUser = 'root'
$DBPass = '123456'
$DBPort = 3306
$DBName = 'seckill'

function Get-JavaExe {
    if ($env:JAVA_HOME) {
        $c = Join-Path $env:JAVA_HOME 'bin\java.exe'
        if (Test-Path -LiteralPath $c) { return $c }
    }
    $base = 'C:\Program Files\Eclipse Adoptium'
    if (Test-Path -LiteralPath $base) {
        $dir = Get-ChildItem -LiteralPath $base -Directory -Filter 'jdk-17*' -ErrorAction SilentlyContinue |
               Sort-Object Name -Descending | Select-Object -First 1
        if ($dir) { return (Join-Path $dir.FullName 'bin\java.exe') }
    }
    $j = Get-Command java -ErrorAction SilentlyContinue
    if ($j) { return $j.Source }
    return $null
}

function Test-Port([int]$port) {
    $c = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
    return [bool]$c
}

function Wait-Port([int]$port, [int]$timeoutSec) {
    $sw = [Diagnostics.Stopwatch]::StartNew()
    while ($sw.Elapsed.TotalSeconds -lt $timeoutSec) {
        if (Test-Port $port) { return $true }
        Start-Sleep -Milliseconds 500
    }
    return $false
}

function Write-Step($msg) { Write-Host $msg -ForegroundColor Cyan }
function Write-Ok($msg)   { Write-Host "    OK    $msg" -ForegroundColor Green }
function Write-Bad($msg)  { Write-Host "    失败  $msg" -ForegroundColor Red }

function Invoke-Sql([string]$sql) {
    $a = @("--user=$DBUser", "--password=$DBPass", "--host=127.0.0.1", "--port=$DBPort", "--default-character-set=utf8mb4", $DBName, "-e", $sql)
    & $MysqlExe @a 2>&1
}
