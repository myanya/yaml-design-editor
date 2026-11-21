#Requires -Version 7.0
[CmdletBinding()]
param([string] $Application = (Join-Path (Split-Path -Parent $PSScriptRoot) 'app'))
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$verificationRoot = Join-Path $repository 'scratchpad/application-verification'
$copy = Join-Path $verificationRoot ([Guid]::NewGuid().ToString('N'))
$oldGradleHome = $env:GRADLE_USER_HOME
[IO.Directory]::CreateDirectory($copy) | Out-Null
try {
    foreach ($file in Get-ChildItem -LiteralPath $Application -Force) {
        if ($file.Name -in @('build', '.gradle', 'data', '.git')) { continue }
        Copy-Item -LiteralPath $file.FullName -Destination $copy -Recurse -Force
    }
    $tests = Join-Path $copy 'src/test/java/verification'
    [IO.Directory]::CreateDirectory($tests) | Out-Null
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot '../verification/MapperIntegrationTest.java') -Destination $tests
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot '../verification/ServiceFoundationTest.java') -Destination $tests
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot '../verification/OperationRunnerTest.java') -Destination $tests
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot '../verification/ServiceIntegrationTest.java') -Destination $tests
    $env:GRADLE_USER_HOME = Join-Path $env:USERPROFILE '.gradle'
    & (Join-Path $copy 'gradlew.bat') -p $copy test bootJar projects --no-daemon --no-parallel --console=plain --max-workers=2
    if ($LASTEXITCODE -ne 0) { throw "アプリの検証に失敗しました。結果: $copy/build/reports/tests/test/index.html" }
    Write-Output "独立アプリのビルド・全 Mapper と全操作・確認の非更新・状態と前提の拒否・同時実行と取り消しを確認しました。結果: $copy/build/reports/tests/test/index.html"
} finally {
    $env:GRADLE_USER_HOME = $oldGradleHome
    # 失敗時も結果を確認できるよう、コピーはコミットまで作業用フォルダに残す。
    Write-Output "検証用コピー: $copy"
}
