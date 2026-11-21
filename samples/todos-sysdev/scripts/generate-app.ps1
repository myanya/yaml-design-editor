#Requires -Version 7.0
[CmdletBinding()]
param(
    [string] $Workspace = (Split-Path -Parent $PSScriptRoot),
    [switch] $Apply,
    [switch] $Check,
    [switch] $SkipCliBuild
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'application-files.ps1')

$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$Workspace = [IO.Path]::GetFullPath($Workspace)
$temporaryRoot = Join-Path $repository 'scratchpad/application-generation'
$stage = Join-Path $temporaryRoot ([Guid]::NewGuid().ToString('N'))
$oldGradleHome = $env:GRADLE_USER_HOME

function Get-DesignInputs([string] $Root) {
    $definitions = Join-Path $Root '.yde'
    Get-ChildItem -LiteralPath $definitions -File -Force
    foreach ($zone in @('schemas', 'views', 'transforms', 'reports', 'stages')) {
        $zonePath = Join-Path $definitions $zone
        if (Test-Path -LiteralPath $zonePath) { Get-ChildItem -LiteralPath $zonePath -File -Recurse -Force }
    }
    Get-ChildItem -LiteralPath (Join-Path $Root 'design') -File -Recurse -Force
}

try {
    if ($Apply -and $Check) { throw '-Apply と -Check は同時に指定できません' }
    if (-not (Test-Path -LiteralPath (Join-Path $Workspace '.yde/workspace.yaml'))) { throw '設計ワークスペースが見つかりません' }
    if (-not $SkipCliBuild) {
        $env:GRADLE_USER_HOME = Join-Path $env:USERPROFILE '.gradle'
        & (Join-Path $repository 'gradlew.bat') -p $repository :cli:cliDist --no-daemon --console=plain
        if ($LASTEXITCODE -ne 0) { throw 'YDE CLI のビルドに失敗しました' }
    }
    $distribution = Join-Path $repository 'cli/build/yde-cli'
    $javaHomePath = (Get-Content -LiteralPath (Join-Path $distribution 'java-home.txt') -Raw -Encoding UTF8).Trim()
    $javaExecutable = Join-Path $javaHomePath 'bin/java.exe'
    $classpath = Join-Path $distribution 'lib/*'
    [IO.Directory]::CreateDirectory($stage) | Out-Null
    $inputHashes = @{}
    foreach ($source in Get-DesignInputs $Workspace) {
        $relative = [IO.Path]::GetRelativePath($Workspace, $source.FullName).Replace('\', '/')
        $bytes = [IO.File]::ReadAllBytes($source.FullName)
        $inputHashes[$relative] = Get-ApplicationHash $bytes
        $destination = Join-Path $stage $relative
        [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($destination)) | Out-Null
        [IO.File]::WriteAllBytes($destination, $bytes)
    }
    $script:resultNumber = 0
    function Invoke-YdeResult([string[]] $CliArguments) {
        $script:resultNumber++
        $outputFile = Join-Path $stage "result-$script:resultNumber.json"
        & $javaExecutable '-Dfile.encoding=UTF-8' '-cp' $classpath 'dev.toros.yde.cli.MainKt' @CliArguments --format json --output-file $outputFile | Out-Host
        if ($LASTEXITCODE -ne 0) { throw "YDE の処理に失敗しました: $($CliArguments[0])" }
        Get-Content -LiteralPath $outputFile -Encoding UTF8 -Raw | ConvertFrom-Json
    }
    $validation = Invoke-YdeResult @('validate', $stage)
    if ($validation.errors -ne 0 -or $validation.schemaErrors.Count -ne 0) { throw '設計の検証で誤りが見つかりました。先に設計を修正してください' }
    $generation = Invoke-YdeResult @('generate', $stage, '--check')
    if (-not $generation.upToDate) { throw '設計の確定項目を再生成してから実行してください' }
    $reports = Invoke-YdeResult @('report', $stage)
    if ($reports.errors.Count -ne 0) { throw "レポート定義に誤りがあります: $($reports.errors -join ' / ')" }
    $files = @{}
    $applicationReports = @($reports.reports | Where-Object { $_.id.StartsWith('app-') })
    if ($applicationReports.Count -eq 0) { throw 'アプリの出力定義がありません' }
    $outputPlan = Invoke-YdeResult @('report', $stage, 'application-outputs')
    if ($outputPlan.missing.Count -ne 0) { throw 'アプリの出力単位を読み取れません' }
    $outputRows = @((Get-Content -LiteralPath (Join-Path $stage $outputPlan.written) -Raw -Encoding UTF8 | ConvertFrom-Json).outputs)
    if ($outputRows.Count -eq 0) { throw '確定したアプリの出力単位がありません' }
    foreach ($report in $applicationReports) {
        # 可変個数のレポートは対象がゼロでも定義を残せる。
        if ($report.params.Count -eq 0 -and $report.id -notin $outputRows.report) { throw "出力計画に登録されていないレポートです: $($report.id)" }
    }
    foreach ($output in $outputRows) {
        $reportId = $output.report
        if ($reportId -notin $reports.reports.id) { throw "出力計画のレポートが見つかりません: $reportId" }
        if ($output.PSObject.Properties.Name -contains 'destination') {
            $null = Resolve-ApplicationPath $stage $output.destination
        }
    }
    # 確定した出力計画をそのまま渡す。Java とワークスペースの開き直しは出力ごとに行わない。
    $batch = Invoke-YdeResult @('report', $stage, '--batch', (Join-Path $stage $outputPlan.written))
    if ($batch.outputs.Count -ne $outputRows.Count) { throw '出力計画と実行結果の件数が一致しません' }
    foreach ($result in $batch.outputs) {
        $relative = $result.written
        $target = Resolve-ApplicationPath $stage $relative
        if ($files.ContainsKey($relative)) { throw "生成先が重複しています: $relative" }
        $files[$relative] = [IO.File]::ReadAllBytes($target)
    }
    foreach ($assetGroup in @('gradle-wrapper', 'web')) {
        $assetsDirectory = Join-Path $Workspace "assets/$assetGroup"
        $assets = Get-Content -LiteralPath (Join-Path $assetsDirectory 'files.json') -Encoding UTF8 -Raw | ConvertFrom-Json
        foreach ($asset in $assets.files) {
            $relative = "app/$($asset.path)"
            $null = Resolve-ApplicationPath $stage $relative
            $bytes = [IO.File]::ReadAllBytes((Join-Path $assetsDirectory $asset.path))
            if ((Get-ApplicationHash $bytes) -ne $asset.sha256) { throw "固定資源が変わっています: $assetGroup/$($asset.path)" }
            if ($files.ContainsKey($relative)) {
                if ((Get-ApplicationHash $files[$relative]) -ne $asset.sha256) { throw "設定の Gradle 版と Wrapper 資源が一致しません: $($asset.path)" }
            } else { $files[$relative] = $bytes }
        }
    }
    $plan = Get-ApplicationFilePlan $Workspace $files
    [ordered]@{ files = $plan.fileCount; operations = @($plan.operations | ForEach-Object { [ordered]@{ kind = $_.kind; path = $_.path } }); apply = [bool]$Apply } | ConvertTo-Json -Depth 10 | Write-Output
    if ($Check -and $plan.operations.Count -ne 0) { throw 'アプリの生成物が最新ではありません。生成予定を確認して -Apply で作り直してください' }
    if ($Apply) {
        $currentInputs = @(Get-DesignInputs $Workspace)
        if ($currentInputs.Count -ne $inputHashes.Count) { throw '生成中に設計のファイル一覧が変わりました。再実行してください' }
        foreach ($source in $currentInputs) {
            $relative = [IO.Path]::GetRelativePath($Workspace, $source.FullName).Replace('\', '/')
            if ((Get-ApplicationHash ([IO.File]::ReadAllBytes($source.FullName))) -ne $inputHashes[$relative]) { throw "生成中に設計が変わりました: $relative" }
        }
        Save-ApplicationFilePlan $plan
    }
} finally {
    $env:GRADLE_USER_HOME = $oldGradleHome
    $resolvedStage = [IO.Path]::GetFullPath($stage)
    $resolvedTemporaryRoot = [IO.Path]::GetFullPath($temporaryRoot) + [IO.Path]::DirectorySeparatorChar
    if (-not $resolvedStage.StartsWith($resolvedTemporaryRoot, [StringComparison]::OrdinalIgnoreCase)) { throw '一時領域の場所が不正です' }
    if (Test-Path -LiteralPath $resolvedStage) { Remove-Item -LiteralPath $resolvedStage -Recurse -Force }
}
