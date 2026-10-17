# 一键构建祈歆 Alive 的 release APK
# 用法:  .\build.ps1            （release，复用祈歆签名）
#        .\build.ps1 debug      （debug 版）
param(
    [ValidateSet('release', 'debug')]
    [string] $Variant = 'release'
)

$ErrorActionPreference = 'Stop'

$root      = $PSScriptRoot
$jdk       = 'C:\Users\anzu\Documents\xin\tools\jdk\jdk-17.0.20.1+1'
$sdk       = 'C:\Users\anzu\Documents\xin\tools\android-sdk'
$gradleBin = 'C:\Users\anzu\.gradle\wrapper\dists\gradle-8.11.1-bin\eac4u065zwes5phgltp5f9b9e\gradle-8.11.1\bin\gradle.bat'

foreach ($p in @($jdk, $sdk, $gradleBin)) {
    if (-not (Test-Path $p)) { throw "找不到: $p" }
}

$env:JAVA_HOME        = $jdk
$env:ANDROID_HOME     = $sdk
$env:ANDROID_SDK_ROOT = $sdk

$task = if ($Variant -eq 'debug') { ':app:assembleDebug' } else { ':app:assembleRelease' }

Write-Host "==> 构建 $Variant ..." -ForegroundColor Cyan
& $gradleBin -p $root $task --console=plain
if ($LASTEXITCODE -ne 0) { throw "构建失败 (exit $LASTEXITCODE)" }

$apk = Get-ChildItem "$root\app\build\outputs\apk\$Variant\*.apk" |
       Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $apk) { throw "没有找到 APK" }

Write-Host ""
Write-Host "==> 产物: $($apk.FullName)" -ForegroundColor Green
Write-Host "    大小: $([math]::Round($apk.Length / 1KB, 1)) KB"

if ($Variant -eq 'release') {
    $dist = Join-Path $root 'dist'
    New-Item -ItemType Directory -Force -Path $dist | Out-Null
    $target = Join-Path $dist 'QixinAlive-v1.4.1-release.apk'
    Copy-Item $apk.FullName $target -Force
    Write-Host "    已复制: $target" -ForegroundColor Green

    $signer = Join-Path $sdk 'build-tools\36.1.0\apksigner.bat'
    if (Test-Path $signer) {
        Write-Host ""
        Write-Host "==> 校验签名 ..." -ForegroundColor Cyan
        & $signer verify --print-certs $target | Select-Object -First 3
    }
}
