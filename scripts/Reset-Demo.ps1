param([switch]$ConfirmReset,[switch]$Empty)
$ErrorActionPreference='Stop'
. "$PSScriptRoot/Environment.ps1"
Set-Location $ProjectRoot
Import-ProjectEnvironment
if (-not $ConfirmReset) { throw '仅用于本地演示库；运行 -ConfirmReset 重新载入样本，或 -ConfirmReset -Empty 清空业务并保留账号。' }
if ($env:DEMO_ENABLED -ne 'true') { throw 'DEMO_ENABLED 必须为 true。' }
if (Get-NetTCPConnection -LocalPort ([int]$env:API_PORT) -State Listen -ErrorAction SilentlyContinue) { throw '请先使用 Stop.ps1 停止后端，避免重置期间仍有业务写入。' }
$sql=@'
SET FOREIGN_KEY_CHECKS=0;
TRUNCATE TABLE recheck_record;
TRUNCATE TABLE rectification_record;
TRUNCATE TABLE review_record;
TRUNCATE TABLE operate_log;
TRUNCATE TABLE file_attachment;
TRUNCATE TABLE risk_event;
TRUNCATE TABLE issue_record;
TRUNCATE TABLE checklist_item;
TRUNCATE TABLE saved_checklist_item;
TRUNCATE TABLE removed_checklist_template;
TRUNCATE TABLE inspect_task;
TRUNCATE TABLE supervised_unit;
TRUNCATE TABLE auth_session;
SET FOREIGN_KEY_CHECKS=1;
'@
if (-not $Empty) { $sql=$sql.Replace('SET FOREIGN_KEY_CHECKS=1;', 'TRUNCATE TABLE sys_user;' + [Environment]::NewLine + 'SET FOREIGN_KEY_CHECKS=1;') }
# Only the Compose project's fire_assistant database; no host root password or other database.
$sql | & docker compose exec -T db sh -c 'exec mysql -u fire_app -p"$MYSQL_PASSWORD" fire_assistant'
if ($LASTEXITCODE -ne 0) { throw '演示库重置失败。' }
if ($Empty) {
    Write-Output '测试业务数据与旧会话已清空，账号及数据库结构保留；重启不加载业务样本。既有附件文件保留，可运行清理脚本。'
} else {
    Write-Output '演示业务数据已清空。下一次 Start.ps1 启动时载入四个账号和样本；既有附件文件保留，可运行清理脚本。'
}
