# 演示一整单：查商品 -> 取秒杀地址 -> 下单 -> 查结果 -> 回数据库核对
. "$PSScriptRoot\common.ps1"

try { [Console]::OutputEncoding = [Text.Encoding]::UTF8 } catch {}

$BASE = 'http://localhost:8080'

# 按 UTF-8 读响应体，避免中文变乱码
function Get-Json([string]$url, $headers, [string]$method = 'GET') {
    $req = [System.Net.WebRequest]::Create($url)
    $req.Method = $method
    $req.Timeout = 15000
    if ($headers) { foreach ($k in $headers.Keys) { $req.Headers[$k] = [string]$headers[$k] } }
    $resp = $req.GetResponse()
    $sr = New-Object System.IO.StreamReader($resp.GetResponseStream(), [Text.Encoding]::UTF8)
    $txt = $sr.ReadToEnd()
    $sr.Close(); $resp.Close()
    return ($txt | ConvertFrom-Json)
}

Write-Host ""
Write-Host "  秒杀系统 - 完整流程演示" -ForegroundColor White
Write-Host ""

if (-not (Test-Port 8080)) {
    Write-Bad "应用没在运行，先双击 start-all.bat"
    exit 1
}

$USER_ID = Get-Random -Minimum 800000 -Maximum 999999
$GOODS   = 1

Write-Step "[1/5] 商品列表"
$list = Get-Json "$BASE/seckill/list"
$list.data | ForEach-Object { Write-Host ("    #{0} {1}  秒杀价 {2}  库存 {3}" -f $_.id, $_.title, $_.seckillPrice, $_.stock) }

Write-Step "[2/5] 获取秒杀地址（自带加盐防刷）"
$h = @{ userId = "$USER_ID" }
$p = Get-Json "$BASE/seckill/path/$GOODS" $h
if ($p.code -ne 0) { Write-Bad "拿地址失败: $($p.msg)"; exit 1 }
$path = $p.data
Write-Host "    path = $path" -ForegroundColor DarkGray
Write-Ok "拿到地址，有效期 60 秒"

Write-Step "[3/5] 下单"
$d = Get-Json "$BASE/seckill/do/$GOODS`?path=$path" $h 'POST'
if ($d.code -eq 0) { Write-Ok "进入排队，等 MQ 异步落库" } else { Write-Bad "下单失败: $($d.msg)" }

Write-Step "[4/5] 轮询结果"
$result = $null
for ($i = 0; $i -lt 10; $i++) {
    Start-Sleep -Milliseconds 300
    $r = Get-Json "$BASE/seckill/result/$GOODS" $h
    if ($r.data -ne 'QUEUING') { $result = $r.data; break }
}
if ($result -eq 'QUEUING') { Write-Bad "一直排队中，检查 RabbitMQ 和消费者日志" }
elseif ($result -eq 'SOLD_OUT') { Write-Bad "已售罄" }
else { Write-Ok "下单成功，订单号 $result" }

Write-Step "[5/5] 回数据库核对"
Invoke-Sql "SELECT id, user_id, goods_id, goods_price, status FROM seckill_order WHERE user_id=$USER_ID;"
Invoke-Sql "SELECT id, title, stock FROM seckill_goods WHERE id=$GOODS;"

Write-Host ""
Write-Host "  演示结束。本次用的是 userId=$USER_ID" -ForegroundColor DarkGray
Write-Host ""
