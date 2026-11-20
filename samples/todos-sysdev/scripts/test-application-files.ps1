#Requires -Version 7.0
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'application-files.ps1')
$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$temporaryRoot = Join-Path $repository 'scratchpad/application-file-tests'
$root = Join-Path $temporaryRoot ([Guid]::NewGuid().ToString('N'))
$utf8 = [Text.UTF8Encoding]::new($false)
function Assert-Result([bool] $Result, [string] $Message) { if (-not $Result) { throw $Message } }
function Assert-Fails([scriptblock] $Run, [string] $Text) {
    try { & $Run } catch { if ("$_".Contains($Text)) { return }; throw }
    throw "拒否されませんでした: $Text"
}
try {
    [IO.Directory]::CreateDirectory($root) | Out-Null
    $initial = @{ 'app/a.txt' = $utf8.GetBytes('A'); 'app/b.txt' = $utf8.GetBytes('B'); 'app/empty.txt' = [byte[]]@() }
    Save-ApplicationFilePlan (Get-ApplicationFilePlan $root $initial)
    Assert-Result ([IO.File]::Exists((Join-Path $root 'app/empty.txt'))) '空のファイルも生成する'
    Assert-Result ((Get-ApplicationFilePlan $root $initial).operations.Count -eq 0) '再生成に差分がある'
    $record = [IO.File]::ReadAllText((Join-Path $root 'design/detail/reports/application-files.json'))
    $updated = @{ 'app/a.txt' = $utf8.GetBytes('AA'); 'app/b.txt' = $utf8.GetBytes('BB'); 'app/new.txt' = $utf8.GetBytes('NEW') }
    $plan = Get-ApplicationFilePlan $root $updated
    Assert-Fails {
        Save-ApplicationFilePlan $plan -WriteFile {
            param($target, $bytes)
            if ($target.EndsWith('b.txt')) { throw '書込失敗の検証' }
            Write-ApplicationBytes $target $bytes
        }
    } '書込失敗の検証'
    Assert-Result ([IO.File]::ReadAllText((Join-Path $root 'app/a.txt')) -eq 'A') '失敗前の更新が復元されない'
    Assert-Result ((Get-ApplicationFilePlan $root $initial).operations.Count -eq 0) '途中失敗後のファイルが元と異なる'
    Assert-Result ([IO.File]::ReadAllText((Join-Path $root 'design/detail/reports/application-files.json')) -eq $record) '途中失敗で記録が変わった'
    [IO.File]::WriteAllText((Join-Path $root 'app/a.txt'), '手修正')
    Assert-Fails { Get-ApplicationFilePlan $root $updated } '変更または削除'
    Assert-Result ([IO.File]::ReadAllText((Join-Path $root 'app/a.txt')) -eq '手修正') '競合時に手修正を変更した'
    [IO.File]::WriteAllBytes((Join-Path $root 'app/a.txt'), $initial['app/a.txt'])
    $plan = Get-ApplicationFilePlan $root $updated
    [IO.File]::WriteAllText((Join-Path $root 'app/b.txt'), '計画後の変更')
    Assert-Fails { Save-ApplicationFilePlan $plan } '計画後に変更'
    Assert-Result ([IO.File]::ReadAllText((Join-Path $root 'app/a.txt')) -eq 'A') '計画後の競合で別ファイルを更新した'
    [IO.File]::WriteAllBytes((Join-Path $root 'app/b.txt'), $initial['app/b.txt'])
    $unchangedSibling = @{ 'app/a.txt' = $utf8.GetBytes('AA'); 'app/b.txt' = $initial['app/b.txt']; 'app/empty.txt' = [byte[]]@() }
    $plan = Get-ApplicationFilePlan $root $unchangedSibling
    [IO.File]::WriteAllText((Join-Path $root 'app/b.txt'), '更新対象でないファイルの変更')
    Assert-Fails { Save-ApplicationFilePlan $plan } '計画後に変更'
    Assert-Result ([IO.File]::ReadAllText((Join-Path $root 'app/a.txt')) -eq 'A') '更新対象以外の競合で別ファイルを更新した'
    [IO.File]::WriteAllBytes((Join-Path $root 'app/b.txt'), $initial['app/b.txt'])
    [IO.File]::WriteAllText((Join-Path $root 'app/handwritten.txt'), '残す')
    $renamed = @{ 'app/renamed.txt' = $utf8.GetBytes('A') }
    Save-ApplicationFilePlan (Get-ApplicationFilePlan $root $renamed)
    Assert-Result (-not [IO.File]::Exists((Join-Path $root 'app/a.txt'))) '名前変更前の生成ファイルが残る'
    Assert-Result (-not [IO.File]::Exists((Join-Path $root 'app/b.txt'))) '不要になった生成ファイルが残る'
    Assert-Result ([IO.File]::ReadAllText((Join-Path $root 'app/handwritten.txt')) -eq '残す') '記録にないファイルを削除した'
    Assert-Fails { Get-ApplicationFilePlan $root @{ 'app/handwritten.txt' = $utf8.GetBytes('別の値') } } '生成記録のない'
    Assert-Fails { Get-ApplicationFilePlan $root @{ 'app/../outside.txt' = $utf8.GetBytes('禁止') } } 'アプリの外'
    Write-Output '生成・一致・空ファイル・途中失敗の復元・手修正・計画後の競合・名前変更・削除範囲: OK'
} finally {
    $resolved = [IO.Path]::GetFullPath($root)
    if (-not $resolved.StartsWith([IO.Path]::GetFullPath($temporaryRoot) + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw '一時領域の場所が不正です' }
    if (Test-Path -LiteralPath $resolved) { Remove-Item -LiteralPath $resolved -Recurse -Force }
}
