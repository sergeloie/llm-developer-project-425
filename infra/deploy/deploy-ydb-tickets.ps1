# deploy-ydb-tickets.ps1 — deploy ydb-tickets Cloud Function + MCP gateway
# Requires: yc CLI authenticated, .env in repo root with YDB_ENDPOINT, YDB_DATABASE, etc.
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# 1. Load environment variables from .env into current process (env:)
#    Lines starting with # are ignored; empty lines skipped.
Write-Host "Loading .env..." -ForegroundColor Cyan
Get-Content -Path ".env" | ForEach-Object {
    if ($_ -match "^\s*([^#][^=]+?)\s*=\s*(.*)\s*$") {
        $key = $Matches[1].Trim()
        $value = $Matches[2].Trim()
        # Remove surrounding quotes if present
        $value = $value -replace '^"(.*)"$', '$1' -replace "^'(.*)'$", '$1'
        Set-Item -Path "env:$key" -Value $value
        Write-Host "  env:$key set"
    }
}

# 2. Resolve service account and folder IDs via yc CLI (JSON parsing)
Write-Host "Resolving service account ID..." -ForegroundColor Cyan
$SA_ID = (yc iam service-account get --name ai-studio-sa --format json | ConvertFrom-Json).id
if (-not $SA_ID) { throw "Failed to get SA_ID for ai-studio-sa" }
Write-Host "  SA_ID=$SA_ID"

Write-Host "Resolving folder ID..." -ForegroundColor Cyan
$FOLDER_ID = (yc config get folder-id)
if (-not $FOLDER_ID) { throw "Failed to get folder-id via yc config" }
Write-Host "  FOLDER_ID=$FOLDER_ID"

# 3. Build common + ydb-tickets (shaded jar) — skip tests for deploy speed
Write-Host "Building ydb-tickets..." -ForegroundColor Cyan
mvn -pl common,ydb-tickets -am package -DskipTests
if ($LASTEXITCODE -ne 0) { throw "Maven build failed for ydb-tickets" }

# 4. Deploy Cloud Function version (java21, entrypoint, memory, timeout, secrets)
#    Secrets from Lockbox: ydb-database, yandex-api-key etc. are injected via --secret
Write-Host "Deploying ydb-tickets function..." -ForegroundColor Cyan
yc serverless function version create `
    --function-name ydb-tickets `
    --runtime java21 `
    --entrypoint ru.anseranser.ydb.YdbTicketsHandler `
    --memory 512m `
    --execution-timeout 30s `
    --source-path ydb-tickets/target/ydb-tickets-1.1.0.jar `
    --service-account-id $SA_ID `
    --environment YDB_ENDPOINT=$env:YDB_ENDPOINT,YDB_DATABASE=$env:YDB_DATABASE `
    --secret environmentVariable=YDB_TOKEN,sourceId=ydb-token,versionId=latest

if ($LASTEXITCODE -ne 0) { throw "yc function version create failed for ydb-tickets" }

# 5. Get deployed function ID (for MCP gateway template substitution)
Write-Host "Fetching ydb-tickets function ID..." -ForegroundColor Cyan
$CF_ID = (yc serverless function get --name ydb-tickets --format json | ConvertFrom-Json).id
if (-not $CF_ID) { throw "Failed to get CF_ID for ydb-tickets" }
Write-Host "  YDB_TICKETS_CF_ID=$CF_ID"

# 6. Render MCP tools template: replace placeholder with real function ID
#    Using PowerShell -replace as envsubst for {{YDB_TICKETS_CF_ID}}
Write-Host "Rendering mcp-tools.yaml from template..." -ForegroundColor Cyan
$templatePath = "infra/mcp/mcp-tools.yaml.template"
$outPath = "infra/mcp/mcp-tools.yaml"
$template = Get-Content -Path $templatePath -Raw
$rendered = $template -replace "{{YDB_TICKETS_CF_ID}}", $CF_ID
Set-Content -Path $outPath -Value $rendered -Encoding utf8
Write-Host "  Rendered $outPath"

# 7. Create or update MCP gateway with rendered tools file
#    If gateway exists -> update, otherwise create.
Write-Host "Deploying MCP gateway..." -ForegroundColor Cyan
$gatewayName = "helpdesk-mcp"
$existing = yc serverless mcp-gateway get --name $gatewayName --format json 2>$null | ConvertFrom-Json
if ($existing) {
    yc serverless mcp-gateway update --name $gatewayName --tools-file $outPath
} else {
    yc serverless mcp-gateway create --name $gatewayName --tools-file $outPath
}
if ($LASTEXITCODE -ne 0) { throw "MCP gateway deploy failed" }

Write-Host "ydb-tickets deploy finished." -ForegroundColor Green
