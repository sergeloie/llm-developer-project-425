# deploy-workflow.ps1 — render and deploy daily-escalation YaWL workflow
# Substitutes {{YDB_DATABASE}} and {{EMAIL_SENDER_CF_ID}} into template, then creates/updates workflow
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# 1. Load .env into env:
# P1 fix: robust parsing via IndexOf('=') — handles passwords/base64 with '='
Write-Host "Loading .env..." -ForegroundColor Cyan
Get-Content -Path ".env" | ForEach-Object {
    $line = $_.Trim()
    if ($line -eq "" -or $line.StartsWith("#")) { return }
    $idx = $line.IndexOf('=')
    if ($idx -le 0) { return }
    $key = $line.Substring(0, $idx).Trim()
    $value = $line.Substring($idx + 1).Trim()
    $value = $value -replace '^"(.*)"$', '$1' -replace "^'(.*)'$", '$1'
    if ($key -ne "") {
        Set-Item -Path "env:$key" -Value $value
        Write-Host "  env:$key set"
    }
}

# 2. Resolve SA and folder
Write-Host "Resolving SA and folder..." -ForegroundColor Cyan
$SA_ID = (yc iam service-account get --name ai-studio-sa --format json | ConvertFrom-Json).id
if (-not $SA_ID) { throw "Failed to get SA_ID for ai-studio-sa" }
$FOLDER_ID = (yc config get folder-id)
if (-not $FOLDER_ID) { throw "Failed to get folder-id" }
Write-Host "  SA_ID=$SA_ID"
Write-Host "  FOLDER_ID=$FOLDER_ID"

# 3. Ensure YDB_DATABASE is available (from .env or yc config)
#    YDB_DATABASE like /ru-central1/b1g.../etn...
$YDB_DATABASE = $env:YDB_DATABASE
if (-not $YDB_DATABASE) {
    Write-Host "YDB_DATABASE not in .env, trying to derive from ydb database list..." -ForegroundColor Yellow
    # Optional: derive from first YDB database endpoint if needed
    $YDB_DATABASE = "{{YDB_DATABASE}}"
}
Write-Host "  YDB_DATABASE=$YDB_DATABASE"

# 4. Fetch email-sender function ID (needed for functionCall step)
Write-Host "Fetching email-sender function ID..." -ForegroundColor Cyan
$EMAIL_SENDER_CF_ID = (yc serverless function get --name email-sender --format json | ConvertFrom-Json).id
if (-not $EMAIL_SENDER_CF_ID) { throw "Failed to get EMAIL_SENDER_CF_ID for email-sender (deploy email-sender first)" }
Write-Host "  EMAIL_SENDER_CF_ID=$EMAIL_SENDER_CF_ID"

# 5. Render workflow template — replace placeholders via PowerShell -replace (envsubst)
Write-Host "Rendering daily-escalation workflow..." -ForegroundColor Cyan
$templatePath = "infra/workflow/daily-escalation.yaml.template"
$renderedPath = "infra/workflow/daily-escalation.yaml"
# Fix encoding: Get-Content -Raw без -Encoding читает кириллицу как ANSI -> кракозябры. Читаем строго UTF8.
$fullTemplatePath = Join-Path (Get-Location) $templatePath
$template = [System.IO.File]::ReadAllText($fullTemplatePath, [System.Text.Encoding]::UTF8)
# Replace YDB_DATABASE and EMAIL_SENDER_CF_ID placeholders
$rendered = $template -replace "{{YDB_DATABASE}}", $YDB_DATABASE
$rendered = $rendered -replace "{{EMAIL_SENDER_CF_ID}}", $EMAIL_SENDER_CF_ID
# Fix BOM: Set-Content -Encoding utf8 в PS5 пишет с BOM (EF BB BF) -> Invalid specification. Пишем без BOM.
$fullPath = Join-Path (Get-Location) $renderedPath
[System.IO.File]::WriteAllText($fullPath, $rendered, [System.Text.UTF8Encoding]::new($false))
Write-Host "  Rendered $renderedPath (utf8NoBOM, utf8 read)"

# 6. Ensure workflow SA has required roles (ydb.editor, ai.assistants.editor, etc.)
#    Idempotent; if authenticated as SA without admin rights, this will PermissionDenied - warn and continue.
Write-Host "Ensuring workflow SA roles..." -ForegroundColor Cyan
$roles = @("ydb.editor", "ai.assistants.editor", "ai.languageModels.user")
foreach ($role in $roles) {
    try {
        $oldEA = $ErrorActionPreference; $ErrorActionPreference = "Continue"
        yc resource-manager folder add-access-binding --id $FOLDER_ID --service-account-id $SA_ID --role $role 2>&1 | Out-Null
        $code = $LASTEXITCODE
        $ErrorActionPreference = $oldEA
        if ($code -ne 0) { Write-Host "  WARN: cannot add $role (already granted or no admin) - continue" -ForegroundColor Yellow }
        else { Write-Host "  Ensured $role" -ForegroundColor Green }
    } catch {
        Write-Host "  WARN: add $role failed: $($_.Exception.Message) - continue" -ForegroundColor Yellow
        $ErrorActionPreference = "Continue"
    }
}

