# 一键发布：重新打包 + 生成 update.json，手机端检查更新就能直接下载
$ErrorActionPreference = 'Stop'
$ROOT = Split-Path -Parent $PSCommandPath
$PROJ = Split-Path -Parent $ROOT
$OUT  = Join-Path $env:TEMP 'qz-build\out'
$UPD  = Join-Path $PROJ 'update'

& (Join-Path $ROOT 'build.ps1') @args
if ($LASTEXITCODE -ne 0) { throw '构建失败' }

$info = Get-Content "$OUT\build-info.json" -Raw | ConvertFrom-Json
New-Item -ItemType Directory -Force -Path $UPD | Out-Null

# 用 build.ps1 处理过的版本（__BUILD__ 已替换成构建号）
Copy-Item (Join-Path $ROOT 'assets\index.html') (Join-Path $UPD 'index.html') -Force
$apkName = "qingzhu-$($info.shellVersionCode).apk"
Copy-Item (Join-Path $PROJ '青竹计划.apk') (Join-Path $UPD $apkName) -Force

$manifest = [xml](Get-Content (Join-Path $ROOT 'AndroidManifest.xml') -Raw)
$notes = if (Test-Path (Join-Path $ROOT 'release-notes.txt')) { (Get-Content (Join-Path $ROOT 'release-notes.txt') -Raw).Trim() } else { "" }

@{
    versionName    = $info.shellVersionName
    versionCode    = $info.shellVersionCode
    contentVersion = $info.contentVersion
    content        = "index.html"
    apk            = $apkName
    notes          = $notes
    publishedAt    = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss')
} | ConvertTo-Json | Set-Content -Path (Join-Path $UPD 'update.json') -Encoding UTF8

# 手机浏览器打开根地址看到的是这个下载页（__UPDATE_URL__ 由 serve.ps1 换成真实地址）
$apkKB = [math]::Round((Get-Item (Join-Path $UPD $apkName)).Length / 1KB, 1)
$notesHtml = if ($notes) { ($notes -replace "`r", "" -replace "`n", "<br>") } else { "内容更新" }
@"
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width,initial-scale=1" />
<title>青竹计划 · 下载</title>
<style>
  *{box-sizing:border-box}
  body{margin:0;min-height:100vh;background:#F3EFE4;color:#232A20;
       font:16px/1.7 -apple-system,"PingFang SC","Microsoft YaHei",sans-serif;
       display:flex;align-items:center;justify-content:center;padding:24px}
  .card{width:100%;max-width:420px;background:#FBF9F2;border-radius:16px;padding:28px 22px;
        box-shadow:0 1px 2px rgba(35,50,30,.05),0 12px 30px rgba(35,50,30,.10)}
  h1{margin:0 0 8px;font-size:28px;letter-spacing:-.5px;font-family:"Songti SC","SimSun",serif}
  .sub{margin:0 0 18px;color:#5B6455;font-size:13px}
  .ver{display:inline-block;background:rgba(63,107,74,.12);color:#3F6B4A;font-size:12px;
       padding:4px 10px;border-radius:6px;margin-bottom:20px}
  .btn{display:block;text-align:center;background:#3F6B4A;color:#fff;text-decoration:none;
       padding:17px;border-radius:12px;font-size:17px;font-weight:600}
  .notes{margin-top:20px;padding:14px;background:#F0EBDE;border-radius:12px;font-size:13px;color:#5B6455}
  .steps{margin-top:20px;font-size:13px;color:#5B6455;line-height:2}
  .url{display:block;margin-top:6px;word-break:break-all;background:#F0EBDE;border-radius:8px;
       padding:10px 12px;color:#232A20;font-size:13px}
</style>
</head>
<body>
  <div class="card">
    <h1>青竹计划</h1>
    <p class="sub">目标 · 关键结果 · 行动，进度自动汇总，到点提醒</p>
    <div class="ver">版本 $($info.shellVersionName) · 构建 $($info.contentVersion)</div>
    <a class="btn" href="$apkName">下载安装包（$apkKB KB）</a>
    <div class="notes">$notesHtml</div>
    <div class="steps">
      1. 点上面按钮下载安装包<br>
      2. 点开文件，按提示允许「安装未知应用」<br>
      3. 覆盖安装即可，计划数据不会丢<br>
      4. 装好后打开应用，它会自动用下面这个地址检查更新
      <span class="url">__UPDATE_URL__</span>
    </div>
  </div>
</body>
</html>
"@ | Set-Content -Path (Join-Path $UPD 'download.html') -Encoding UTF8

"已发布到 $UPD"
Get-ChildItem $UPD | Select-Object Name, @{n='KB';e={[math]::Round($_.Length/1KB,1)}}
"下一步：运行 android\serve.ps1 把地址填进手机上的「更新地址」"
