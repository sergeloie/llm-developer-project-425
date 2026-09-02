# deploy-email-sender.ps1 — deploy email-sender Cloud Function via ZIP with sources
# Requires: yc CLI authenticated, .env in repo root
# Принцип: собираем standalone zip (pom.xml + java файлы) и грузим через --source-path.
# ВАЖНО: zip должен содержать пути с '/' (не '\'), иначе Yandex Console покажет файлы src\main как файлы.
# Используем System.IO.Compression.ZipArchive напрямую, а НЕ Compress-Archive.
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

# 3. Build ZIP with sources for Yandex Cloud Builder (standalone, без parent/common)
Write-Host "Building email-sender.zip (standalone sources)..." -ForegroundColor Cyan
$ProjectRoot = Resolve-Path "$PSScriptRoot\..\.."
$STAGE = Join-Path $env:TEMP "opencode\email-sender-zip"
$ZIP_PATH = Join-Path $ProjectRoot "email-sender.zip"

if (Test-Path $STAGE) { Remove-Item -Recurse -Force $STAGE }
New-Item -ItemType Directory -Path "$STAGE\src\main\java\ru\anseranser\mailsender" -Force | Out-Null
New-Item -ItemType Directory -Path "$STAGE\src\main\java\ru\anseranser\mail" -Force | Out-Null

# 3.1 Copy java sources (email-sender + нужный файл из common)
Copy-Item -Path "$ProjectRoot\email-sender\src\main\java\ru\anseranser\mailsender\EmailSenderFunction.java" -Destination "$STAGE\src\main\java\ru\anseranser\mailsender\"
Copy-Item -Path "$ProjectRoot\common\src\main\java\ru\anseranser\mail\SmtpEmailSender.java" -Destination "$STAGE\src\main\java\ru\anseranser\mail\"
if (-not (Test-Path "$STAGE\src\main\java\ru\anseranser\mailsender\EmailSenderFunction.java")) { throw "EmailSenderFunction.java not found" }
if (-not (Test-Path "$STAGE\src\main\java\ru\anseranser\mail\SmtpEmailSender.java")) { throw "SmtpEmailSender.java not found" }

# 3.2 Create standalone pom.xml (без parent, без зависимости common). См. docs/yc-java-function-deploy.md
$pom = @'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
  xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>ru.anseranser</groupId>
  <artifactId>email-sender</artifactId>
  <version>1.0.0</version>
  <packaging>jar</packaging>
  <name>email-sender</name>
  <description>Cloud Function for sending emails via SMTP - standalone for Yandex Cloud Builder</description>
  <properties>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <maven.compiler.release>21</maven.compiler.release>
    <yc-sdk.version>2.14.0</yc-sdk.version>
    <angus-mail.version>2.0.5</angus-mail.version>
    <gson.version>2.11.0</gson.version>
    <junit.version>5.11.0</junit.version>
    <mockito.version>5.14.2</mockito.version>
  </properties>
  <dependencies>
    <dependency>
      <groupId>com.yandex.cloud</groupId>
      <artifactId>java-sdk-serverless</artifactId>
      <version>${yc-sdk.version}</version>
    </dependency>
    <dependency>
      <groupId>org.eclipse.angus</groupId>
      <artifactId>angus-mail</artifactId>
      <version>${angus-mail.version}</version>
    </dependency>
    <dependency>
      <groupId>com.google.code.gson</groupId>
      <artifactId>gson</artifactId>
      <version>${gson.version}</version>
    </dependency>
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <version>${junit.version}</version>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.mockito</groupId>
      <artifactId>mockito-core</artifactId>
      <version>${mockito.version}</version>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.mockito</groupId>
      <artifactId>mockito-junit-jupiter</artifactId>
      <version>${mockito.version}</version>
      <scope>test</scope>
    </dependency>
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
                <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer"><mainClass>ru.anseranser.mailsender.EmailSenderFunction</mainClass></transformer>
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

# 3.3 Create ZIP with forward slashes (важно! Compress-Archive пишет '\' и Yandex ломается)
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
Add-FileToZip $archive "$STAGE\src\main\java\ru\anseranser\mail\SmtpEmailSender.java" "src/main/java/ru/anseranser/mail/SmtpEmailSender.java"
Add-FileToZip $archive "$STAGE\src\main\java\ru\anseranser\mailsender\EmailSenderFunction.java" "src/main/java/ru/anseranser/mailsender/EmailSenderFunction.java"
$archive.Dispose()
$stream.Close()
$stream.Dispose()
Write-Host "  ZIP created: $ZIP_PATH ($((Get-Item $ZIP_PATH).Length) bytes)" -ForegroundColor Green
# Verify
$z = [System.IO.Compression.ZipFile]::OpenRead($ZIP_PATH)
$z.Entries | ForEach-Object { Write-Host "    $($_.FullName)" }
$z.Dispose()

# 4. Get function ID if exists (for later workflow substitution) — optional before create
$CF_ID_BEFORE = $null
try {
    $CF_ID_BEFORE = (yc serverless function get --name email-sender --format json | ConvertFrom-Json).id
} catch {}

# 5. Deploy function version — входная точка с полным пакетом
Write-Host "Deploying email-sender function from ZIP..." -ForegroundColor Cyan
yc serverless function version create `
    --function-name email-sender `
    --runtime java21 `
    --entrypoint ru.anseranser.mailsender.EmailSenderFunction `
    --memory 256MB `
    --execution-timeout 30s `
    --source-path $ZIP_PATH `
    --service-account-id $SA_ID `
    --environment SMTP_HOST=$env:SMTP_HOST,SMTP_PORT=$env:SMTP_PORT,SMTP_USER=$env:SMTP_USER,HELPDESK_MAILBOX=$env:HELPDESK_MAILBOX `
    --secret environment-variable=SMTP_PASSWORD,name=smtp-password,version-id=latest

if ($LASTEXITCODE -ne 0) { throw "yc function version create failed for email-sender" }

# 6. Fetch deployed function ID (used by workflow template)
Write-Host "Fetching email-sender function ID..." -ForegroundColor Cyan
$CF_ID = (yc serverless function get --name email-sender --format json | ConvertFrom-Json).id
if (-not $CF_ID) { throw "Failed to get CF_ID for email-sender" }
Write-Host "  EMAIL_SENDER_CF_ID=$CF_ID"

Write-Host "email-sender deploy finished. CF_ID=$CF_ID" -ForegroundColor Green
Write-Host "  Next: run deploy-workflow.ps1 to update workflow with this CF_ID." -ForegroundColor Yellow
