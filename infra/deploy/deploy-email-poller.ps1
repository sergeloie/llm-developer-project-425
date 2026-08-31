# deploy-email-poller.ps1 — deploy email-poller (helpdesk mail handler) Cloud Function
# Trigger: cron or manual; handles IMAP fetch + AI agent + SMTP reply
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

# 2. Resolve SA and folder via yc CLI JSON
Write-Host "Resolving service account..." -ForegroundColor Cyan
$SA_ID = (yc iam service-account get --name ai-studio-sa --format json | ConvertFrom-Json).id
if (-not $SA_ID) { throw "Failed to get SA_ID for ai-studio-sa" }
$FOLDER_ID = (yc config get folder-id)
if (-not $FOLDER_ID) { throw "Failed to get folder-id" }
Write-Host "  SA_ID=$SA_ID"
Write-Host "  FOLDER_ID=$FOLDER_ID"

# 3. Build common + email-poller (shaded jar)
Write-Host "Building email-poller..." -ForegroundColor Cyan
mvn -pl common,email-poller -am package -DskipTests
if ($LASTEXITCODE -ne 0) { throw "Maven build failed for email-poller" }

# 4. Deploy function version — java21, entryPoint EmailHandler, 512m, 120s timeout for IMAP+LLM
Write-Host "Deploying email-poller function..." -ForegroundColor Cyan
yc serverless function version create `
    --function-name email-poller `
    --runtime java21 `
    --entrypoint ru.anseranser.mail.EmailHandler `
    --memory 512m `
    --execution-timeout 120s `
    --source-path email-poller/target/email-poller-1.2.0.jar `
    --service-account-id $SA_ID `
    --environment IMAP_HOST=$env:IMAP_HOST,IMAP_USER=$env:IMAP_USER,SMTP_HOST=$env:SMTP_HOST,SMTP_PORT=$env:SMTP_PORT,SMTP_USER=$env:SMTP_USER,HELPDESK_MAILBOX=$env:HELPDESK_MAILBOX,YDB_ENDPOINT=$env:YDB_ENDPOINT,YDB_DATABASE=$env:YDB_DATABASE,AGENT_ID=$env:AGENT_ID,ORGANIZATION_ID=$env:ORGANIZATION_ID,MCP_SERVER_URL=$env:MCP_SERVER_URL,VECTOR_STORE_ID=$env:VECTOR_STORE_ID `
    --secret environmentVariable=IMAP_PASSWORD,sourceId=imap-password,versionId=latest `
    --secret environmentVariable=SMTP_PASSWORD,sourceId=smtp-password,versionId=latest `
    --secret environmentVariable=YANDEX_API_KEY,sourceId=yandex-api-key,versionId=latest `
    --secret environmentVariable=YDB_TOKEN,sourceId=ydb-token,versionId=latest

if ($LASTEXITCODE -ne 0) { throw "yc function version create failed for email-poller" }

# 5. Fetch CF_ID for verification (not used in templates but useful for logs)
$CF_ID = (yc serverless function get --name email-poller --format json | ConvertFrom-Json).id
Write-Host "  EMAIL_POLLER_CF_ID=$CF_ID" -ForegroundColor Cyan

Write-Host "email-poller deploy finished." -ForegroundColor Green
