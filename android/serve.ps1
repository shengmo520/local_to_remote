# 在局域网里把 update 目录用 HTTP 提供出去，手机上的「更新地址」填这里显示的地址
# 用 TcpListener 自己实现，不需要管理员权限（HttpListener 绑非 localhost 要管理员）
param([int]$Port = 8080)
$ErrorActionPreference = 'Stop'
$ROOT = Split-Path -Parent $PSCommandPath
$UPD  = Join-Path (Split-Path -Parent $ROOT) 'update'

if (-not (Test-Path $UPD)) { throw "还没有 update 目录，先运行 publish.ps1" }
$updFull = (Resolve-Path $UPD).Path

$ips = @()
try {
    $ips = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction Stop |
        Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' } |
        Select-Object -ExpandProperty IPAddress
} catch { }
if (-not $ips) {
    try {
        $ips = [System.Net.Dns]::GetHostAddresses([System.Net.Dns]::GetHostName()) |
            Where-Object { $_.AddressFamily -eq 'InterNetwork' -and -not [System.Net.IPAddress]::IsLoopback($_) } |
            ForEach-Object { $_.IPAddressToString }
    } catch { }
}
if (-not $ips) {
    try {
        $udp = New-Object System.Net.Sockets.UdpClient
        $udp.Connect('8.8.8.8', 65535)
        $ips = @($udp.LocalEndPoint.Address.ToString())
        $udp.Close()
    } catch { }
}
if (-not $ips) { $ips = @('127.0.0.1') }

"青竹计划 · 更新服务已启动（保持这个窗口开着）"
foreach ($ip in $ips) { "  手机上的更新地址填：http://${ip}:${Port}/" }
"  目录：$updFull"
"  按 Ctrl+C 停止"

$types = @{ '.json' = 'application/json; charset=utf-8'; '.html' = 'text/html; charset=utf-8'; '.apk' = 'application/vnd.android.package-archive' }
$baseUrl = "http://$($ips[0]):$Port/"
$landingFile = Join-Path $UPD 'download.html'
$landing = ''
$listener = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Any, $Port)
$listener.Start()

while ($true) {
    $client = $listener.AcceptTcpClient()
    try {
        $stream = $client.GetStream()
        $reader = New-Object System.IO.StreamReader($stream, [System.Text.Encoding]::ASCII)
        $requestLine = $reader.ReadLine()
        while ($true) { $line = $reader.ReadLine(); if ($null -eq $line -or $line -eq '') { break } }

        $path = ''
        if ($requestLine -match '^GET\s+(\S+)') { $path = $matches[1].Split('?')[0].TrimStart('/') }

        # 根路径 / download.html：给手机浏览器看的下载页
        if ($path -eq '' -or $path -eq 'download.html') {
            if (-not $landing) {
                $landing = if (Test-Path $landingFile) {
                    (Get-Content $landingFile -Raw -Encoding UTF8).Replace('__UPDATE_URL__', $baseUrl)
                } else {
                    '<!DOCTYPE html><meta charset="utf-8"><body style="font:16px sans-serif;padding:24px">青竹计划：<a href="qingzhu-2.apk">下载安装包</a></body>'
                }
            }
            $bytes = [System.Text.Encoding]::UTF8.GetBytes($landing)
            $head = [System.Text.Encoding]::ASCII.GetBytes(
                "HTTP/1.1 200 OK`r`nContent-Type: text/html; charset=utf-8`r`nContent-Length: $($bytes.Length)`r`nConnection: close`r`n`r`n")
            $stream.Write($head, 0, $head.Length)
            $stream.Write($bytes, 0, $bytes.Length)
            "[{0}] 200 /（下载页）" -f (Get-Date -Format 'HH:mm:ss')
            $stream.Flush(); $stream.Close(); $client.Close(); continue
        }
        if ([string]::IsNullOrWhiteSpace($path)) { $path = 'update.json' }

        $file  = Join-Path $UPD $path
        $sent  = $false
        if (Test-Path -LiteralPath $file -PathType Leaf) {
            $full = (Resolve-Path $file).Path
            if ($full.StartsWith($updFull)) {
                $bytes = [System.IO.File]::ReadAllBytes($full)
                $ext = [System.IO.Path]::GetExtension($full).ToLower()
                $ctype = if ($types.ContainsKey($ext)) { $types[$ext] } else { 'application/octet-stream' }
                $head = [System.Text.Encoding]::ASCII.GetBytes(
                    "HTTP/1.1 200 OK`r`nContent-Type: $ctype`r`nContent-Length: $($bytes.Length)`r`nConnection: close`r`n`r`n")
                $stream.Write($head, 0, $head.Length)
                $stream.Write($bytes, 0, $bytes.Length)
                $sent = $true
                "[{0}] 200 {1}  ({2} KB)" -f (Get-Date -Format 'HH:mm:ss'), $path, [math]::Round($bytes.Length / 1KB, 1)
            }
        }
        if (-not $sent) {
            $body = [System.Text.Encoding]::UTF8.GetBytes('404 not found')
            $head = [System.Text.Encoding]::ASCII.GetBytes(
                "HTTP/1.1 404 Not Found`r`nContent-Type: text/plain`r`nContent-Length: $($body.Length)`r`nConnection: close`r`n`r`n")
            $stream.Write($head, 0, $head.Length)
            $stream.Write($body, 0, $body.Length)
            "[{0}] 404 {1}" -f (Get-Date -Format 'HH:mm:ss'), $path
        }
        $stream.Flush()
        $stream.Close()
    } catch { }
    $client.Close()
}
