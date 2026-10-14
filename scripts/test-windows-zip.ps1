param(
    [Parameter(Mandatory = $true)][string]$ArchivePath,
    [Parameter(Mandatory = $true)][string]$JdkHome,
    [Parameter(Mandatory = $true)][string]$AppVersion,
    [Parameter(Mandatory = $true)][string]$BuildVersion,
    [Parameter(Mandatory = $true)][string]$Commit
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Get-ContentHash([string]$FilePath) {
    $stream = [IO.File]::OpenRead($FilePath)
    $hash = [Security.Cryptography.SHA256]::Create()
    try { return [BitConverter]::ToString($hash.ComputeHash($stream)) }
    finally { $stream.Dispose(); $hash.Dispose() }
}

# 毎回新しい検証場所へ展開し、元のアプリや以前の検査結果を上書きしない。
$stage = Join-Path $repoRoot ('build/zv/' + [Guid]::NewGuid().ToString('N').Substring(0, 8))
if ([IO.Directory]::Exists($stage)) { throw '検証先が既に存在します。再実行してください' }
$archive = [IO.Compression.ZipFile]::OpenRead($ArchivePath)
$names = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
try {
    foreach ($entry in $archive.Entries) {
        $name = $entry.FullName
        if (-not $name.StartsWith('yaml-design-editor/') -or $name.Contains('\') -or $name.Contains(':') -or
            ($name.Split('/') -contains '..') -or -not $names.Add($name)) {
            throw "ZIP 内のパスが不正または重複しています: $name"
        }
    }
} finally { $archive.Dispose() }
[IO.Compression.ZipFile]::ExtractToDirectory($ArchivePath, $stage)
$distribution = Join-Path $stage 'yaml-design-editor'
$image = Join-Path $repoRoot 'build/compose/binaries/main/app/yaml-design-editor'

# 配布物以外のローカル設定・ログ等が入っていないこと、内容が元の生成物と一致することを検査する。
$imageFiles = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
foreach ($file in [IO.Directory]::GetFiles($image, '*', [IO.SearchOption]::AllDirectories)) {
    $relative = $file.Substring($image.Length + 1).Replace('\', '/')
    [void]$imageFiles.Add($relative)
    $copy = Join-Path $distribution $relative
    if (-not [IO.File]::Exists($copy) -or (Get-ContentHash $file) -ne (Get-ContentHash $copy)) {
        throw "ZIP でファイルが欠落または変化しています: $relative"
    }
}
foreach ($file in [IO.Directory]::GetFiles($distribution, '*', [IO.SearchOption]::AllDirectories)) {
    $relative = $file.Substring($distribution.Length + 1).Replace('\', '/')
    if ($imageFiles.Contains($relative)) { continue }
    $source = switch -Regex ($relative) {
        '^README\.txt$' { Join-Path $repoRoot 'distribution/windows/README.txt'; break }
        '^(BUILD-INFO|RUNTIME-RELEASE)\.txt$' { Join-Path $repoRoot "build/generated/windows-release/$relative"; break }
        '^examples/todos/(README\.md|\.yde/workspace\.yaml|\.yde/(schemas|views)/[^/]+\.yaml|todos/([^/]+|details/[^/]+)\.yaml)$' {
            Join-Path $repoRoot ('samples/todos/' + $relative.Substring('examples/todos/'.Length)); break
        }
        default { throw "ZIP に想定外のファイルがあります: $relative" }
    }
    if ((Get-ContentHash $file) -ne (Get-ContentHash $source)) { throw "追加資源の内容が一致しません: $relative" }
}
foreach ($required in @('yaml-design-editor.exe', 'README.txt', 'BUILD-INFO.txt', 'RUNTIME-RELEASE.txt',
    'examples/todos/.yde/workspace.yaml', 'examples/todos/.yde/schemas/common.yaml', 'examples/todos/todos/todo-list.yaml')) {
    if (-not [IO.File]::Exists((Join-Path $distribution $required))) { throw "ZIP に必要なファイルがありません: $required" }
}
$info = [IO.File]::ReadAllLines((Join-Path $distribution 'BUILD-INFO.txt'))
foreach ($line in @("appVersion=$AppVersion", "buildVersion=$BuildVersion", "commit=$Commit", 'platform=windows-x64')) {
    if ($info -notcontains $line) { throw "ZIP のビルド情報が一致しません: $line" }
}
& "$PSScriptRoot/test-distribution.ps1" -JdkHome $JdkHome -Distribution $distribution `
    -AppVersion $AppVersion -BuildVersion $BuildVersion -Commit $Commit
Write-Output "ZIP の内容・展開・同梱ランタイム・ビルド情報 OK: $ArchivePath"
