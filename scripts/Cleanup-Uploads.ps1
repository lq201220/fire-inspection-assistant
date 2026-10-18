param([int]$OlderThanDays=1,[switch]$Apply)
$ErrorActionPreference='Stop'
. "$PSScriptRoot/Environment.ps1"
Set-Location $ProjectRoot
Import-ProjectEnvironment
if($OlderThanDays -lt 1) { throw '必须保留至少一天的新上传附件。' }
if(Get-NetTCPConnection -LocalPort ([int]$env:API_PORT) -State Listen -ErrorAction SilentlyContinue) { throw '请先停止后端再清理附件。' }
$sql="SELECT stored_name FROM file_attachment WHERE bound_id IS NOT NULL OR created_at >= DATE_SUB(NOW(),INTERVAL $OlderThanDays DAY);"
$used=$sql | & docker compose exec -T db sh -c 'exec mysql -N -B -u fire_app -p"$MYSQL_PASSWORD" fire_assistant'
if($LASTEXITCODE -ne 0) { throw '无法读取附件引用，拒绝清理。' }
$uploadRoot=[IO.Path]::GetFullPath($env:UPLOAD_DIR)
$cutoff=(Get-Date).AddDays(-$OlderThanDays)
$orphans=Get-ChildItem -LiteralPath $uploadRoot -File | Where-Object { $_.Name -match '^file-[a-f0-9]{32}\.(png|jpg|pdf)$' -and $_.LastWriteTime -lt $cutoff -and $_.Name -notin $used }
if($Apply) {
    $deleteSql="DELETE FROM file_attachment WHERE bound_id IS NULL AND created_at < DATE_SUB(NOW(),INTERVAL $OlderThanDays DAY);"
    $deleteSql | & docker compose exec -T db sh -c 'exec mysql -u fire_app -p"$MYSQL_PASSWORD" fire_assistant'
    if($LASTEXITCODE -ne 0) { throw '临时附件清理失败，文件保留。' }
}
foreach($file in $orphans) {
    if([IO.Path]::GetDirectoryName($file.FullName) -ne $uploadRoot) { throw '附件路径超出配置目录。' }
    if($Apply) { Remove-Item -LiteralPath $file.FullName } else { Write-Output $file.Name }
}
Write-Output ("孤立文件数："+@($orphans).Count+$(if($Apply){'；已清理'}else{'；仅预览，使用 -Apply 清理'}))
