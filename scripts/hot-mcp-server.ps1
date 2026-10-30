# Hot Reload の MCP サーバ（gradlew hotMcpServer）を、起動したセッション（Claude Code など）と一緒に終わるように起動する（Windows）。
# .mcp.json から呼ぶ。標準入出力は MCP の通信そのものなので、サーバとして動くときは標準出力に何も書かない（記録は標準エラーへ）。
#
# なぜ挟むか（2026-10-31 に実測）: cmd /c gradlew.bat hotMcpServer をそのまま起動すると、Gradle の呼び出し側・使い捨てのデーモン・
# MCP の本体の 3 つの Java（合わせて 400〜550MB）ができる。使い手が入力を閉じても 3 つとも終わらず、直接の子（cmd）を止めても孫は残る。
#
# 仕組み:
# 1. Windows のジョブ（Job Object）を「最後の参照が閉じたら中のプロセスを全部止める」設定で作り、このプロセス自身を入れる。
#    この後に起動する子と孫（cmd・Gradle・MCP の本体・MCP の restart で起動したアプリ）は、自動で同じジョブに入る。
# 2. gradlew.bat hotMcpServer を子として起動する。標準入出力はそのまま引き継ぐので、MCP の通信は子に直接つながる。
# 3. 子が終わるか、親（このスクリプトを起動したセッション）が居なくなるまで待って終わる。このプロセスが終わるとジョブが閉じ、
#    子孫が全部止まる。セッションがこのプロセスを強制終了したときも同じ。
# 4. 起動の後で、前のセッションの取り残し（親の居ない MCP サーバの起動と、その本体）を片付ける。
#
# 使い方:
#   サーバとして（.mcp.json から）:
#     powershell -NoProfile -NonInteractive -ExecutionPolicy Bypass -File scripts/hot-mcp-server.ps1
#   呼んだセッションの MCP サーバを止める（画面の確認の片付け。ui-check の stop.ps1 -Mcp から呼ぶ）:
#     powershell -NoProfile -ExecutionPolicy Bypass -File scripts/hot-mcp-server.ps1 -StopSession
#   取り残しだけを片付ける:
#     powershell -NoProfile -ExecutionPolicy Bypass -File scripts/hot-mcp-server.ps1 -CleanOrphans
#
# 止めてよいのは MCP サーバの系列だけ: サーバとして動くこのスクリプト・cmd /c gradlew.bat ... hotMcpServer・
# gradle-wrapper.jar ... hotMcpServer・ComposeHotReloadMcp の本体と、その子孫。Gradle デーモンを名前では選ばない
# （使い捨てと共有のデーモンは起動行で区別できず、共有のデーモンはほかのビルドが使う）。
param(
    [switch]$StopSession,
    [switch]$CleanOrphans
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$repo = Split-Path -Parent $PSScriptRoot

function Get-ProcessTable {
    @(Get-CimInstance Win32_Process | Select-Object ProcessId, ParentProcessId, Name, CommandLine, CreationDate)
}

# MCP サーバの系列の起点か。サーバとして動くこのスクリプトは、スクリプトの場所で起動行が終わる（-StopSession などが付かない）
function Test-McpProcess($p) {
    $c = $p.CommandLine
    if (-not $c) { return $false }
    switch ($p.Name) {
        'powershell.exe' { return $c -match '-File\s+"?[^"]*hot-mcp-server\.ps1"?\s*$' }
        'pwsh.exe' { return $c -match '-File\s+"?[^"]*hot-mcp-server\.ps1"?\s*$' }
        'cmd.exe' { return $c -match '/c\s+"*[^"\s]*gradlew\.bat"?\s+[^&|]*\bhotMcpServer"*\s*$' }
        'java.exe' {
            return ($c -match 'gradle-wrapper\.jar"?\s+[^&|]*\bhotMcpServer"?\s*$') -or
                ($c -match 'org\.jetbrains\.compose\.reload\.mcp\.ComposeHotReloadMcp')
        }
    }
    return $false
}

# 親が生きているか（番号の使い回しに備え、親が子より後に作られていたら居ないとみなす）
function Test-ParentAlive($p, $byPid) {
    $parent = $byPid[[int]$p.ParentProcessId]
    return [bool]($parent -and $parent.CreationDate -le $p.CreationDate)
}

# 起点と子孫（親から順）
function Get-Tree($root, $all) {
    $children = @{}
    foreach ($p in $all) {
        $k = [int]$p.ParentProcessId
        if (-not $children.ContainsKey($k)) { $children[$k] = New-Object System.Collections.ArrayList }
        [void]$children[$k].Add($p)
    }
    $result = New-Object System.Collections.ArrayList
    [void]$result.Add($root)
    $queue = New-Object System.Collections.Queue
    $queue.Enqueue($root)
    while ($queue.Count -gt 0) {
        $parent = $queue.Dequeue()
        $id = [int]$parent.ProcessId
        if (-not $children.ContainsKey($id)) { continue }
        foreach ($c in $children[$id]) {
            if ([int]$c.ProcessId -eq $id -or $c.CreationDate -lt $parent.CreationDate) { continue }
            [void]$result.Add($c)
            $queue.Enqueue($c)
        }
    }
    $result
}

# このプロセスと先祖の番号（止めてはいけない）
function Get-Protected($byPid) {
    $set = New-Object 'System.Collections.Generic.HashSet[int]'
    $id = $PID
    while ($byPid.ContainsKey($id) -and $set.Add($id)) {
        $id = [int]$byPid[$id].ParentProcessId
    }
    , $set
}

# 起点と子孫を止め、止めたものを返す
function Stop-Tree($root, $all, $protected) {
    $stopped = New-Object System.Collections.ArrayList
    foreach ($p in (Get-Tree $root $all)) {
        $id = [int]$p.ProcessId
        if ($protected.Contains($id)) { continue }
        try {
            Stop-Process -Id $id -Force -ErrorAction Stop
            [void]$stopped.Add($p)
        } catch { }   # ジョブが閉じて先に止まったもの・権限の無いもの
    }
    , $stopped
}

# 前のセッションの取り残し（親の居ない MCP サーバの系列）を止め、止めたものを返す
function Remove-Orphans {
    $all = Get-ProcessTable
    $byPid = @{}
    foreach ($p in $all) { $byPid[[int]$p.ProcessId] = $p }
    $protected = Get-Protected $byPid
    $stopped = New-Object System.Collections.ArrayList
    foreach ($p in $all) {
        if ($protected.Contains([int]$p.ProcessId)) { continue }
        if ((Test-McpProcess $p) -and -not (Test-ParentAlive $p $byPid)) {
            foreach ($s in (Stop-Tree $p $all $protected)) { [void]$stopped.Add($s) }
        }
    }
    , $stopped
}

function Format-Stopped($list) {
    $list | ForEach-Object { "  {0} {1}" -f $_.ProcessId, $_.Name }
}

if ($StopSession -or $CleanOrphans) {
    if ($StopSession) {
        # 呼んだセッション＝このプロセスの先祖。MCP サーバの系列の起点のうち、親が先祖のものを止める
        $all = Get-ProcessTable
        $byPid = @{}
        foreach ($p in $all) { $byPid[[int]$p.ProcessId] = $p }
        $protected = Get-Protected $byPid
        $roots = @($all | Where-Object {
                (Test-McpProcess $_) -and -not $protected.Contains([int]$_.ProcessId) -and $protected.Contains([int]$_.ParentProcessId)
            })
        if ($roots.Count -eq 0) {
            Write-Output 'このセッションの MCP サーバはありません'
        }
        foreach ($r in $roots) {
            $stopped = Stop-Tree $r $all $protected
            Write-Output ("MCP サーバを止めました（起点 {0}・{1} 個）:" -f $r.ProcessId, $stopped.Count)
            Format-Stopped $stopped
        }
    }
    $orphans = Remove-Orphans
    if ($orphans.Count -gt 0) {
        Write-Output ("取り残しを止めました（{0} 個）:" -f $orphans.Count)
        Format-Stopped $orphans
    } elseif ($CleanOrphans) {
        Write-Output '取り残しはありません'
    }
    return
}

# ---- サーバとして動く ----

$log = New-Object System.IO.StreamWriter([Console]::OpenStandardError(), (New-Object System.Text.UTF8Encoding $false))
$log.AutoFlush = $true

$jobSource = @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;

public static class YdeMcpJob {
    [StructLayout(LayoutKind.Sequential)]
    struct BasicLimit {
        public long PerProcessUserTimeLimit;
        public long PerJobUserTimeLimit;
        public uint LimitFlags;
        public UIntPtr MinimumWorkingSetSize;
        public UIntPtr MaximumWorkingSetSize;
        public uint ActiveProcessLimit;
        public UIntPtr Affinity;
        public uint PriorityClass;
        public uint SchedulingClass;
    }

    [StructLayout(LayoutKind.Sequential)]
    struct IoCounters {
        public ulong ReadOperationCount, WriteOperationCount, OtherOperationCount;
        public ulong ReadTransferCount, WriteTransferCount, OtherTransferCount;
    }

    [StructLayout(LayoutKind.Sequential)]
    struct ExtendedLimit {
        public BasicLimit Basic;
        public IoCounters Io;
        public UIntPtr ProcessMemoryLimit;
        public UIntPtr JobMemoryLimit;
        public UIntPtr PeakProcessMemoryUsed;
        public UIntPtr PeakJobMemoryUsed;
    }

    [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    static extern IntPtr CreateJobObject(IntPtr attributes, string name);

    [DllImport("kernel32.dll", SetLastError = true)]
    static extern bool SetInformationJobObject(IntPtr job, int infoClass, ref ExtendedLimit info, int length);

    [DllImport("kernel32.dll", SetLastError = true)]
    static extern bool AssignProcessToJobObject(IntPtr job, IntPtr process);

    [DllImport("kernel32.dll")]
    static extern IntPtr GetCurrentProcess();

    const int ExtendedLimitInformation = 9;
    const uint KillOnJobClose = 0x2000;

    public static IntPtr EnterKillOnCloseJob() {
        IntPtr job = CreateJobObject(IntPtr.Zero, null);
        if (job == IntPtr.Zero) throw new Win32Exception();
        ExtendedLimit info = new ExtendedLimit();
        info.Basic.LimitFlags = KillOnJobClose;
        if (!SetInformationJobObject(job, ExtendedLimitInformation, ref info, Marshal.SizeOf(typeof(ExtendedLimit)))) throw new Win32Exception();
        if (!AssignProcessToJobObject(job, GetCurrentProcess())) throw new Win32Exception();
        return job;
    }
}
'@

# ジョブの参照はこのプロセスだけが持つ（子に引き継がない）。このプロセスが終わると参照が閉じ、ジョブの中が全部止まる
$job = [IntPtr]::Zero
try {
    Add-Type -TypeDefinition $jobSource
    $job = [YdeMcpJob]::EnterKillOnCloseJob()
} catch {
    $log.WriteLine("hot-mcp-server: ジョブを作れませんでした。終わるときに子孫を自分で止めます（$($_.Exception.Message)）")
}

# 親（セッション）。先に参照を開いておき、番号が使い回されても同じプロセスを見る
$parent = $null
try {
    $self = Get-CimInstance Win32_Process -Filter "ProcessId=$PID"
    $parent = [System.Diagnostics.Process]::GetProcessById([int]$self.ParentProcessId)
    [void]$parent.Handle
} catch {
    $parent = $null
    $log.WriteLine("hot-mcp-server: 親のプロセスを開けませんでした。子が終わるまで待ちます（$($_.Exception.Message)）")
}

# 子: 標準入出力はリダイレクトせず、そのまま引き継ぐ
$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = $env:ComSpec
# gradlew.bat は絶対パスで渡す（NoDefaultCurrentDirectoryInExePath が立つ環境では、今のフォルダのバッチを名前だけで起動できない）
$psi.Arguments = '/d /s /c ""{0}" --no-daemon --quiet --console=plain hotMcpServer"' -f (Join-Path $repo 'gradlew.bat')
$psi.WorkingDirectory = $repo
$psi.UseShellExecute = $false
$child = [System.Diagnostics.Process]::Start($psi)

try {
    $orphans = Remove-Orphans
    if ($orphans.Count -gt 0) {
        $log.WriteLine(("hot-mcp-server: 前のセッションの取り残しを止めました: {0}" -f (($orphans | ForEach-Object { "$($_.ProcessId) $($_.Name)" }) -join ', ')))
    }
} catch {
    $log.WriteLine("hot-mcp-server: 取り残しの片付けに失敗しました（$($_.Exception.Message)）")
}

$reason = 'MCP サーバが終わった'
while (-not $child.WaitForExit(2000)) {
    if ($parent -and $parent.HasExited) {
        $reason = 'セッションが終わった'
        break
    }
}

if ($job -eq [IntPtr]::Zero -and -not $child.HasExited) {
    # ジョブが無いときだけ、子孫を自分で止める
    $all = Get-ProcessTable
    $byPid = @{}
    foreach ($p in $all) { $byPid[[int]$p.ProcessId] = $p }
    $root = $byPid[[int]$child.Id]
    if ($root) { [void](Stop-Tree $root $all (Get-Protected $byPid)) }
}

$log.WriteLine("hot-mcp-server: 終わります（$reason）")
if ($child.HasExited) { exit $child.ExitCode }
exit 0
