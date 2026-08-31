# deploy-workflow.ps1 — render and deploy daily-escalation YaWL workflow
# Substitutes {{YDB_DATABASE}} and {{EMAIL_SENDER_CF_ID}} into template, then creates/updates workflow
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# 1. Load .env into env:
Write-Host "Loading .env..." -ForegroundColor Cyan
Get-Content -Path ".env" | ForEach-Object {
    if ($_ -match "^\s*([^#][^=]+?)\s*=\s*(.*)\s*$") {
        $key = $Matches[1].Trim()
        $value = $Matches[2].Trim() -replace '^"(.*)"$', '$1' -replace "^'(.*)'$", '$1'
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
$template = Get-Content -Path $templatePath -Raw
# Replace YDB_DATABASE and EMAIL_SENDER_CF_ID placeholders
$rendered = $template -replace "{{YDB_DATABASE}}", $YDB_DATABASE
$rendered = $rendered -replace "{{EMAIL_SENDER_CF_ID}}", $EMAIL_SENDER_CF_ID
Set-Content -Path $renderedPath -Value $rendered -Encoding utf8
Write-Host "  Rendered $renderedPath"

# 6. Ensure workflow SA has required roles (ydb.editor, ai.assistants.editor, etc.)
#    These bindings are idempotent; yc will skip if already granted.
Write-Host "Ensuring workflow SA roles..." -ForegroundColor Cyan
$roles = @("ydb.editor", "ai.assistants.editor", "ai.languageModels.user")
foreach ($role in $roles) {
    yc resource-manager folder add-access-binding --id $FOLDER_ID --service-account-id $SA_ID --role $role 2>$null
}

# 7. Create or update workflow (YaWL 0.2)
#    Workflow name: daily-escalation
Write-Host "Deploying workflow daily-escalation..." -ForegroundColor Cyan
$workflowName = "daily-escalation"
$existingWorkflow = yc serverless workflow get --name $workflowName --format json 2>$null | ConvertFrom-Json
if ($existingWorkflow) {
    yc serverless workflow update --name $workflowName --yaml-spec $renderedPath
    Write-Host "  Workflow updated."
} else {
    yc serverless workflow create --name $workflowName --yaml-spec $renderedPath --service-account-id $SA_ID
    Write-Host "  Workflow created."
}
if ($LASTEXITCODE -ne 0) { throw "Workflow deploy failed" }

# 8. Grant workflow executor/viewer to SA (if needed for invocation)
yc serverless workflow add-access-binding --name $workflowName --service-account-id $SA_ID --role serverless.workflows.executor 2>$null
yc serverless workflow add-access-binding --name $workflowName --service-account-id $SA_ID --role serverless.workflows.viewer 2>$null

Write-Host "Workflow daily-escalation deploy finished." -ForegroundColor Green
Write-Host "  Spec: $renderedPath yawl: '0.2' start: fetchOverdueTickets" -ForegroundColor Cyan
