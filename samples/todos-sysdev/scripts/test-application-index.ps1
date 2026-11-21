#Requires -Version 7.0
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'application-files.ps1')
$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$temporaryRoot = Join-Path $repository 'scratchpad/application-index-tests'
$root = Join-Path $temporaryRoot ([Guid]::NewGuid().ToString('N'))
$source = Join-Path $root 'source'
$sample = 'samples/todos-sysdev'
$workspace = Join-Path $source $sample
$utf8 = [Text.UTF8Encoding]::new($false)
function Assert-Result([bool] $Result, [string] $Message) { if (-not $Result) { throw $Message } }
function Assert-Fails([scriptblock] $Run, [string] $Text) {
    try { & $Run } catch { if ("$_".Contains($Text)) { return }; throw }
    throw "拒否されませんでした: $Text"
}
function Invoke-GitTest([string[]] $Arguments) {
    & git -c "safe.directory=$source" -C $source @Arguments
    if ($LASTEXITCODE -ne 0) { throw "検証用 Git の操作に失敗しました: $Arguments" }
}
try {
    [IO.Directory]::CreateDirectory($source) | Out-Null
    Invoke-GitTest @('init', '--quiet')
    [IO.File]::WriteAllText((Join-Path $source '.gitattributes'), "* text=auto eol=lf`n*.bat text eol=crlf`n", $utf8)
    $java = 'app/src/main/java/example/todos/data/model/TodoRow.java'
    $initial = @{
        'app/.gitignore' = $utf8.GetBytes("data/`n")
        'app/gradlew.bat' = $utf8.GetBytes("@echo off`r`n")
        $java = $utf8.GetBytes("class TodoRow {}`n")
    }
    Save-ApplicationFilePlan (Get-ApplicationFilePlan $workspace $initial)
    Invoke-GitTest @('add', '--', '.gitattributes', $sample)
    # ファイルが手元に存在しても、Git から除外された状態では拒否する。
    Assert-Fails {
        & (Join-Path $PSScriptRoot 'export-app-index.ps1') -Repository $source -Destination (Join-Path $root 'missing')
    } 'Git に未登録の生成物'
    # 強制登録だけで隠した場合も、除外規則の誤りを検出する。
    Invoke-GitTest @('add', '-f', '--', "$sample/$java")
    Assert-Fails {
        & (Join-Path $PSScriptRoot 'export-app-index.ps1') -Repository $source -Destination (Join-Path $root 'ignored-source')
    } '生成物が Git の除外対象'
    $initial['app/.gitignore'] = [IO.File]::ReadAllBytes((Join-Path $PSScriptRoot '../.yde/reports/application/gitignore.txt'))
    Save-ApplicationFilePlan (Get-ApplicationFilePlan $workspace $initial)
    Invoke-GitTest @('add', '--', $sample)

    # 実 DB ではなく文字の試験データ。未登録のソースも一緒に置く。
    $database = Join-Path $workspace 'app/data/todos.mv.db'
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($database)) | Out-Null
    [IO.File]::WriteAllText($database, '試験用の保存データ', $utf8)
    [IO.File]::WriteAllText((Join-Path $workspace $java), 'ステージしていない変更', $utf8)
    [IO.File]::WriteAllText((Join-Path $workspace 'app/Untracked.java'), '未登録のソース', $utf8)
    $destination = Join-Path $root 'complete'
    & (Join-Path $PSScriptRoot 'export-app-index.ps1') -Repository $source -Destination $destination
    Assert-Result ([IO.File]::ReadAllText((Join-Path $destination "$sample/$java")) -eq "class TodoRow {}`n") '作業ツリーの内容が混入した'
    Assert-Result (-not (Test-Path -LiteralPath (Join-Path $destination "$sample/app/Untracked.java"))) '未登録のソースをコピーした'
    Assert-Result (-not (Test-Path -LiteralPath (Join-Path $destination "$sample/app/data"))) '保存データをコピーした'
    Assert-Result ([IO.File]::ReadAllText($database) -eq '試験用の保存データ') '元の保存データが変わった'
    Assert-Result ([IO.File]::ReadAllText((Join-Path $destination "$sample/app/gradlew.bat")) -eq "@echo off`r`n") 'バッチの改行が取り出し時に変わった'
    Assert-Fails {
        & (Join-Path $PSScriptRoot 'export-app-index.ps1') -Repository $source -Destination $destination
    } '取り出し先は新しいフォルダ'

    Invoke-GitTest @('add', '-f', '--', "$sample/app/data/todos.mv.db")
    Assert-Fails {
        & (Join-Path $PSScriptRoot 'export-app-index.ps1') -Repository $source -Destination (Join-Path $root 'registered-data')
    } '保存データが Git に登録されています'
    Invoke-GitTest @('rm', '--cached', '--quiet', '--', "$sample/app/data/todos.mv.db")
    # ステージしたソースと生成記録が異なる場合も、手元の正しい値で補わない。
    Invoke-GitTest @('add', '--', "$sample/$java")
    Assert-Fails {
        & (Join-Path $PSScriptRoot 'export-app-index.ps1') -Repository $source -Destination (Join-Path $root 'changed-source')
    } '生成後に変更または削除'
    [IO.File]::WriteAllBytes((Join-Path $workspace $java), $initial[$java])
    $initial['app/.gitignore'] = [byte[]]@()
    Save-ApplicationFilePlan (Get-ApplicationFilePlan $workspace $initial)
    Invoke-GitTest @('add', '--', "$sample/$java", "$sample/app/.gitignore", "$sample/design/detail/reports/application-files.json")
    Assert-Fails {
        & (Join-Path $PSScriptRoot 'export-app-index.ps1') -Repository $source -Destination (Join-Path $root 'unprotected-data')
    } '保存データが Git の除外対象ではありません'
    Write-Output 'Git の登録漏れ・除外規則・登録内容の分離・保存データの保護・改行・生成記録の一致: OK'
} finally {
    $resolved = [IO.Path]::GetFullPath($root)
    if (-not $resolved.StartsWith([IO.Path]::GetFullPath($temporaryRoot) + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw '一時領域の場所が不正です' }
    if (Test-Path -LiteralPath $resolved) { Remove-Item -LiteralPath $resolved -Recurse -Force }
}
# 拒否を確認した Git コマンドの終了値を、検証全体の失敗として返さない。
exit 0
