# 停止本项目相关进程（只按路径匹配，不碰系统里其它 MySQL / Redis / Erlang）
. "$PSScriptRoot\common.ps1"

Write-Host ""
Write-Host "  停止秒杀系统相关进程" -ForegroundColor White
Write-Host ""

function Stop-ByMatch($label, [scriptblock]$filter) {
    $procs = Get-CimInstance Win32_Process | Where-Object $filter
    if (-not $procs) { Write-Ok "$label 没在运行"; return }
    foreach ($p in $procs) {
        Write-Host "    结束 $label PID=$($p.ProcessId)" -ForegroundColor Yellow
        Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
    }
}

Stop-ByMatch '应用'     { $_.Name -eq 'java.exe' -and $_.CommandLine -like '*seckill-1.0.0.jar*' }
Stop-ByMatch 'Redis'    { $_.Name -eq 'redis-server.exe' -and $_.ExecutablePath -like "*$MW\redis*" }
Stop-ByMatch 'MariaDB'  { $_.Name -eq 'mysqld.exe' -and $_.ExecutablePath -like "*$MW\mariadb*" }
Stop-ByMatch 'RabbitMQ' { $_.Name -eq 'erl.exe' -and $_.ExecutablePath -like "*$MW\erl26*" }
Stop-ByMatch 'Erlang后台' { $_.Name -eq 'erlsrv.exe' -and $_.ExecutablePath -like "*$MW\erl26*" }

Write-Host ""
foreach ($p in 6379,3306,5672,8080) {
    if (Test-Port $p) { Write-Bad "端口 $p 仍在监听" } else { Write-Ok "端口 $p 已释放" }
}
Write-Host ""
