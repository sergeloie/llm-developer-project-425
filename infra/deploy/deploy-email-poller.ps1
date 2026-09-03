# deploy-email-poller.ps1 — deploy email-poller Cloud Function via ZIP with sources
# Trigger: cron or manual; handles IMAP fetch + AI agent + SMTP reply
# Принцип: standalone zip (pom.xml + java) с '/' в путях. См. docs/yc-java-function-deploy.md
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

# 2. Resolve SA and folder via yc CLI JSON
Write-Host "Resolving service account..." -ForegroundColor Cyan
$SA_ID = (yc iam service-account get --name ai-studio-sa --format json | ConvertFrom-Json).id
if (-not $SA_ID) { throw "Failed to get SA_ID for ai-studio-sa" }
$FOLDER_ID = (yc config get folder-id)
if (-not $FOLDER_ID) { throw "Failed to get folder-id" }
Write-Host "  SA_ID=$SA_ID"
Write-Host "  FOLDER_ID=$FOLDER_ID"

# 3. Build ZIP with sources for Yandex Cloud Builder
Write-Host "Building email-poller.zip (standalone sources)..." -ForegroundColor Cyan
$ProjectRoot = Resolve-Path "$PSScriptRoot\..\.."
$STAGE = Join-Path $env:TEMP "opencode\email-poller-zip"
$ZIP_PATH = Join-Path $ProjectRoot "email-poller.zip"

if (Test-Path $STAGE) { Remove-Item -Recurse -Force $STAGE }
New-Item -ItemType Directory -Path "$STAGE\src\main\java\ru\anseranser\mail" -Force | Out-Null

# 3.1 Copy java sources: весь модуль email-poller + нужный файл из common
Get-ChildItem -Path "$ProjectRoot\email-poller\src\main\java\ru\anseranser\mail\*.java" | ForEach-Object {
    Copy-Item -Path $_.FullName -Destination "$STAGE\src\main\java\ru\anseranser\mail\"
}
Copy-Item -Path "$ProjectRoot\common\src\main\java\ru\anseranser\mail\SmtpEmailSender.java" -Destination "$STAGE\src\main\java\ru\anseranser\mail\" -Force

$files = Get-ChildItem "$STAGE\src\main\java\ru\anseranser\mail\*.java"
Write-Host "  Copied $($files.Count) java files:"
$files | ForEach-Object { Write-Host "    $($_.Name)" }

# 3.2 Standalone pom.xml (без parent, без common)
$pom = @'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
  xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>ru.anseranser</groupId>
  <artifactId>email-poller</artifactId>
  <version>1.2.0</version>
  <packaging>jar</packaging>
  <name>email-poller</name>
  <description>Email poller Cloud Function with RAG - standalone for Yandex Cloud Builder</description>
  <properties>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <maven.compiler.release>21</maven.compiler.release>
    <yc-sdk.version>2.14.0</yc-sdk.version>
    <angus-mail.version>2.0.5</angus-mail.version>
    <gson.version>2.11.0</gson.version>
    <jsoup.version>1.23.1</jsoup.version>
    <openai.version>4.50.0</openai.version>
    <junit.version>5.11.0</junit.version>
    <mockito.version>5.14.2</mockito.version>
  </properties>
  <dependencies>
    <dependency><groupId>com.yandex.cloud</groupId><artifactId>java-sdk-serverless</artifactId><version>${yc-sdk.version}</version></dependency>
    <dependency><groupId>com.openai</groupId><artifactId>openai-java</artifactId><version>${openai.version}</version></dependency>
    <dependency><groupId>org.eclipse.angus</groupId><artifactId>angus-mail</artifactId><version>${angus-mail.version}</version></dependency>
    <dependency><groupId>org.jsoup</groupId><artifactId>jsoup</artifactId><version>${jsoup.version}</version></dependency>
    <dependency><groupId>com.google.code.gson</groupId><artifactId>gson</artifactId><version>${gson.version}</version></dependency>
    <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><version>${junit.version}</version><scope>test</scope></dependency>
    <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter-params</artifactId><version>${junit.version}</version><scope>test</scope></dependency>
    <dependency><groupId>org.mockito</groupId><artifactId>mockito-core</artifactId><version>${mockito.version}</version><scope>test</scope></dependency>
    <dependency><groupId>org.mockito</groupId><artifactId>mockito-junit-jupiter</artifactId><version>${mockito.version}</version><scope>test</scope></dependency>
  </dependencies>
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
                <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer"><mainClass>ru.anseranser.mail.EmailHandler</mainClass></transformer>
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
Get-ChildItem "$STAGE\src\main\java\ru\anseranser\mail\*.java" | ForEach-Object {
    $rel = "src/main/java/ru/anseranser/mail/$($_.Name)"
    Add-FileToZip $archive $_.FullName $rel
}
$archive.Dispose()
$stream.Close()
$stream.Dispose()
Write-Host "  ZIP created: $ZIP_PATH ($((Get-Item $ZIP_PATH).Length) bytes)" -ForegroundColor Green
$z = [System.IO.Compression.ZipFile]::OpenRead($ZIP_PATH)
$z.Entries | ForEach-Object { Write-Host "    $($_.FullName)" }
$z.Dispose()