# 7. Create or update workflow (YaWL 0.2)
#    Workflow name: daily-escalation
Write-Host "Deploying workflow daily-escalation..." -ForegroundColor Cyan
$workflowName = "daily-escalation"
$existingWorkflow = $null
try {
    $oldEA2 = $ErrorActionPreference; $ErrorActionPreference = "Continue"
    $json = yc serverless workflow get --name $workflowName --format json 2>&1
    $ErrorActionPreference = $oldEA2
    if ($LASTEXITCODE -eq 0 -and $json) { $existingWorkflow = $json | ConvertFrom-Json }
} catch { $existingWorkflow = $null; $ErrorActionPreference = "Continue" }
if ($null -ne $existingWorkflow) {
    try {
        $hasId = $false
        if ($existingWorkflow.workflow -and $existingWorkflow.workflow.id) { $hasId = $true }
        elseif ($existingWorkflow.id) { $hasId = $true }
    } catch { $hasId = $false }
    if ($hasId) { Write-Host "  Workflow exists, updating..." -ForegroundColor Yellow; yc serverless workflow update --name $workflowName --yaml-spec $renderedPath; Write-Host "  Workflow updated." }
    else { Write-Host "  Workflow not found, creating..." -ForegroundColor Yellow; yc serverless workflow create --name $workflowName --yaml-spec $renderedPath --service-account-id $SA_ID; Write-Host "  Workflow created." }
} else {
    Write-Host "  Workflow not found, creating..." -ForegroundColor Yellow
    yc serverless workflow create --name $workflowName --yaml-spec $renderedPath --service-account-id $SA_ID
    Write-Host "  Workflow created."
}
if ($LASTEXITCODE -ne 0) { throw "Workflow deploy failed" }

# 8. Grant workflow executor/viewer to SA (non-fatal if no admin)
try { $oldEA3=$ErrorActionPreference; $ErrorActionPreference="Continue"; yc serverless workflow add-access-binding --name $workflowName --service-account-id $SA_ID --role serverless.workflows.executor 2>&1 | Out-Null; $ErrorActionPreference=$oldEA3 } catch { $ErrorActionPreference="Continue" }
try { $oldEA4=$ErrorActionPreference; $ErrorActionPreference="Continue"; yc serverless workflow add-access-binding --name $workflowName --service-account-id $SA_ID --role serverless.workflows.viewer 2>&1 | Out-Null; $ErrorActionPreference=$oldEA4 } catch { $ErrorActionPreference="Continue" }

# 9. Timer trigger: start daily-escalation Mon-Fri 09:00 MSK (06:00 UTC)
#    Idempotent: create if missing, update if exists. Fatal on CLI failure.
Write-Host "Ensuring timer trigger daily-escalation-timer..." -ForegroundColor Cyan
$triggerName = "daily-escalation-timer"
$cronUtc = "0 6 ? * MON-FRI *"   # 09:00 MSK = 06:00 UTC (timer cron is UTC+0)
# Resolve workflow ID by name (needed for trigger create/update)
$wfJson = yc serverless workflow get --name $workflowName --format json 2>&1
if ($LASTEXITCODE -ne 0) { throw "Failed to get workflow $workflowName (trigger step aborted)" }
$workflowId = ($wfJson | ConvertFrom-Json).workflow.id
if (-not $workflowId) { throw "Failed to resolve workflow id for $workflowName" }
Write-Host "  workflowId=$workflowId"

$existingTrigger = $null
try {
    $oldEA5 = $ErrorActionPreference; $ErrorActionPreference = "Continue"
    $trigJson = yc serverless trigger get --name $triggerName --format json 2>&1
    $trigCode = $LASTEXITCODE
    $ErrorActionPreference = $oldEA5
    if ($trigCode -eq 0 -and $trigJson) { $existingTrigger = $trigJson | ConvertFrom-Json }
} catch { $existingTrigger = $null; $ErrorActionPreference = "Continue" }

if ($null -ne $existingTrigger) {
    # Note: `update timer <name>` ignores the positional; pass trigger id explicitly
    $triggerId = $existingTrigger.id
    if (-not $triggerId) { throw "Trigger exists but id is missing" }
    Write-Host "  Trigger exists ($triggerId), updating..." -ForegroundColor Yellow
    yc serverless trigger update timer --id $triggerId --new-cron-expression $cronUtc --new-start-workflow-id $workflowId --new-start-workflow-service-account-id $SA_ID
    if ($LASTEXITCODE -ne 0) { throw "Trigger update failed" }
    Write-Host "  Trigger updated ($triggerName)."
} else {
    Write-Host "  Trigger not found, creating..." -ForegroundColor Yellow
    yc serverless trigger create timer $triggerName --cron-expression $cronUtc --start-workflow-id $workflowId --start-workflow-service-account-id $SA_ID
    if ($LASTEXITCODE -ne 0) { throw "Trigger create failed" }
    Write-Host "  Trigger created ($triggerName)."
}

Write-Host "Workflow daily-escalation deploy finished." -ForegroundColor Green
Write-Host "  Spec: $renderedPath yawl: '0.2' start: fetchOverdueTickets" -ForegroundColor Cyan
