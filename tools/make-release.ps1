# =====================================================================
#  سكربت إصدار SaveTek
#  يبني التطبيق، ويجهّز ملفات APK بأسماء ثابتة في مجلد release،
#  ويحسب بصمة SHA-256 وحجم كل ملف، ثم يكتب docs/version.json تلقائياً.
#
#  طريقة التشغيل (من مجلد المشروع):
#    powershell -ExecutionPolicy Bypass -File tools\make-release.ps1
#
#  خيارات:
#    -MinSupported 8   أقل إصدار مدعوم (للتحديث الإجباري). بدونه تبقى القيمة السابقة.
#    -SkipBuild        لا تبنِ من جديد (استخدم ملفات APK الموجودة).
#
#  قبل التشغيل:
#    1. ارفع versionCode و versionName في app/build.gradle.kts
#    2. اكتب "ما الجديد" بكل اللغات في tools/whats-new.json
# =====================================================================
param(
    [int]$MinSupported = -1,
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

# --- رقم الإصدار من ملف البناء ---
$gradle = Get-Content -Raw app\build.gradle.kts
$versionCode = [int]([regex]::Match($gradle, 'versionCode\s*=\s*(\d+)').Groups[1].Value)
$versionName = [regex]::Match($gradle, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value

# --- اسم الحساب والمستودع من AppConfig.kt (المكان الوحيد لهما) ---
$config = Get-Content -Raw app\src\main\java\com\nazeeltek\savetek\AppConfig.kt
$user = [regex]::Match($config, 'GITHUB_USER\s*=\s*"([^"]+)"').Groups[1].Value
$repo = [regex]::Match($config, 'GITHUB_REPO\s*=\s*"([^"]+)"').Groups[1].Value
if ($user -eq 'YOUR_GITHUB_USERNAME') {
    Write-Warning "GITHUB_USER in AppConfig.kt is still YOUR_GITHUB_USERNAME. Set your GitHub username there, then run this script again."
}

Write-Host "SaveTek $versionName (versionCode $versionCode) -> github.com/$user/$repo" -ForegroundColor Cyan

# --- البناء ---
if (-not $SkipBuild) {
    if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr' }
    & .\gradlew.bat assembleRelease
    if ($LASTEXITCODE -ne 0) { throw "Build failed." }
}

# --- نسخ الملفات بأسماء ثابتة وحساب البصمات ---
$tag = "v$versionName"
$outDir = Join-Path $root "release\$tag"
New-Item -ItemType Directory -Force $outDir | Out-Null

$abis = [ordered]@{}
foreach ($abi in 'arm64-v8a', 'armeabi-v7a', 'x86_64', 'universal') {
    $src = "app\build\outputs\apk\release\app-$abi-release.apk"
    if (-not (Test-Path $src)) { throw "Missing $src" }
    $fileName = "SaveTek-$versionName-$abi.apk"
    $dest = Join-Path $outDir $fileName
    Copy-Item $src $dest -Force
    $abis[$abi] = [ordered]@{
        url       = "https://github.com/$user/$repo/releases/download/$tag/$fileName"
        sha256    = (Get-FileHash -Algorithm SHA256 $dest).Hash.ToLower()
        sizeBytes = (Get-Item $dest).Length
    }
}

# --- خريطة التشويش (R8): احتفظ بها مع كل إصدار لقراءة تقارير الأعطال لاحقاً. لا ترفعها للعامة ---
$mapping = "app\build\outputs\mapping\release\mapping.txt"
if (Test-Path $mapping) { Copy-Item $mapping (Join-Path $outDir "mapping-$versionName.txt") -Force }

# --- أقل إصدار مدعوم: من الخيار، أو القيمة السابقة، أو 1 ---
if ($MinSupported -lt 0) {
    $MinSupported = 1
    if (Test-Path docs\version.json) {
        $old = Get-Content -Raw -Encoding UTF8 docs\version.json | ConvertFrom-Json
        if ($old.minSupportedVersionCode) { $MinSupported = [int]$old.minSupportedVersionCode }
    }
}

$whatsNew = Get-Content -Raw -Encoding UTF8 tools\whats-new.json | ConvertFrom-Json
$main = $abis['arm64-v8a']

$info = [ordered]@{
    versionCode             = $versionCode
    versionName             = $versionName
    releaseDate             = (Get-Date -Format 'yyyy-MM-dd')
    apkUrl                  = $main.url
    sha256                  = $main.sha256
    apkSizeBytes            = $main.sizeBytes
    minSupportedVersionCode = $MinSupported
    releasePage             = "https://github.com/$user/$repo/releases/tag/$tag"
    abis                    = $abis
    whatsNew                = $whatsNew
}

# UTF-8 بدون BOM (بعض القرّاء يرفضون الملف إذا كان فيه BOM)
$json = $info | ConvertTo-Json -Depth 6
[IO.File]::WriteAllText((Join-Path $root 'docs\version.json'), $json, (New-Object Text.UTF8Encoding($false)))

Write-Host ""
Write-Host "Done." -ForegroundColor Green
Write-Host "  APK files : $outDir"
Write-Host "  version.json updated : docs\version.json"
Write-Host ""
Write-Host "Next steps:"
Write-Host "  1. On GitHub, create a release with tag $tag and upload the 4 APK files from $outDir"
Write-Host "  2. Commit and push the docs folder (docs\version.json)."
Write-Host "  Order matters: publish the release BEFORE pushing version.json."