# 4. Deploy function version — FQN entrypoint (create function if not exists)
Write-Host "Deploying email-poller function from ZIP..." -ForegroundColor Cyan
try {
    $oldEA = $ErrorActionPreference; $ErrorActionPreference = "Continue"
    $exists = yc serverless function get --name email-poller --format json 2>&1
    $ErrorActionPreference = $oldEA
} catch { $exists = $null; $ErrorActionPreference = "Continue" }
if ($LASTEXITCODE -ne 0 -or -not $exists -or $exists -match "not found") {
    Write-Host "  Function email-poller not found, creating..." -ForegroundColor Yellow
    yc serverless function create --name email-poller --description "HelpDesk email poller IMAP->RAG->MCP->SMTP"
    if ($LASTEXITCODE -ne 0) { throw "yc function create failed for email-poller" }
}
yc serverless function version create `
    --function-name email-poller `
    --runtime java21 `
    --entrypoint ru.anseranser.mail.EmailHandler `
    --memory 512MB `
    --execution-timeout 120s `
    --source-path $ZIP_PATH `
    --service-account-id $SA_ID `
    --environment IMAP_HOST=$env:IMAP_HOST,IMAP_USER=$env:IMAP_USER,SMTP_HOST=$env:SMTP_HOST,SMTP_PORT=$env:SMTP_PORT,SMTP_USER=$env:SMTP_USER,HELPDESK_MAILBOX=$env:HELPDESK_MAILBOX,YDB_ENDPOINT=$env:YDB_ENDPOINT,YDB_DATABASE=$env:YDB_DATABASE,AGENT_ID=$env:AGENT_ID,ORGANIZATION_ID=$env:ORGANIZATION_ID,MCP_SERVER_URL=$env:MCP_SERVER_URL,VECTOR_STORE_ID=$env:VECTOR_STORE_ID `
    --secret environment-variable=IMAP_PASSWORD,name=email-credentials,key=password `
    --secret environment-variable=SMTP_PASSWORD,name=email-credentials,key=password `
    --secret environment-variable=YANDEX_API_KEY,name=agent-api-key,key=agent-api-key

if ($LASTEXITCODE -ne 0) { throw "yc function version create failed for email-poller" }

# 5. Fetch CF_ID for verification
$CF_ID = (yc serverless function get --name email-poller --format json | ConvertFrom-Json).id
Write-Host "  EMAIL_POLLER_CF_ID=$CF_ID" -ForegroundColor Cyan

Write-Host "email-poller deploy finished." -ForegroundColor Green
