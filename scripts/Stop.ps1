param([switch]$StopDatabase)
$ErrorActionPreference='Stop'
. "$PSScriptRoot/Environment.ps1"
Set-Location $ProjectRoot
function Stop-OwnedProcessTree([int]$servicePid) {
    $children=Get-CimInstance Win32_Process -Filter "ParentProcessId=$servicePid" -ErrorAction SilentlyContinue
    foreach($child in $children) { Stop-OwnedProcessTree $child.ProcessId }
    Stop-Process -Id $servicePid -Force -ErrorAction SilentlyContinue
}
foreach($service in @('api','web')) {
    $pidFile=Join-Path $ProjectRoot ".runtime/$service.pid"
    if (-not (Test-Path -LiteralPath $pidFile)) { continue }
    $servicePid=[int](Get-Content -LiteralPath $pidFile)
    $process=Get-CimInstance Win32_Process -Filter "ProcessId=$servicePid" -ErrorAction SilentlyContinue
    if($process -and $process.CommandLine.Contains((Join-Path $PSScriptRoot 'Run-Service.ps1'))) {
        Stop-OwnedProcessTree $servicePid
    } elseif($process) { throw 'PID 已属于其他进程，拒绝停止。' }
    Remove-Item -LiteralPath $pidFile -Force
}
if($StopDatabase) { & docker compose stop db }
Write-Output '本项目服务已停止，数据库数据与附件保留。'
