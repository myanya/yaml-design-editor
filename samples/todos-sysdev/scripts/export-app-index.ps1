#Requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $Destination,
    [string] $Repository = ([IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..')))
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'application-files.ps1')
$Repository = [IO.Path]::GetFullPath($Repository).Replace('\', '/')
$Destination = [IO.Path]::GetFullPath($Destination).Replace('\', '/')
$sample = 'samples/todos-sysdev'

function Invoke-GitWithPaths([string] $Directory, [string[]] $Arguments, [string[]] $InputPaths) {
    # PowerShell のパイプが加える CR をファイル名に含めないよう、NUL 区切りで渡す。
    $start = [Diagnostics.ProcessStartInfo]::new('git')
    $start.WorkingDirectory = $Directory
    $start.UseShellExecute = $false
    $start.RedirectStandardInput = $true
    $start.RedirectStandardOutput = $true
    $start.StandardInputEncoding = [Text.UTF8Encoding]::new($false)
    $start.StandardOutputEncoding = [Text.UTF8Encoding]::new($false)
    foreach ($argument in @('-c', "safe.directory=$Directory") + $Arguments) { $start.ArgumentList.Add($argument) }
    $process = [Diagnostics.Process]::Start($start)
    try {
        $process.StandardInput.Write(($InputPaths -join "`0") + "`0")
        $process.StandardInput.Close()
        $output = $process.StandardOutput.ReadToEnd()
        $process.WaitForExit()
        [pscustomobject]@{ code = $process.ExitCode; output = $output }
    } finally { $process.Dispose() }
}

# 作業ツリーにだけあるファイルで登録漏れを補わず、次のコミットに入る内容を取り出す。
if (Test-Path -LiteralPath $Destination) { throw "取り出し先は新しいフォルダを指定してください: $Destination" }
$paths = @(& git -c "safe.directory=$Repository" -C $Repository -c core.quotePath=false ls-files -- $sample .gitattributes .gitignore)
if ($LASTEXITCODE -ne 0) { throw 'Git の登録ファイル一覧を取得できません' }
if ($paths.Count -eq 0) { throw 'Git に登録された設計とアプリがありません' }
if (@($paths | Where-Object { $_ -like "$sample/app/data/*" }).Count -ne 0) {
    throw 'app/data の保存データが Git に登録されています。登録を外してください'
}
[IO.Directory]::CreateDirectory($Destination) | Out-Null
$prefix = $Destination.Replace('\', '/') + '/'
$checkout = Invoke-GitWithPaths $Repository @('checkout-index', "--prefix=$prefix", '--stdin', '-z') $paths
if ($checkout.code -ne 0) { throw 'Git の登録内容を取り出せません' }
$workspace = Join-Path $Destination $sample
$manifestPath = Join-Path $workspace 'design/detail/reports/application-files.json'
if (-not (Test-Path -LiteralPath $manifestPath)) { throw 'アプリの生成記録を Git に登録してください' }
$manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
$files = @{}
foreach ($file in $manifest.files) {
    $target = Resolve-ApplicationPath $workspace $file.path
    if ($file.path.StartsWith('app/data/')) { throw 'app/data は生成記録に含められません' }
    if (-not (Test-Path -LiteralPath $target -PathType Leaf)) {
        throw "Git に未登録の生成物があります: $($file.path)。生成物を git add して再実行してください"
    }
    $files[$file.path] = [IO.File]::ReadAllBytes($target)
}
$plan = Get-ApplicationFilePlan $workspace $files
if ($plan.operations.Count -ne 0) { throw 'Git に登録したアプリと生成記録が一致しません' }

# 取り出した除外規則を Git 自身で検査する。利用者の全体設定には依存させない。
& git -C $Destination init --quiet
if ($LASTEXITCODE -ne 0) { throw '除外規則の検査用 Git を用意できません' }
$emptyExcludes = Join-Path $Destination '.git/empty-excludes'
[IO.File]::WriteAllText($emptyExcludes, '')
foreach ($relative in @('app/data/todos.mv.db', 'app/data/todos.trace.db', 'app/data/todos.lock.db', 'app/data/backups/todos.mv.db')) {
    & git -c "safe.directory=$Destination" -C $Destination -c "core.excludesFile=$emptyExcludes" check-ignore --quiet --no-index -- "$sample/$relative"
    if ($LASTEXITCODE -ne 0) { throw "保存データが Git の除外対象ではありません: $relative" }
}
$ignored = Invoke-GitWithPaths $Destination @('-c', "core.excludesFile=$emptyExcludes", 'check-ignore', '--no-index', '--stdin', '-z') @($manifest.files | ForEach-Object { "$sample/$($_.path)" })
if ($ignored.code -gt 1) { throw '生成物の除外規則を検査できません' }
if ($ignored.code -eq 0) { throw "生成物が Git の除外対象です: $($ignored.output.Replace("`0", ', '))" }
Write-Output "Git 登録内容: $($files.Count) ファイルと生成記録が一致し、保存データは除外されています。取り出し先: $workspace"
