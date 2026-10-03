# 青竹 —— 不依赖 Gradle 的极简安卓打包脚本
# 依赖：JDK 17 + Android build-tools 34 + platform android.jar（放在 $TC 下）
param(
    [long]$ContentVersion = 0,        # 内容版本号，不传就用当前时间生成
    [int]$ShellVersionCode = 3,
    [string]$ShellVersionName = "1.6",
    [string]$UpdateUrl = ""           # 内置到 APK 里的更新地址；留空则用本机局域网地址
)
$ErrorActionPreference = 'Stop'

$ROOT = Split-Path -Parent $PSCommandPath              # ...\计划APP\android
$PROJ = Split-Path -Parent $ROOT                       # ...\计划APP
$TC   = 'C:\Users\86150\android-build\x'
$JDK  = Join-Path $TC 'jdk17\jdk-17.0.13+11'
$BT   = Join-Path $TC 'buildtools\android-14'
$ANDROID_JAR = Join-Path $TC 'platform\android-34\android.jar'
# 构建中间产物放系统临时目录（工具链本身只读使用）
$OUT  = Join-Path $env:TEMP 'qz-build\out'
# aapt2 / zipalign 是原生程序，处理不了带中文的路径，所以先拷到纯英文目录里构建
$STAGE = Join-Path $env:TEMP 'qz-build\stage'
$PKG  = 'com\planapp\bamboo'

if ($ContentVersion -le 0) { $ContentVersion = [long](Get-Date -Format 'yyMMddHHmmss') }

$env:JAVA_HOME = $JDK
$env:PATH = "$JDK\bin;$env:PATH"

foreach ($p in @($JDK, $BT, $ANDROID_JAR)) {
    if (-not (Test-Path -LiteralPath $p)) { throw "缺少工具链：$p" }
}

Remove-Item $OUT -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $OUT, "$OUT\gen", "$OUT\geninfo", "$OUT\classes", "$OUT\dex", "$ROOT\assets" | Out-Null

# 1) 单文件应用放进 assets：
#    __BUILD__ 换成构建号（页面自己就能报出是哪一版）
#    __UPDATE_URL__ 换成这台电脑的局域网更新地址（手机不用手输）
$lanIp = ''
try {
    $lanIp = (Get-NetIPAddress -AddressFamily IPv4 -ErrorAction Stop |
        Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' -and $_.PrefixOrigin -ne 'WellKnown' } |
        Select-Object -First 1 -ExpandProperty IPAddress)
} catch { }
if (-not $lanIp) {
    try {
        $lanIp = ([System.Net.Dns]::GetHostAddresses([System.Net.Dns]::GetHostName()) |
            Where-Object { $_.AddressFamily -eq 'InterNetwork' -and -not [System.Net.IPAddress]::IsLoopback($_) } |
            Select-Object -First 1).IPAddressToString
    } catch { }
}
if (-not $UpdateUrl) { $UpdateUrl = if ($lanIp) { "http://${lanIp}:8080/" } else { "" } }

$html = Get-Content (Join-Path $PROJ 'index.html') -Raw -Encoding UTF8
$html = $html.Replace('__BUILD__', "$ContentVersion").Replace('__UPDATE_URL__', $UpdateUrl)
Set-Content -Path (Join-Path $ROOT 'assets\index.html') -Value $html -Encoding UTF8 -NoNewline
Remove-Item $STAGE -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $STAGE | Out-Null
Copy-Item "$ROOT\res"   (Join-Path $STAGE 'res') -Recurse -Force
Copy-Item "$ROOT\src"   (Join-Path $STAGE 'src') -Recurse -Force
Copy-Item "$ROOT\assets" (Join-Path $STAGE 'assets') -Recurse -Force
Copy-Item "$ROOT\AndroidManifest.xml" (Join-Path $STAGE 'AndroidManifest.xml') -Force

# 2) 生成版本信息类（内容版本号 = 本次构建时间，用于判断手机上的页面是不是旧的）
$infoDir = Join-Path $OUT "geninfo\$PKG"
New-Item -ItemType Directory -Force -Path $infoDir | Out-Null
@"
package com.planapp.bamboo;

