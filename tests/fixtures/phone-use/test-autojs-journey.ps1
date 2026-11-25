param (
    [Parameter(Mandatory=$true)][string]$Serial,
    [string]$Sdk = $env:ANDROID_HOME,
    [Parameter(Mandatory=$true)][string]$EvidencePath
)
$ErrorActionPreference='Stop'
$adb=Join-Path $Sdk 'platform-tools/adb.exe'
$pkg='com.androidharness.fixture.phoneuse'
$plugin='io.github.supermonster003.autojs6.plugin.mcp.server:mcp_server'
$serverPid=(& $adb -s $Serial shell pidof $plugin | Out-String).Trim()
if ($serverPid -notmatch '^\d+$') { throw 'No active paired plugin process.' }
if (Test-Path -LiteralPath $EvidencePath) { throw 'Choose a new evidence file for this run.' }
$release=(& $adb -s $Serial shell dumpsys package com.androidharness.app | Select-String -Pattern '^\s+versionName=' | Select-Object -First 1).Line.Trim()
if(!$release){throw 'Harness release not installed'}
"$release" | Set-Content -LiteralPath $EvidencePath -Encoding utf8
$rows = [Collections.Generic.List[string]]::new()
& $adb -s $Serial shell am instrument -w -e live one-explicit-device-trial -e target $pkg -e harness com.androidharness.app -e scenario autojs-roundtrip "$pkg/.CalculatorJourneyProbe" | ForEach-Object {
    $rows.Add($_)
    Add-Content -Encoding utf8 -LiteralPath $EvidencePath -Value $_
    Write-Output $_
}
$trace=$rows -join "`n"
$start=[regex]::Match($trace,'managed_wall_ms=(\d+)')
$end=[regex]::Match($trace,'trial_end_wall_ms=(\d+)')
if($start.Success -and $end.Success) {
    $first=$start.Groups[1].Value
    $last=$end.Groups[1].Value
    # Filter on-device: only timestamp, the known MCP tool name, and this client's identity.
    # No MCP arguments, identifiers, screenshot data, system logs, or authorization headers.
    $filter=@'
logcat -b main -d -v epoch --pid=PID -s McpServerRuntime:I | awk '$1+0 >= FROM/1000 && $1+0 <= UNTIL/1000 { for(i=1;i+3<=NF;i++) if($i=="tool_call" && $(i+1)~/^(ui_click|ui_set_text|ui_press_key|ui_dump|screen_state|screen_capture|device_ping)$/ && $(i+2)=="by" && $(i+3)=="AndroidHarness") print $1,$(i+1) }'
'@
    $filter=$filter.Replace('PID',$serverPid).Replace('FROM',$first).Replace('UNTIL',$last)
    $calls=@($filter | & $adb -s $Serial shell "tr -d '\r' | sh")
    $report=@($calls|Group-Object { ($_ -split '\s+')[1] }|ForEach-Object{"MCP $($_.Name): $($_.Count)"})
    if(!$report){$report=@('No attributable plugin calls recovered for the grant interval.')}
    $report | Add-Content -Encoding utf8 -LiteralPath $EvidencePath
    $report
}
if($trace -notmatch 'INSTRUMENTATION_CODE: -1' -or $trace -match 'INSTRUMENTATION_RESULT: failure=') { throw 'Real-model UI journey did not pass; see evidence.' }
if($trace -notmatch 'INSTRUMENTATION_RESULT: named_autojs_run_completed_steps=[1-9]' -or
   $trace -notmatch 'INSTRUMENTATION_RESULT: anonymous_autojs_run_completed_steps=[1-9]') {
    throw 'Inspect the existing synthetic turn manually: the two script receipts have not both been verified. Do not repeat the task for evidence.'
}
if(!($calls | Where-Object { $_ -match ' ui_set_text$' }) -or !($calls | Where-Object { $_ -match ' ui_click$' })) {
    throw 'Both scripts completed but this run lacked attributable MCP input telemetry.'
}
