# 画面の確認で起動したアプリを止める（Windows）。止めたプロセスの番号を出す。
# 止めるのは、アプリ本体と Hot Reload の道具の JVM（どちらも起動行に dev.toros.yde.desktop.MainKt を含む）と、
# hotRun を走らせた Gradle の呼び出し側（gradle-wrapper.jar hotRun）だけ。
# Hot Reload の MCP サーバ（gradlew hotMcpServer と、その本体 ComposeHotReloadMcp）と、Gradle デーモンは止めない
# （MCP が切れる・ほかのビルドを壊すため）。
# 使い方: powershell -NoProfile -ExecutionPolicy Bypass -File .claude/skills/ui-check/scripts/stop.ps1
$targets = Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object {
    $c = $_.CommandLine
    $c -and $c -notmatch 'hotMcpServer|ComposeHotReloadMcp|GradleDaemon' -and (
        $c -match 'dev\.toros\.yde\.desktop\.MainKt' -or
        ($c -match 'gradle-wrapper\.jar|GradleWrapperMain' -and $c -match '\bhotRun\b')
    )
}
if (-not $targets) {
    Write-Output '止めるプロセスはありません'
    exit 0
}
foreach ($p in $targets) {
    Stop-Process -Id $p.ProcessId -Force -Confirm:$false
    Write-Output ("止めました: {0}" -f $p.ProcessId)
}
