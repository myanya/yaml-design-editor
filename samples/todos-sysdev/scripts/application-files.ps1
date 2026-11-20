# 生成したファイルだけを更新する。Java・SQL・HTML の組み立ては YDE に任せる。
Set-StrictMode -Version Latest

function Get-ApplicationHash([byte[]] $Bytes) {
    $sha = [Security.Cryptography.SHA256]::Create()
    try { [BitConverter]::ToString($sha.ComputeHash($Bytes)).Replace('-', '').ToLowerInvariant() }
    finally { $sha.Dispose() }
}

function Assert-ApplicationPath([string] $Root, [string] $Target) {
    for ($entry = $Target; $entry -and $entry -ne $Root; $entry = [IO.Path]::GetDirectoryName($entry)) {
        if (Test-Path -LiteralPath $entry) {
            if ((Get-Item -LiteralPath $entry -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) {
                throw "リンクを経由する生成先は使えません: $Target"
            }
        }
    }
}

function Resolve-ApplicationPath([string] $Root, [string] $Relative) {
    if ($Relative -notmatch '^app/[^\\:]+$' -or $Relative.Split('/') -contains '..') {
        throw "アプリの外を生成先にはできません: $Relative"
    }
    $target = [IO.Path]::GetFullPath((Join-Path $Root $Relative))
    $appRoot = [IO.Path]::GetFullPath((Join-Path $Root 'app')) + [IO.Path]::DirectorySeparatorChar
    if (-not $target.StartsWith($appRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw "アプリの外を生成先にはできません: $Relative"
    }
    # リンクを経由して別の場所を書き換えない。
    Assert-ApplicationPath $Root $target
    $target
}

function Get-ApplicationFilePlan([string] $Root, [System.Collections.IDictionary] $Files) {
    $Root = [IO.Path]::GetFullPath($Root)
    $manifestRelative = 'design/detail/reports/application-files.json'
    $manifestPath = Join-Path $Root $manifestRelative
    Assert-ApplicationPath $Root $manifestPath
    $beforeManifest = if ([IO.File]::Exists($manifestPath)) { ,([IO.File]::ReadAllBytes($manifestPath)) } else { $null }
    $previous = @{}
    if ($null -ne $beforeManifest) {
        $manifest = [Text.Encoding]::UTF8.GetString($beforeManifest) | ConvertFrom-Json
        if ($manifest.format -ne 1) { throw 'アプリ生成記録の形式が未対応です' }
        foreach ($file in $manifest.files) {
            $null = Resolve-ApplicationPath $Root $file.path
            if ($previous.ContainsKey($file.path) -or $file.sha256 -notmatch '^[a-f0-9]{64}$') {
                throw "アプリ生成記録が不正です: $($file.path)"
            }
            $previous[$file.path] = $file.sha256
        }
    }
    $operations = [Collections.Generic.List[object]]::new()
    $checks = [Collections.Generic.List[object]]::new()
    $nextManifest = [Collections.Generic.List[object]]::new()
    $all = @(@($Files.Keys) + @($previous.Keys) | Sort-Object -Unique)
    foreach ($relative in $all) {
        $target = Resolve-ApplicationPath $Root $relative
        if (Test-Path -LiteralPath $target -PathType Container) { throw "生成先にフォルダがあります: $relative" }
        $before = if ([IO.File]::Exists($target)) { ,([IO.File]::ReadAllBytes($target)) } else { $null }
        $beforeHash = if ($null -ne $before) { Get-ApplicationHash $before } else { $null }
        $after = if ($Files.Contains($relative)) { ,([byte[]]$Files[$relative]) } else { $null }
        $afterHash = if ($null -ne $after) { Get-ApplicationHash $after } else { $null }
        $checks.Add([pscustomobject]@{ path = $relative; target = $target; beforeHash = $beforeHash; afterHash = $afterHash })
        if ($previous.ContainsKey($relative)) {
            if ($beforeHash -ne $previous[$relative]) { throw "生成後に変更または削除されています: $relative。変更を設計の入力か生成規則へ戻してください" }
        } elseif ($null -ne $before -and $beforeHash -ne $afterHash) {
            throw "生成記録のないファイルと競合しています: $relative"
        }
        if ($null -ne $after) { $nextManifest.Add([ordered]@{ path = $relative; sha256 = $afterHash }) }
        if ($beforeHash -ne $afterHash) {
            $kind = if ($null -eq $before) { 'create' } elseif ($null -eq $after) { 'delete' } else { 'update' }
            $operations.Add([pscustomobject]@{ kind = $kind; path = $relative; target = $target; before = $before; after = $after; beforeHash = $beforeHash; afterHash = $afterHash })
        }
    }
    $manifestText = ([ordered]@{ format = 1; files = @($nextManifest.ToArray()) } | ConvertTo-Json -Depth 10).Replace("`r`n", "`n") + "`n"
    $manifestBytes = [Text.UTF8Encoding]::new($false).GetBytes($manifestText)
    $manifestHash = Get-ApplicationHash $manifestBytes
    $beforeManifestHash = if ($null -ne $beforeManifest) { Get-ApplicationHash $beforeManifest } else { $null }
    $checks.Add([pscustomobject]@{ path = $manifestRelative; target = $manifestPath; beforeHash = $beforeManifestHash; afterHash = $manifestHash })
    if ($manifestHash -ne $beforeManifestHash) {
        $operations.Add([pscustomobject]@{ kind = 'record'; path = $manifestRelative; target = $manifestPath; before = $beforeManifest; after = $manifestBytes; beforeHash = $beforeManifestHash; afterHash = $manifestHash })
    }
    [pscustomobject]@{ root = $Root; operations = @($operations.ToArray()); checks = @($checks.ToArray()); fileCount = $Files.Count }
}

function Write-ApplicationBytes([string] $Target, [byte[]] $Bytes) {
    if ($null -eq $Bytes) { [IO.File]::Delete($Target); return }
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($Target)) | Out-Null
    # 同じ場所の一時ファイルから置き換え、書込失敗で元のファイルを途中まで切らない。
    $temporary = $Target + '.yde-' + [Guid]::NewGuid().ToString('N') + '.tmp'
    try {
        [IO.File]::WriteAllBytes($temporary, $Bytes)
        [IO.File]::Move($temporary, $Target, $true)
    } finally {
        if ([IO.File]::Exists($temporary)) { [IO.File]::Delete($temporary) }
    }
}

function Save-ApplicationFilePlan($Plan, [scriptblock] $WriteFile = ${function:Write-ApplicationBytes}) {
    # 全件の計画後にも変わっていないことを確認してから適用する。
    foreach ($operation in $Plan.checks) {
        Assert-ApplicationPath $Plan.root $operation.target
        if ([IO.Directory]::Exists($operation.target)) { throw "生成先にフォルダがあります: $($operation.path)" }
        $currentHash = if ([IO.File]::Exists($operation.target)) { Get-ApplicationHash ([IO.File]::ReadAllBytes($operation.target)) } else { $null }
        if ($currentHash -ne $operation.beforeHash) { throw "計画後に変更されました。再生成をやり直してください: $($operation.path)" }
    }
    $written = [Collections.Generic.List[object]]::new()
    try {
        foreach ($operation in $Plan.operations) {
            Assert-ApplicationPath $Plan.root $operation.target
            $currentHash = if ([IO.File]::Exists($operation.target)) { Get-ApplicationHash ([IO.File]::ReadAllBytes($operation.target)) } else { $null }
            if ($currentHash -ne $operation.beforeHash) { throw "適用中に変更されました: $($operation.path)" }
            $written.Add($operation)
            & $WriteFile $operation.target $operation.after
        }
        foreach ($check in $Plan.checks) {
            Assert-ApplicationPath $Plan.root $check.target
            $currentHash = if ([IO.File]::Exists($check.target)) { Get-ApplicationHash ([IO.File]::ReadAllBytes($check.target)) } else { $null }
            if ($currentHash -ne $check.afterHash) { throw "適用中に変更されました: $($check.path)" }
        }
    } catch {
        $failure = $_
        $restoreErrors = [Collections.Generic.List[string]]::new()
        for ($index = $written.Count - 1; $index -ge 0; $index--) {
            $operation = $written[$index]
            try {
                Assert-ApplicationPath $Plan.root $operation.target
                $currentHash = if ([IO.File]::Exists($operation.target)) { Get-ApplicationHash ([IO.File]::ReadAllBytes($operation.target)) } else { $null }
                if ($currentHash -eq $operation.beforeHash) { continue }
                if ($currentHash -ne $operation.afterHash) { throw '別の変更が入りました' }
                Write-ApplicationBytes $operation.target $operation.before
            } catch { $restoreErrors.Add("$($operation.path): $_") }
        }
        if ($restoreErrors.Count -ne 0) { throw "生成に失敗しました: $failure。復元できないファイル: $($restoreErrors -join ' / ')" }
        throw $failure
    }
}
