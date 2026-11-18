# 一键启动：Redis -> MariaDB -> RabbitMQ -> SpringBoot 应用
. "$PSScriptRoot\common.ps1"

Write-Host ""
Write-Host "  高并发秒杀系统 - 一键启动" -ForegroundColor White
Write-Host "  项目目录: $PROJ"
Write-Host "  中间件目录: $MW"
Write-Host ""

# ---------- 1. Redis ----------
Write-Step "[1/4] Redis 6379"
if (Test-Port 6379) {
    Write-Ok "已经在运行，跳过"
} elseif (-not (Test-Path -LiteralPath $RedisExe)) {
    Write-Bad "找不到 $RedisExe"
} else {
    Start-Process -FilePath $RedisExe -WorkingDirectory (Split-Path $RedisExe) -WindowStyle Hidden
    if (Wait-Port 6379 10) { Write-Ok "启动成功" } else { Write-Bad "端口没起来" }
}

# ---------- 2. MariaDB ----------
Write-Step "[2/4] MariaDB 3306"
if (Test-Port 3306) {
    Write-Ok "已经在运行，跳过"
} elseif (-not (Test-Path -LiteralPath $MysqldExe)) {
    Write-Bad "找不到 $MysqldExe"
} else {
    Start-Process -FilePath $MysqldExe -ArgumentList "--defaults-file=$MyIni" -WindowStyle Hidden
    if (Wait-Port 3306 25) { Write-Ok "启动成功" } else { Write-Bad "端口没起来，检查 $MW\my.ini" }
}

# ---------- 3. RabbitMQ ----------
Write-Step "[3/4] RabbitMQ 5672"
if (Test-Port 5672) {
    Write-Ok "已经在运行，跳过"
} elseif (-not (Test-Path -LiteralPath $RabbitSbin)) {
    Write-Bad "找不到 $RabbitSbin"
} else {
    $env:ERLANG_HOME = $ErlHome
    $env:RABBITMQ_BASE = Join-Path $MW 'rabbitmq-data'
    Start-Process -FilePath (Join-Path $RabbitSbin 'rabbitmq-server.bat') -WorkingDirectory $RabbitSbin -WindowStyle Hidden
    if (Wait-Port 5672 60) { Write-Ok "启动成功" } else { Write-Bad "端口没起来，看 $MW\rabbitmq-data\log" }
}

# ---------- 4. 应用 ----------
Write-Step "[4/4] SpringBoot 应用 8080"
if (Test-Port 8080) {
    Write-Ok "已经在运行，跳过"
} else {
    if (-not (Test-Path -LiteralPath $JarPath)) {
        Write-Bad "没有找到 jar，先编译一次："
        Write-Host "      cd `"$PROJ`"" -ForegroundColor Yellow
        Write-Host "      `"$MavenCmd`" -s maven-settings.xml -DskipTests package" -ForegroundColor Yellow
    } else {
        $java = Get-JavaExe
        if (-not $java) {
            Write-Bad "找不到 JDK 17"
        } else {
            Start-Process -FilePath "cmd.exe" `
                -ArgumentList "/c","start","`"Seckill System`"","/MIN","`"$java`"","-jar","`"$JarPath`"" `
                -WorkingDirectory $PROJ -WindowStyle Hidden
            if (Wait-Port 8080 60) { Write-Ok "启动成功" } else { Write-Bad "端口没起来，把任务栏那个黑窗口打开看日志" }
        }
    }
}

Write-Host ""
Write-Host "  商品列表  http://localhost:8080/seckill/list" -ForegroundColor Green
Write-Host "  想看完整日志：任务栏里标题为 Seckill System 的窗口" -ForegroundColor DarkGray
Write-Host ""