public final class AppInfo {
    public static final long CONTENT_VERSION = ${ContentVersion}L;
    public static final int SHELL_VERSION_CODE = $ShellVersionCode;
    public static final String SHELL_VERSION_NAME = "$ShellVersionName";
}
"@ | Set-Content -Path (Join-Path $infoDir 'AppInfo.java') -Encoding UTF8

# 3) 编译资源
& "$BT\aapt2.exe" compile --dir "$STAGE\res" -o "$OUT\res.zip"
if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile 失败' }

# 4) 链接资源、打包 assets、生成 R.java
#    注意：编译产物 zip 必须作为位置参数传入，用 -R 会被当成「叠加资源」而报错
& "$BT\aapt2.exe" link `
    -o "$OUT\base.apk" `
    -I "$ANDROID_JAR" `
    --manifest "$STAGE\AndroidManifest.xml" `
    "$OUT\res.zip" `
    -A "$STAGE\assets" `
    --java "$OUT\gen" `
    --min-sdk-version 26 `
    --target-sdk-version 34 `
    --version-code $ShellVersionCode `
    --version-name $ShellVersionName
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link 失败' }

# 5) 编译 Java
$SRC = @(
    "$OUT\gen\$PKG\R.java",
    "$infoDir\AppInfo.java"
) + (Get-ChildItem "$STAGE\src\$PKG" -Filter *.java | ForEach-Object { $_.FullName })
& "$JDK\bin\javac.exe" -encoding UTF-8 -source 8 -target 8 -nowarn `
    -classpath "$ANDROID_JAR" -d "$OUT\classes" $SRC
if ($LASTEXITCODE -ne 0) { throw 'javac 失败' }

# 6) 转成 dex
$CLASSES = Get-ChildItem "$OUT\classes\$PKG" -Filter *.class | ForEach-Object { $_.FullName }
& "$BT\d8.bat" --lib "$ANDROID_JAR" --min-api 26 --output "$OUT\dex" $CLASSES
if ($LASTEXITCODE -ne 0) { throw 'd8 失败' }

# 7) 把 classes.dex 放进 APK
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$OUT\base.apk", 'Update')
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$OUT\dex\classes.dex", 'classes.dex') | Out-Null
$zip.Dispose()

# 8) 4 字节对齐
& "$BT\zipalign.exe" -f 4 "$OUT\base.apk" "$OUT\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw 'zipalign 失败' }

# 9) 签名（证书存在工程目录里，同一个签名才能覆盖安装）
$KS = Join-Path $ROOT 'planapp.keystore'
if (-not (Test-Path $KS)) {
    $old = Join-Path $TC 'planapp.keystore'
    if (Test-Path $old) {
        Copy-Item $old $KS -Force          # 沿用之前那把，保证能覆盖安装
    } else {
        & "$JDK\bin\keytool.exe" -genkeypair -keystore $KS -alias planapp -keyalg RSA -keysize 2048 `
            -validity 10950 -storepass planapp123 -keypass planapp123 `
            -dname "CN=QingZhu, OU=Personal, O=Personal, L=Beijing, ST=Beijing, C=CN"
        if ($LASTEXITCODE -ne 0) { throw 'keytool 生成签名失败' }
    }
}
$APK = Join-Path $PROJ '青竹计划.apk'
& "$BT\apksigner.bat" sign --ks $KS --ks-pass pass:planapp123 --key-pass pass:planapp123 `
    --v4-signing-enabled false --out $APK "$OUT\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw 'apksigner 签名失败' }

# 10) 校验并记录本次构建信息
& "$BT\zipalign.exe" -c 4 $APK
if ($LASTEXITCODE -ne 0) { throw 'zipalign 校验失败' }

@{ contentVersion = $ContentVersion; shellVersionCode = $ShellVersionCode; shellVersionName = $ShellVersionName } |
    ConvertTo-Json | Set-Content -Path "$OUT\build-info.json" -Encoding UTF8

$f = Get-Item $APK
"APK  OK  $APK  $([math]::Round($f.Length/1KB,1)) KB  内容版本=$ContentVersion  外壳=$ShellVersionName($ShellVersionCode)"
