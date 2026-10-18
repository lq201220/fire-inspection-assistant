$ErrorActionPreference='Stop'
. "$PSScriptRoot/Environment.ps1"
Set-Location $ProjectRoot
Import-ProjectEnvironment
New-Item -ItemType Directory -Path '.runtime' -Force | Out-Null
& docker compose up -d --wait db
if ($LASTEXITCODE -ne 0) { throw '数据库启动失败。' }
foreach ($service in @('api','web')) {
    $pidFile=Join-Path $ProjectRoot ".runtime/$service.pid"
    if (Test-Path -LiteralPath $pidFile) {
        $existing=Get-Process -Id (Get-Content -LiteralPath $pidFile) -ErrorAction SilentlyContinue
        if ($existing) { Write-Output "$service 已在运行"; continue }
    }
    $port=if($service -eq 'api'){[int]$env:API_PORT}else{5173}
    if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) { throw "端口 $port 已占用，请先停止本项目旧服务或调整配置。" }
    $script=Join-Path $PSScriptRoot 'Run-Service.ps1'
    $shellPath=(Get-Process -Id $PID).Path
    $arguments=@('-NoProfile','-ExecutionPolicy','Bypass','-File',('"'+$script+'"'),'-Service',$service)
    $process=Start-Process -FilePath $shellPath -ArgumentList $arguments -WorkingDirectory $ProjectRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $ProjectRoot ".runtime/$service.out.log") -RedirectStandardError (Join-Path $ProjectRoot ".runtime/$service.err.log") -PassThru
    Set-Content -LiteralPath $pidFile -Value $process.Id
}
$healthy=$false
for($attempt=0;$attempt -lt 45;$attempt++) {
    try { $health=Invoke-RestMethod "http://localhost:$env:API_PORT/api/health" -TimeoutSec 2; if($health.code -eq 0){$healthy=$true;break} } catch {}
    Start-Sleep -Seconds 1
}
if (-not $healthy) { throw '后端启动未成功，请检查 .runtime/api.err.log 与 api.out.log。' }
Write-Output "已启动。前端：http://localhost:5173；接口：http://localhost:$env:API_PORT/api/health。"
