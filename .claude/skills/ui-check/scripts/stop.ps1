# 画面の確認で起動したアプリを止める（Windows）。止めたプロセスの番号を出す。
# 止めるのは、アプリ本体と Hot Reload の道具の JVM（どちらも起動行に dev.toros.yde.desktop.MainKt を含む）と、
# hotRun を走らせた Gradle の呼び出し側（gradle-wrapper.jar hotRun）。Gradle デーモンは止めない（ほかのビルドを壊すため）。
# -Mcp を付けると、このセッションの Hot Reload の MCP サーバ（.mcp.json から起動した系列）と、前のセッションの取り残しも止める
# （scripts/hot-mcp-server.ps1 -StopSession）。止めた MCP サーバは、同じセッションでは /mcp の再接続までつながらない。
# 使い方: powershell -NoProfile -ExecutionPolicy Bypass -File .claude/skills/ui-check/scripts/stop.ps1 [-Mcp]
param([switch]$Mcp)

$targets = Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object {
    $c = $_.CommandLine
    $c -and $c -notmatch 'hotMcpServer|ComposeHotReloadMcp|GradleDaemon' -and (
        $c -match 'dev\.toros\.yde\.desktop\.MainKt' -or
        ($c -match 'gradle-wrapper\.jar|GradleWrapperMain' -and $c -match '\bhotRun\b')
    )
}
if (-not $targets) {
    Write-Output '止めるアプリはありません'
}
foreach ($p in $targets) {
    Stop-Process -Id $p.ProcessId -Force -Confirm:$false
    Write-Output ("止めました: {0}" -f $p.ProcessId)
}

if ($Mcp) {
    $repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..\..')).Path
    & (Join-Path $repo 'scripts\hot-mcp-server.ps1') -StopSession
}
