# 并发压测：默认 200 个用户抢 100 件库存
. "$PSScriptRoot\common.ps1"

Write-Host ""
Write-Host "  秒杀系统 - 并发压测" -ForegroundColor White
Write-Host ""

if (-not (Test-Port 8080)) {
    Write-Bad "应用没在运行，先双击 start-all.bat"
    exit 1
}

$script = Join-Path $PROJ 'scripts\pressure_test.py'
$py = (Get-Command python -ErrorAction SilentlyContinue)
if (-not $py) { $py = (Get-Command python3 -ErrorAction SilentlyContinue) }
if (-not $py) {
    Write-Bad "没找到 python，装一个再来：winget install Python.Python.3.12"
    exit 1
}

Write-Host "    python: $($py.Source)" -ForegroundColor DarkGray
& $py.Source $script --users 200 --goods 1 --workers 20

Write-Host ""
Write-Host "  压测完想再演示，先 stop-all.bat，再 reset-data.bat，再 start-all.bat" -ForegroundColor DarkGray
Write-Host ""
