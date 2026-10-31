param(
    [Parameter(Mandatory = $true)][string]$JdkHome,
    [string]$Distribution,
    [string]$AppVersion,
    [string]$BuildVersion,
    [string]$Commit
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
$OutputEncoding = [Console]::OutputEncoding
$repoRoot = Split-Path $PSScriptRoot -Parent
if (-not $Distribution) { $Distribution = Join-Path $repoRoot 'desktop/build/compose/binaries/main/app/yaml-design-editor' }
$runtime = Join-Path $distribution 'runtime'
$testClasses = Join-Path $repoRoot 'desktop/build/classes/kotlin/test'
$serverTestClasses = Join-Path $repoRoot 'local-server/build/classes/kotlin/test'
$smoke = 'dev.toros.yde.desktop.DistributionSmoke'
if (-not (Test-Path "$runtime/lib/modules")) { throw '先に createDistributable を実行してください' }
if (-not (Test-Path (Join-Path $testClasses (($smoke -replace '\.', '/') + '.class')))) { throw '先に testClasses を実行してください' }
if (-not (Test-Path (Join-Path $serverTestClasses 'dev/toros/yde/server/LocalServerDistributionSmoke.class'))) { throw '先に :local-server:testClasses を実行してください' }
$runtimeVersion = (Get-Content "$runtime/release" | Where-Object { $_ -like 'JAVA_VERSION=*' })
$jdkVersion = (Get-Content "$JdkHome/release" | Where-Object { $_ -like 'JAVA_VERSION=*' })
if ($runtimeVersion -ne $jdkVersion) { throw '配布ビルドと同じバージョンの JDK を指定してください' }

# 配布物には java.exe が無いので、検証用の写しへ同じ JDK のランチャーだけを足す。
# モジュールと DLL は配布物そのものを使い、元の配布物は変更しない。
$stage = Join-Path $repoRoot ('build/distribution-smoke/' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $stage | Out-Null
Copy-Item -LiteralPath $runtime -Destination "$stage/runtime" -Recurse
Copy-Item -LiteralPath "$JdkHome/bin/java.exe" -Destination "$stage/runtime/bin/java.exe"
function Get-ModuleHash([string]$FilePath) {
    $stream = [IO.File]::OpenRead($FilePath)
    $hash = [Security.Cryptography.SHA256]::Create()
    try { return [BitConverter]::ToString($hash.ComputeHash($stream)) }
    finally { $stream.Dispose(); $hash.Dispose() }
}
if ((Get-ModuleHash "$runtime/lib/modules") -ne (Get-ModuleHash "$stage/runtime/lib/modules")) {
    throw '検証用ランタイムの内容が配布物と一致しません'
}
foreach ($relative in @('LICENSE', 'THIRD-PARTY-NOTICES.txt', 'SOURCES.txt', 'dependencies/THIRD-PARTY-NOTICES.txt', 'upstream/SOURCES.txt', 'cica/LICENSE.txt')) {
    if (-not (Test-Path "$distribution/app/resources/licenses/$relative")) { throw "配布物の表示が不足しています: $relative" }
}
$expected = @()
if ($AppVersion) { $expected = @($AppVersion, $BuildVersion, $Commit) }
$smokeJavaOptions = @('-Djava.awt.headless=true', '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8')
& "$stage/runtime/bin/java.exe" @smokeJavaOptions '-cp' "$testClasses;$distribution/app/*" $smoke @expected
if ($LASTEXITCODE -ne 0) { throw "同梱ランタイムの検査に失敗しました: $LASTEXITCODE" }
& "$stage/runtime/bin/java.exe" @smokeJavaOptions '-cp' "$serverTestClasses;$distribution/app/*" 'dev.toros.yde.server.LocalServerDistributionSmoke'
if ($LASTEXITCODE -ne 0) { throw "ローカル接続の同梱ランタイム検査に失敗しました: $LASTEXITCODE" }
