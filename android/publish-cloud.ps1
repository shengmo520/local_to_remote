# 发布到公网，实现「跨局域网更新」：手机用 4G / 别的 WiFi 也能收到更新
#
# 做法：把 update 目录（update.json + 页面 + APK）提交到 GitHub 仓库的 docs 目录，
#       再通过 jsDelivr 或 GitHub Pages 提供出去；同时把这个公网地址写进 APK，
#       装了这个 APK 之后手机在哪儿都能自动检查更新。
#
# 用法：
#   .\publish-cloud.ps1                      # 打包 + 生成 docs/ + 提交到本地
#   .\publish-cloud.ps1 -Push                # 顺便推送到 GitHub
#   .\publish-cloud.ps1 -Push -UpdateUrl "https://你的域名/"   # 换用对象存储等其它地址
param(
    [string]$Remote = 'origin',
    [string]$Branch = 'main',
    [string]$UpdateUrl = '',
    [switch]$Push
)
$ErrorActionPreference = 'Stop'
$ROOT = Split-Path -Parent $PSCommandPath
$PROJ = Split-Path -Parent $ROOT
$DOCS = Join-Path $PROJ 'docs'

# 先算出仓库的 用户名/仓库名，用来拼公网地址
$remoteUrl = (git -C $PROJ remote get-url $Remote) -replace '\.git$', ''
$slug = $remoteUrl -replace '^https://github\.com/', '' -replace '^git@github\.com:', ''
$user = ($slug -split '/')[0]
$repo = ($slug -split '/')[1]

# 默认用 jsDelivr（推上去立刻可用，国内一般能访问；缺点是分支文件最长 12 小时缓存）
if (-not $UpdateUrl) {
    $UpdateUrl = "https://cdn.jsdelivr.net/gh/$user/$repo@$Branch/docs/"
}

# 1) 打包发布，并把公网地址内置进 APK
& (Join-Path $ROOT 'publish.ps1') -UpdateUrl $UpdateUrl

# 2) 同步到 docs/（GitHub Pages 可以直接把这个目录当站点根）
New-Item -ItemType Directory -Force -Path $DOCS | Out-Null
Copy-Item (Join-Path $PROJ 'update\*') $DOCS -Force -Recurse
New-Item -ItemType File -Force -Path (Join-Path $DOCS '.nojekyll') | Out-Null

""
"已写入 $DOCS"
"APK 内置的更新地址： $UpdateUrl"
""
"其它可选地址（想换就改手机上的「更新地址」，或重跑本脚本加 -UpdateUrl）："
"  GitHub Pages : https://$user.github.io/$repo/    （需在仓库 Settings → Pages 里选 $Branch 分支 /docs 目录）"
"  jsDelivr     : https://cdn.jsdelivr.net/gh/$user/$repo@$Branch/docs/"
"  对象存储      : 把 docs 里几个文件传到 OSS/COS 桶根目录，用桶的访问域名（国内最稳）"

Push-Location $PROJ
try {
    # 注意：不要用 -f，否则会把 .gitignore 排除的签名私钥和构建产物也加进去
    git add docs index.html android 2>$null
    git -c user.name=shengmo520 -c user.email=168434781+shengmo520@users.noreply.github.com `
        commit -m "publish: 更新内容 $(Get-Date -Format 'yyyy-MM-dd HH:mm')" 2>$null
    if ($LASTEXITCODE -ne 0) {
        "（docs 内容没有变化，跳过提交）"
    } else {
        "已提交到本地仓库"
    }
    if ($Push) {
        # GitHub 在国内经常直连不上；如果本机有代理（Clash / v2ray 等）就自动走代理
        $proxyArgs = @()
        foreach ($p in @(7897, 7890, 7891, 7892, 10809, 10808, 2080, 1080)) {
            if (netstat -ano | Select-String -Pattern ":$p\s+.*LISTENING") {
                $proxyArgs = @('-c', "http.proxy=http://127.0.0.1:$p", '-c', "https.proxy=http://127.0.0.1:$p")
                "检测到本机代理 127.0.0.1:$p，推送时使用它"
                break
            }
        }
        git @proxyArgs push $Remote $Branch
        if ($LASTEXITCODE -eq 0) {
            "已推送到 $Remote/$Branch"
            # jsDelivr 对分支文件有最长 12 小时缓存，推完主动刷新，让更新立刻生效
            if ($UpdateUrl -like 'https://cdn.jsdelivr.net/*') {
                $purgeBase = $UpdateUrl -replace '^https://cdn\.jsdelivr\.net/', 'https://purge.jsdelivr.net/'
                $apkFile = (Get-Content (Join-Path $DOCS 'update.json') -Raw | ConvertFrom-Json).apk
                foreach ($f in @('update.json', 'index.html', 'download.html', $apkFile)) {
                    $okFile = $false
                    for ($try = 1; $try -le 3 -and -not $okFile; $try++) {
                        try {
                            Invoke-RestMethod -Uri ($purgeBase + $f) -TimeoutSec 60 | Out-Null
                            "已刷新缓存：$f"
                            $okFile = $true
                        } catch {
                            if ($try -lt 3) { Start-Sleep -Seconds 3 }
                        }
                    }
                    if (-not $okFile) { "刷新 $f 没成功（最长 12 小时后也会自动生效）" }
                }
            }
        } else {
            "推送失败：连不上 GitHub。国内通常需要开着代理（Clash / v2ray 等）再推。"
            "本地已经提交好了，等网络通了执行：  git push $Remote $Branch"
        }
    } else {
        ""
        "还没推送。确认无误后执行：  git push $Remote $Branch"
        "或者下次直接加 -Push：     .\publish-cloud.ps1 -Push"
    }
} finally {
    Pop-Location
}
