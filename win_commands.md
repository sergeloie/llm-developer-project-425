**Step 2**
```powershell
$endpointJson = yc ydb database get help-desk-db --format json | ConvertFrom-Json
$ENDPOINT = $endpointJson.endpoint
$YDB_ENDPOINT = ($ENDPOINT -replace '(grpcs://[^/?]+).*', '$1')
$YDB_DATABASE = ($ENDPOINT -replace '.*database=([^&]+).*', '$1')
Write-Output "YDB_ENDPOINT=$YDB_ENDPOINT"
Write-Output "YDB_DATABASE=$YDB_DATABASE"
```


**Step 3**
```powershell
$saJson = yc iam service-account get --name ai-studio-sa --format json | ConvertFrom-Json
$SA_ID = $saJson.id

$FOLDER_ID = yc config get folder-id

$ROLES = @(
    "functions.functionInvoker"
    "serverless.mcpGateways.invoker"
    "lockbox.payloadViewer"
    "ai.languageModels.user"
    "ydb.editor"
)

foreach ($ROLE in $ROLES) {
    yc resource-manager folder add-access-binding `
        --id $FOLDER_ID `
        --service-account-id $SA_ID `
        --role $ROLE
}
```

