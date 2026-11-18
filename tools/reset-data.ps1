# 一键重置：停应用 -> 清 Redis -> 删订单 -> 恢复库存 -> 重新启动应用（自动预热）
. "$PSScriptRoot\common.ps1"

Write-Host ""
Write-Host "  重置秒杀数据" -ForegroundColor White
Write-Host ""

$app = Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'java.exe' -and $_.CommandLine -like '*seckill-1.0.0.jar*' }
if ($app) {
    foreach ($p in $app) { Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue }
    Write-Host "    已停止应用，等端口释放..." -ForegroundColor Yellow
    Start-Sleep -Seconds 4
} else {
    Write-Ok "应用本来就没在运行"
}

if (-not (Test-Port 6379) -or -not (Test-Port 3306)) {
    Write-Bad "Redis 或 MariaDB 没运行，请先双击 start-all.bat"
    exit 1
}

& $RedisCli FLUSHDB | Out-Null
Write-Ok "Redis 已清空"

Invoke-Sql "DELETE FROM seckill_order;" | Out-Null
Write-Ok "订单已删除"

Invoke-Sql "UPDATE seckill_goods SET stock = total_stock;" | Out-Null
Write-Ok "库存已恢复到初始值"

Write-Host ""
Invoke-Sql "SELECT id, title, stock, total_stock FROM seckill_goods;"

if (-not (Test-Port 8080)) {
    Write-Step "重新启动应用"
    $java = Get-JavaExe
    if ($java -and (Test-Path -LiteralPath $JarPath)) {
        Start-Process -FilePath "cmd.exe" `
            -ArgumentList "/c","start","`"Seckill System`"","/MIN","`"$java`"","-jar","`"$JarPath`"" `
            -WorkingDirectory $PROJ -WindowStyle Hidden
        if (Wait-Port 8080 60) { Write-Ok "应用已重新启动（库存已预热）" } else { Write-Bad "应用没起来" }
    } else {
        Write-Bad "找不到 JDK 或 jar 包"
    }
}

Write-Host ""
Write-Host "  现在可以双击 demo.bat 演示了" -ForegroundColor Green
Write-Host ""
