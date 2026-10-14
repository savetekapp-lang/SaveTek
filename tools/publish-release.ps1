# =====================================================================
#  سكربت نشر إصدار SaveTek (يُشغَّل بعد tools\make-release.ps1)
#
#  الترتيب المعتمد للنشر:
#    أ. make-release.ps1: بناء الملفات وكتابة docs\version.json محلياً.
#    ب. هذا السكربت: حفظ كل التغييرات (ومنها version.json) في commit واحد.
#    ج. إنشاء الوسم vX.Y.Z على هذا الـ commit ورفع الوسم فقط (بدون الفرع main).
#    د. إنشاء الإصدار من الوسم ورفع ملفات APK، ثم التحقق أن روابط التحميل
#       تعمل وأن البصمات مطابقة لـ version.json وأن التوقيع هو المفتاح الرسمي.
#    هـ. بعد نجاح كل ما سبق فقط: رفع الفرع main ليتحدّث الموقع (version.json).
#
#  بهذا يكون version.json داخل الوسم هو نفسه المنشور في الموقع،
#  ولا يرى المستخدمون التحديث قبل أن تصبح ملفاته جاهزة للتحميل.
#
#  طريقة التشغيل (من مجلد المشروع):
#    powershell -ExecutionPolicy Bypass -File tools\publish-release.ps1 -NotesFile notes.md
#
#  خيارات:
#    -NotesFile <ملف>     وصف الإصدار (Markdown). إلزامي.
#    -CommitMessage <نص>  رسالة الـ commit (الافتراضي: "Release vX.Y.Z").
#    -SkipPushMain        توقف قبل الخطوة هـ (لرفع main يدوياً لاحقاً).
# =====================================================================
param(
    [Parameter(Mandatory = $true)][string]$NotesFile,
    [string]$CommitMessage = '',
    [switch]$SkipPushMain,
    # بصمة شهادة التوقيع الرسمية (SHA-256): أي ملف بتوقيع آخر يوقف النشر
    [string]$ExpectedCert = '4a66426907291f89ed2782a5c908ddab6656a8ee31088ac47dde9d4562235fa6'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

function Step($text) { Write-Host ""; Write-Host "== $text" -ForegroundColor Cyan }
function Fail($text) { Write-Host "STOPPED: $text" -ForegroundColor Red; exit 1 }
function GitOrFail { & git.exe @args; if ($LASTEXITCODE -ne 0) { Fail "git $($args -join ' ')" } }

# --- معلومات الإصدار ---
$gradle = Get-Content -Raw app\build.gradle.kts
$versionName = [regex]::Match($gradle, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
$tag = "v$versionName"
$config = Get-Content -Raw app\src\main\java\com\nazeeltek\savetek\AppConfig.kt
$user = [regex]::Match($config, 'GITHUB_USER\s*=\s*"([^"]+)"').Groups[1].Value
$repo = [regex]::Match($config, 'GITHUB_REPO\s*=\s*"([^"]+)"').Groups[1].Value
$outDir = Join-Path $root "release\$tag"
if (-not $CommitMessage) { $CommitMessage = "Release $tag" }

Step "Checks before publishing $tag"
if (-not (Test-Path $NotesFile)) { Fail "notes file not found: $NotesFile" }
$info = Get-Content -Raw -Encoding UTF8 docs\version.json | ConvertFrom-Json
if ($info.versionName -ne $versionName) { Fail "docs\version.json is $($info.versionName), expected $versionName. Run tools\make-release.ps1 first." }
$apks = 'arm64-v8a', 'armeabi-v7a', 'x86_64', 'universal' | ForEach-Object { Join-Path $outDir "SaveTek-$versionName-$_.apk" }
foreach ($apk in $apks) { if (-not (Test-Path $apk)) { Fail "missing $apk" } }
& git rev-parse -q --verify "refs/tags/$tag" *> $null
if ($LASTEXITCODE -eq 0) { Fail "tag $tag already exists locally" }
if (& git ls-remote --tags origin "refs/tags/$tag") { Fail "tag $tag already exists on GitHub" }

# كل ملف APK: بصمته مطابقة لـ version.json، وتوقيعه بالمفتاح الرسمي
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr' }
$buildTools = Get-ChildItem "$env:LOCALAPPDATA\Android\Sdk\build-tools" | Sort-Object Name | Select-Object -Last 1
$apksigner = Join-Path $buildTools.FullName 'apksigner.bat'
foreach ($abi in 'arm64-v8a', 'armeabi-v7a', 'x86_64', 'universal') {
    $apk = Join-Path $outDir "SaveTek-$versionName-$abi.apk"
    $hash = (Get-FileHash -Algorithm SHA256 $apk).Hash.ToLower()
    if ($hash -ne $info.abis.$abi.sha256) { Fail "$abi fingerprint differs from version.json" }
    # apksigner يطبع تحذيرات Java على stderr: لا نعتبرها خطأً، ونقرأ البصمة من الناتج فقط
    $prev = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    $out = & $apksigner verify --print-certs $apk 2>&1 | Out-String
    $ErrorActionPreference = $prev
    $cert = [regex]::Match($out, 'Signer #1 certificate SHA-256 digest: (\w+)').Groups[1].Value
    if ($cert -ne $ExpectedCert) { Fail "$abi is not signed with the official key ($cert)" }
}
Write-Host "  4 APKs: fingerprints match version.json, signed with the official key"

# --- ب. commit واحد بكل التغييرات (ومنها version.json) ---
Step "b. One commit with all changes (including version.json)"
GitOrFail add -A
$staged = & git diff --cached --name-only
# حماية: لا تُرفع المفاتيح أو ملفات APK أو خريطة التشويش أبداً
$forbidden = $staged | Where-Object { $_ -match '\.(jks|keystore|apk)$|keystore.*\.properties$|local\.properties$|mapping.*\.txt$' }
if ($forbidden) { & git reset -q; Fail "refusing to commit secret or build files: $($forbidden -join ', ')" }
if ($staged) {
    $staged | ForEach-Object { Write-Host "  + $_" }
    GitOrFail commit -q -m $CommitMessage
} else {
    Write-Host "  nothing new to commit; using the current commit"
}
& git show HEAD:docs/version.json | Out-String | ConvertFrom-Json | ForEach-Object {
    if ($_.versionName -ne $versionName) { Fail "the commit does not contain the new version.json" }
}
$commit = (& git rev-parse HEAD).Trim()
Write-Host "  commit $commit"

# --- ج. الوسم على هذا الـ commit، ورفع الوسم فقط ---
Step "c. Tag $tag on this commit and push the tag only (not main)"
GitOrFail tag -a $tag -m "SaveTek $versionName" $commit
GitOrFail push -q origin "refs/tags/$tag"

# --- د. الإصدار من الوسم + رفع الملفات + التحقق ---
Step "d. Create the release from the tag, upload APKs, verify downloads"
& gh release create $tag --verify-tag --latest --title "SaveTek $versionName" --notes-file $NotesFile @apks
if ($LASTEXITCODE -ne 0) { Fail "gh release create failed (main was NOT pushed)" }
$tmp = Join-Path $env:TEMP "savetek-verify-$tag"
New-Item -ItemType Directory -Force $tmp | Out-Null
foreach ($abi in 'arm64-v8a', 'armeabi-v7a', 'x86_64', 'universal') {
    $entry = $info.abis.$abi
    $dest = Join-Path $tmp "$abi.apk"
    Invoke-WebRequest -UseBasicParsing -Uri $entry.url -OutFile $dest
    $hash = (Get-FileHash -Algorithm SHA256 $dest).Hash.ToLower()
    if ($hash -ne $entry.sha256) { Fail "$abi downloaded file does not match version.json (main was NOT pushed)" }
    Write-Host "  OK $abi : download link works, fingerprint matches"
}
Remove-Item -Recurse -Force $tmp

# --- هـ. بعد نجاح كل شيء: رفع main ليتحدّث الموقع ---
if ($SkipPushMain) {
    Step "e. Skipped. When ready: git push origin main"
} else {
    Step "e. Push main so the website (version.json) updates"
    GitOrFail push -q origin main
}

Write-Host ""
Write-Host "Published $tag (commit $commit)." -ForegroundColor Green
Write-Host "  Release: https://github.com/$user/$repo/releases/tag/$tag"
