# deploy-ydb-tickets.ps1 — deploy ydb-tickets Cloud Function + MCP gateway via ZIP with sources
# Requires: yc CLI authenticated, .env in repo root with YDB_ENDPOINT, YDB_DATABASE, etc.
# Принцип: standalone zip (pom.xml + java) с '/' в путях. См. docs/yc-java-function-deploy.md
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# 1. Load environment variables from .env into current process (env:)
Write-Host "Loading .env..." -ForegroundColor Cyan
Get-Content -Path ".env" | ForEach-Object {
    if ($_ -match "^\s*([^#][^=]+?)\s*=\s*(.*)\s*$") {
        $key = $Matches[1].Trim()
        $value = $Matches[2].Trim()
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

# 3. Build ZIP with sources for Yandex Cloud Builder
Write-Host "Building ydb-tickets.zip (standalone sources)..." -ForegroundColor Cyan
$ProjectRoot = Resolve-Path "$PSScriptRoot\..\.."
$STAGE = Join-Path $env:TEMP "opencode\ydb-tickets-zip"
$ZIP_PATH = Join-Path $ProjectRoot "ydb-tickets.zip"

if (Test-Path $STAGE) { Remove-Item -Recurse -Force $STAGE }
New-Item -ItemType Directory -Path "$STAGE\src\main\java\ru\anseranser\ydb" -Force | Out-Null
New-Item -ItemType Directory -Path "$STAGE\src\main\java\ru\anseranser\json" -Force | Out-Null
New-Item -ItemType Directory -Path "$STAGE\src\main\java\ru\anseranser\pii" -Force | Out-Null
New-Item -ItemType Directory -Path "$STAGE\src\main\java\ru\anseranser\security" -Force | Out-Null

# 3.1 Copy java sources: весь ydb-tickets + нужные из common
Get-ChildItem -Path "$ProjectRoot\ydb-tickets\src\main\java\ru\anseranser\ydb\*.java" | ForEach-Object {
    Copy-Item -Path $_.FullName -Destination "$STAGE\src\main\java\ru\anseranser\ydb\"
}
Copy-Item -Path "$ProjectRoot\common\src\main\java\ru\anseranser\json\JsonEventParser.java" -Destination "$STAGE\src\main\java\ru\anseranser\json\"
Copy-Item -Path "$ProjectRoot\common\src\main\java\ru\anseranser\pii\PiiMasker.java" -Destination "$STAGE\src\main\java\ru\anseranser\pii\"
Copy-Item -Path "$ProjectRoot\common\src\main\java\ru\anseranser\security\InjectionClassifier.java" -Destination "$STAGE\src\main\java\ru\anseranser\security\"
Copy-Item -Path "$ProjectRoot\common\src\main\java\ru\anseranser\ydb\YdbTransportFactory.java" -Destination "$STAGE\src\main\java\ru\anseranser\ydb\"

$files = Get-ChildItem "$STAGE\src\main\java" -Recurse -Filter "*.java"
Write-Host "  Copied $($files.Count) java files:"
$files | ForEach-Object { Write-Host "    $($_.FullName.Replace($STAGE+'\',''))" }

# 3.2 Standalone pom.xml (без parent, без common)
$pom = @'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
  xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>ru.anseranser</groupId>
  <artifactId>ydb-tickets</artifactId>
  <version>1.1.0</version>
  <packaging>jar</packaging>
  <name>ydb-tickets</name>
  <description>YDB tickets handler - standalone for Yandex Cloud Builder</description>
  <properties>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <maven.compiler.release>21</maven.compiler.release>
    <yc-sdk.version>2.14.0</yc-sdk.version>
    <ydb.version>2.4.9</ydb.version>
    <jackson.version>2.17.2</jackson.version>
    <yc-auth.version>2.3.1</yc-auth.version>
    <junit.version>5.11.0</junit.version>
    <mockito.version>5.14.2</mockito.version>
  </properties>
  <dependencies>
    <dependency><groupId>com.yandex.cloud</groupId><artifactId>java-sdk-serverless</artifactId><version>${yc-sdk.version}</version></dependency>
    <dependency><groupId>tech.ydb</groupId><artifactId>ydb-sdk-table</artifactId><version>${ydb.version}</version></dependency>
    <dependency><groupId>tech.ydb.auth</groupId><artifactId>yc-auth-provider</artifactId><version>${yc-auth.version}</version></dependency>
    <dependency><groupId>com.fasterxml.jackson.core</groupId><artifactId>jackson-databind</artifactId><version>${jackson.version}</version></dependency>
    <dependency><groupId>com.fasterxml.jackson.core</groupId><artifactId>jackson-core</artifactId><version>${jackson.version}</version></dependency>
    <dependency><groupId>com.fasterxml.jackson.core</groupId><artifactId>jackson-annotations</artifactId><version>${jackson.version}</version></dependency>
    <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><version>${junit.version}</version><scope>test</scope></dependency>
    <dependency><groupId>org.mockito</groupId><artifactId>mockito-core</artifactId><version>${mockito.version}</version><scope>test</scope></dependency>
    <dependency><groupId>org.mockito</groupId><artifactId>mockito-junit-jupiter</artifactId><version>${mockito.version}</version><scope>test</scope></dependency>
  </dependencies>
  <dependencyManagement>
    <dependencies>
      <dependency><groupId>tech.ydb</groupId><artifactId>ydb-sdk-bom</artifactId><version>${ydb.version}</version><type>pom</type><scope>import</scope></dependency>
    </dependencies>
  </dependencyManagement>
  <build>
    <pluginManagement>
      <plugins>
        <plugin><artifactId>maven-clean-plugin</artifactId><version>3.4.0</version></plugin>
        <plugin><artifactId>maven-resources-plugin</artifactId><version>3.3.1</version></plugin>
        <plugin><artifactId>maven-compiler-plugin</artifactId><version>3.13.0</version></plugin>
        <plugin><artifactId>maven-surefire-plugin</artifactId><version>3.3.0</version><configuration><argLine>-Dnet.bytebuddy.experimental=true -XX:+EnableDynamicAgentLoading</argLine></configuration></plugin>
        <plugin><artifactId>maven-jar-plugin</artifactId><version>3.4.2</version></plugin>
        <plugin><artifactId>maven-install-plugin</artifactId><version>3.1.2</version></plugin>
        <plugin><artifactId>maven-deploy-plugin</artifactId><version>3.1.2</version></plugin>
        <plugin><artifactId>maven-shade-plugin</artifactId><version>3.6.0</version></plugin>
      </plugins>
    </pluginManagement>
    <plugins>
      <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId></plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId><artifactId>maven-shade-plugin</artifactId>
        <executions>
          <execution>
            <phase>package</phase><goals><goal>shade</goal></goals>
            <configuration>
              <transformers>
                <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer"><mainClass>ru.anseranser.ydb.YdbTicketsHandler</mainClass></transformer>
                <transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
              </transformers>
              <filters><filter><artifact>*:*</artifact><excludes><exclude>META-INF/*.SF</exclude><exclude>META-INF/*.DSA</exclude><exclude>META-INF/*.RSA</exclude></excludes></filter></filters>
            </configuration>
          </execution>
        </executions>
      </plugin>
      <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId></plugin>
    </plugins>
  </build>
</project>
'@
Set-Content -Path "$STAGE\pom.xml" -Value $pom -Encoding UTF8

# 3.3 Create ZIP with forward slashes
if (Test-Path $ZIP_PATH) { Remove-Item -Force $ZIP_PATH }
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$stream = [System.IO.File]::Create($ZIP_PATH)
$archive = New-Object System.IO.Compression.ZipArchive($stream, [System.IO.Compression.ZipArchiveMode]::Create)
function Add-FileToZip($archive, $sourcePath, $entryName) {
  $entry = $archive.CreateEntry($entryName, [System.IO.Compression.CompressionLevel]::Optimal)
  $es = $entry.Open()
  $bytes = [System.IO.File]::ReadAllBytes($sourcePath)
  $es.Write($bytes, 0, $bytes.Length)
  $es.Close()
}
Add-FileToZip $archive "$STAGE\pom.xml" "pom.xml"
$stageRoot = (Get-Item $STAGE).FullName
Get-ChildItem "$STAGE\src" -Recurse -File | ForEach-Object {
    $rel = $_.FullName.Substring($stageRoot.Length + 1) -replace "\\", "/"
    Add-FileToZip $archive $_.FullName $rel
}
$archive.Dispose()
$stream.Close()
$stream.Dispose()
Write-Host "  ZIP created: $ZIP_PATH ($((Get-Item $ZIP_PATH).Length) bytes)" -ForegroundColor Green
$z = [System.IO.Compression.ZipFile]::OpenRead($ZIP_PATH)
$z.Entries | ForEach-Object { Write-Host "    $($_.FullName)" }
$z.Dispose()

# 4. Deploy Cloud Function version — FQN entrypoint, zip via --source-path
Write-Host "Deploying ydb-tickets function from ZIP..." -ForegroundColor Cyan
yc serverless function version create `
    --function-name ydb-tickets `
    --runtime java21 `
    --entrypoint ru.anseranser.ydb.YdbTicketsHandler `
    --memory 512MB `
    --execution-timeout 30s `
    --source-path $ZIP_PATH `
    --service-account-id $SA_ID `
    --environment YDB_ENDPOINT=$env:YDB_ENDPOINT,YDB_DATABASE=$env:YDB_DATABASE `
    --secret environment-variable=YDB_TOKEN,name=ydb-token,version-id=latest

if ($LASTEXITCODE -ne 0) { throw "yc function version create failed for ydb-tickets" }

# 5. Get deployed function ID (for MCP gateway template substitution)
Write-Host "Fetching ydb-tickets function ID..." -ForegroundColor Cyan
$CF_ID = (yc serverless function get --name ydb-tickets --format json | ConvertFrom-Json).id
if (-not $CF_ID) { throw "Failed to get CF_ID for ydb-tickets" }
Write-Host "  YDB_TICKETS_CF_ID=$CF_ID"

# 6. Render MCP tools template: replace placeholder with real function ID
Write-Host "Rendering mcp-tools.yaml from template..." -ForegroundColor Cyan
$templatePath = "infra/mcp/mcp-tools.yaml.template"
$outPath = "infra/mcp/mcp-tools.yaml"
$template = Get-Content -Path $templatePath -Raw
$rendered = $template -replace "{{YDB_TICKETS_CF_ID}}", $CF_ID
Set-Content -Path $outPath -Value $rendered -Encoding utf8
Write-Host "  Rendered $outPath"

# 7. Create or update MCP gateway with rendered tools file
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
