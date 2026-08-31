# deploy-email-sender.ps1 — deploy email-sender Cloud Function
# Requires: yc CLI authenticated, .env in repo root
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# 1. Load .env into env: (ignore comments and empty lines)
Write-Host "Loading .env..." -ForegroundColor Cyan
Get-Content -Path ".env" | ForEach-Object {
    if ($_ -match "^\s*([^#][^=]+?)\s*=\s*(.*)\s*$") {
        $key = $Matches[1].Trim()
        $value = $Matches[2].Trim() -replace '^"(.*)"$', '$1' -replace "^'(.*)'$", '$1'
        Set-Item -Path "env:$key" -Value $value
        Write-Host "  env:$key set"
    }
}

# 2. Resolve service account and folder
Write-Host "Resolving SA and folder..." -ForegroundColor Cyan
$SA_ID = (yc iam service-account get --name ai-studio-sa --format json | ConvertFrom-Json).id
if (-not $SA_ID) { throw "Failed to get SA_ID for ai-studio-sa" }
$FOLDER_ID = (yc config get folder-id)
if (-not $FOLDER_ID) { throw "Failed to get folder-id" }
Write-Host "  SA_ID=$SA_ID FOLDER_ID=$FOLDER_ID"

# 3. Build common + email-sender
Write-Host "Building email-sender..." -ForegroundColor Cyan
mvn -pl common,email-sender -am package -DskipTests
if ($LASTEXITCODE -ne 0) { throw "Maven build failed for email-sender" }

# 4. Get function ID if exists (for later workflow substitution) — optional before create
#    CF_ID will be refreshed after version create
$CF_ID_BEFORE = $null
try {
    $CF_ID_BEFORE = (yc serverless function get --name email-sender --format json | ConvertFrom-Json).id
} catch {}

# 5. Deploy function version
Write-Host "Deploying email-sender function..." -ForegroundColor Cyan
yc serverless function version create `
    --function-name email-sender `
    --runtime java21 `
    --entrypoint ru.anseranser.mailsender.EmailSenderFunction `
    --memory 256m `
    --execution-timeout 30s `
    --source-path email-sender/target/email-sender-1.0.0.jar `
    --service-account-id $SA_ID `
    --environment SMTP_HOST=$env:SMTP_HOST,SMTP_PORT=$env:SMTP_PORT,SMTP_USER=$env:SMTP_USER,HELPDESK_MAILBOX=$env:HELPDESK_MAILBOX `
    --secret environmentVariable=SMTP_PASSWORD,sourceId=smtp-password,versionId=latest

if ($LASTEXITCODE -ne 0) { throw "yc function version create failed for email-sender" }

# 6. Fetch deployed function ID (used by workflow template)
Write-Host "Fetching email-sender function ID..." -ForegroundColor Cyan
$CF_ID = (yc serverless function get --name email-sender --format json | ConvertFrom-Json).id
if (-not $CF_ID) { throw "Failed to get CF_ID for email-sender" }
Write-Host "  EMAIL_SENDER_CF_ID=$CF_ID"

Write-Host "email-sender deploy finished. CF_ID=$CF_ID" -ForegroundColor Green
Write-Host "  Next: run deploy-workflow.ps1 to update workflow with this CF_ID." -ForegroundColor Yellow
