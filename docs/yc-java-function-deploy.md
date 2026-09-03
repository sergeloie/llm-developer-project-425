# Развертывание Java Cloud Functions в Yandex Cloud — накопленный опыт

> Сохранено 2026-09-02 после отладки `email-sender`, `email-poller`, `ydb-tickets`. Чтать перед любым изменением `infra/deploy/*.ps1`.

## 1. Что поддерживает Yandex Cloud

* Документация: `E:\hexlet-llm-developer\docs\docs\ru\functions\quickstart\create-function\java-function-quickstart.md` и `E:\hexlet-llm-developer\docs\docs\ru\cli\cli-ref\serverless\cli-ref\function\version\create.md`
* `java21` runtime требует класс с `implements YcFunction<I,O>` из `com.yandex.cloud:java-sdk-serverless:2.14.0`. Без этой зависимости функция не соберется.
* Форматы загрузки кода (`concepts/function.md:27`): ZIP с ПК, ZIP из Object Storage (S3), директория, файл. Лимит прямой загрузки `--source-path` — **3.5 МБ**. Если архив больше — грузить через бакет (`--package-bucket-name` / `--package-object-name`) или уменьшить размер.
* При загрузке **ZIP с исходниками** (`pom.xml` + `src/`) сборка идет в Builder в облаке (`concepts/builder.md`). Builder запускает `mvn package` и ожидает `maven-shade-plugin` с `ServicesResourceTransformer`.

## 2. Почему нужен именно ZIP с исходниками, а не JAR

* `mvn -pl common,module -am package -DskipTests` локально дает `shaded.jar ~41 МБ` (все зависимости `ydb/jsoup/openai` тянутся транзитивно через `common`). 41 МБ > 3.5 МБ → `ERROR: zip archive content exceeds the maximum size 3.5 MB`.
* Вариант 1 — грузить JAR через Object Storage — работает, но требует бакет, права SA и усложняет скрипты.
* Вариант 2 — **собирать в облаке из ZIP с исходниками**. ZIP содержит только `pom.xml` + 2-7 `.java` файлов → размер ZIP **4-20 КБ**, собирается в облаке в уже правильный `shaded.jar` (~1-5 МБ). Это повторяет эталонный проект `helpdesk/` из репозитория, который на сайте Yandex создается именно как `pom.xml + несколько java`.

Выбран **Вариант 2** для всех 3 функций.

## 3. Структура ZIP (критично!)

```
pom.xml                          # в корне архива, standalone без parent
src/main/java/ru/anseranser/mailsender/EmailSenderFunction.java
src/main/java/ru/anseranser/mail/SmtpEmailSender.java
```

* **Пути внутри ZIP должны быть с `/`, а не `\`**! `Compress-Archive` на Windows пишет `src\main\...` → Yandex Console показывает каталоги как файлы `src\main`, `src\main\java` и не дает выбрать класс как точку входа. 
* Правильно: создавать архив через `System.IO.Compression.ZipArchive` и указывать entryName с `/` (`src/main/java/...`). См. `infra/deploy/deploy-*.ps1` — функция `Add-FileToZip`.
* Не включать `target/`, `dependency-reduced-pom.xml`, пустые записи директорий. Только `pom.xml` и `*.java`.
* Проверка: `Add-Type -AssemblyName System.IO.Compression.FileSystem; [IO.Compression.ZipFile]::OpenRead("email-sender.zip").Entries | % FullName` — все записи должны быть с `/`.

## 4. Standalone pom.xml

Исходный монорепозиторий — `parent pom (helpdesk-parent) + common + 3 модуля`. В облаке `common` как артефакт недоступен (не в Maven Central). Поэтому для каждого ZIP делаем **плоский pom без parent**, в котором:

* `groupId: ru.anseranser`, `artifactId: <module>`, `version: 1.x.x`, `packaging: jar`
* `<properties>` скопированы из `parent/pom.xml` (`yc-sdk.version`, `ydb.version`, `angus-mail.version` и т.д.)
* Зависимости — только нужные для модуля (см. таблицу), плюс `java-sdk-serverless` обязательно:
  * `email-sender`: `java-sdk-serverless`, `angus-mail`, `gson`
  * `email-poller`: `java-sdk-serverless`, `openai-java`, `angus-mail`, `jsoup`, `gson`
  * `ydb-tickets`: `java-sdk-serverless`, `ydb-sdk-table`, `yc-auth-provider`, `jackson-databind/core/annotations` + `ydb-sdk-bom` в `dependencyManagement`
* Не включать `common` как dependency! Вместо этого копируем нужные `*.java` из `common/src/main/java` прямо в `src/main/java` внутри ZIP (см. ниже).
* `build` — как в `helpdesk/pom.xml`: `maven-compiler-plugin` + `maven-shade-plugin` с:
  ```xml
  <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
    <mainClass>ru.anseranser.mailsender.EmailSenderFunction</mainClass>
  </transformer>
  <transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
  ```
  и фильтром `META-INF/*.SF|DSA|RSA`.

Эталон: `helpdesk/pom.xml` — можно копировать оттуда.

## 5. Какие файлы из `common` копировать

| Функция | Копируемые `common/*.java` | Причина |
|---------|----------------------------|---------|
| `email-sender` | `mail/SmtpEmailSender.java` | `EmailSenderFunction` использует `SmtpEmailSender` напрямую |
| `email-poller` | `mail/SmtpEmailSender.java` | `EmailHandler` (пакет `ru.anseranser.mail`) использует `SmtpEmailSender` из того же пакета — без импорта |
| `ydb-tickets` | `json/JsonEventParser.java`, `pii/PiiMasker.java`, `security/InjectionClassifier.java`, `ydb/YdbTransportFactory.java` | `YdbTicketsHandler` импортирует `JsonEventParser`, `PiiMasker`, `InjectionClassifier`; `YdbClient` использует `YdbTransportFactory.create()` |

Остальные файлы `common` не нужны — они только увеличивают время сборки.

## 6. Точка входа (entrypoint)

* **Обязательно FQN с пакетом**, например `ru.anseranser.mailsender.EmailSenderFunction`, а не `EmailSenderFunction` или `Handler`. 
* В `yc serverless function version create` флаг `--entrypoint` должен совпадать с `mainClass` в `shade` plugin и с `package + class` внутри ZIP.
* Таблица:

| Функция | `--function-name` | `--entrypoint` | `--runtime` |
|---------|-------------------|----------------|-------------|
| `email-sender` | `email-sender` | `ru.anseranser.mailsender.EmailSenderFunction` | `java21` |
| `email-poller` | `email-poller` | `ru.anseranser.mail.EmailHandler` | `java21` |
| `ydb-tickets` | `ydb-tickets` | `ru.anseranser.ydb.YdbTicketsHandler` | `java21` |

## 7. Флаги `yc serverless function version create` (актуально на 2026-09-02)

Сверено с `create.md` и `yc --help`:

```
--function-name string          # имя функции
--runtime string                # java21
--entrypoint string             # FQN класса
--memory byteSize               # 256MB / 512MB (писать MB/GB, не m!)
--execution-timeout duration    # 30s / 120s
--source-path string            # путь к ZIP (для >3.5МБ - только Object Storage)
--service-account-id string     # SA для функции
--environment stringToString    # FOO=bar,BAZ=qux
--secret PROPERTY=VALUE,...     # см. ниже
```

* `--secret` — **только kebab-case**: `environment-variable`, `name` или `id`, `version-id`, `key`. Не `environmentVariable`, не `sourceId`, не `versionId`! Примеры (проект использует один секрет `email-credentials` с `key=password` на обе переменные):
  ```bash
  --secret environment-variable=IMAP_PASSWORD,name=email-credentials,key=password,version-id=latest
  --secret environment-variable=SMTP_PASSWORD,name=email-credentials,key=password,version-id=latest
  --secret environment-variable=YANDEX_API_KEY,name=yandex-api-key,version-id=latest
  ```
* `--memory` — писать `256MB`, `512MB`, а не `256m`. Дока: `byteSize` с примерами `'128MB', '1GB'`.
* Для ZIP-архива >3.5МБ использовать `--package-bucket-name` / `--package-object-name` вместо `--source-path` (см. `function.md:27`).

## 8. Шаблон скрипта деплоя (PowerShell)

Ключевые фрагменты из `infra/deploy/deploy-email-sender.ps1`:

```powershell
$ProjectRoot = Resolve-Path "$PSScriptRoot\..\.."
$STAGE = Join-Path $env:TEMP "opencode\email-sender-zip"
$ZIP_PATH = Join-Path $ProjectRoot "email-sender.zip"

if (Test-Path $STAGE) { Remove-Item -Recurse -Force $STAGE }
New-Item -ItemType Directory -Path "$STAGE\src\main\java\ru\anseranser\mailsender" -Force | Out-Null
Copy-Item "$ProjectRoot\email-sender\src\main\java\ru\anseranser\mailsender\EmailSenderFunction.java" "$STAGE\..."
Copy-Item "$ProjectRoot\common\src\main\java\ru\anseranser\mail\SmtpEmailSender.java" "$STAGE\..."

Set-Content -Path "$STAGE\pom.xml" -Value $pom -Encoding UTF8  # $pom — heredoc standalone pom

# ВАЖНО: создаем ZIP с '/' через ZipArchive
Add-Type -AssemblyName System.IO.Compression
$stream = [System.IO.File]::Create($ZIP_PATH)
$archive = New-Object System.IO.Compression.ZipArchive($stream, [IO.Compression.ZipArchiveMode]::Create)
function Add-FileToZip($archive,$src,$entry){ $e=$archive.CreateEntry($entry,[CompressionLevel]::Optimal); $es=$e.Open(); $b=[IO.File]::ReadAllBytes($src); $es.Write($b,0,$b.Length); $es.Close() }
Add-FileToZip $archive "$STAGE\pom.xml" "pom.xml"
Add-FileToZip $archive "$STAGE\src\main\java\ru\anseranser\mailsender\EmailSenderFunction.java" "src/main/java/ru/anseranser/mailsender/EmailSenderFunction.java"
$archive.Dispose(); $stream.Close()

# Деплой
yc serverless function version create `
    --function-name email-sender `
    --runtime java21 `
    --entrypoint ru.anseranser.mailsender.EmailSenderFunction `
    --memory 256MB `
    --execution-timeout 30s `
    --source-path $ZIP_PATH `
    --service-account-id $SA_ID ...
```

## 9. Типичные ошибки и как их избегать

| Симптом | Причина | Решение |
|---------|---------|---------|
| `zip archive content exceeds the maximum size 3.5 MB` | Грузишь `shaded.jar` (41 МБ) через `--source-path` | Перейти на ZIP с исходниками (4 КБ) или грузить JAR через бакет |
| В консоли Yandex файлы `src\main`, `src\main\java` как файлы | ZIP создан `Compress-Archive` с `\` | Пересоздать через `ZipArchive` с `/` |
| `Cannot find class EmailSenderFunction` / `Handler not found` | `--entrypoint` без пакета (`Handler` вместо `ru.anseranser...`) | Указывать FQN, как в `helpdesk` и в `deploy-*.ps1` |
| `Could not resolve dependency ru.anseranser:common` | В ZIP `pom.xml` остался `<parent>` или `common` dependency | Сделать standalone pom без parent/common, скопировать *.java |
| `Missing YcFunction` | Нет `java-sdk-serverless` в pom | Добавить `com.yandex.cloud:java-sdk-serverless:2.14.0` |
| `Unknown property sourceId/versionId/environmentVariable` | Неверный кейс в `--secret` | Использовать `name`/`version-id`/`environment-variable` (kebab-case) |

## 10. Проверка перед деплоем

* Локально: распаковать stage и `mvn clean package -DskipTests` — должен быть `BUILD SUCCESS` и `jar ~1-2 МБ`.
* В ZIP: `ZipFile.OpenRead(zip).Entries.FullName` — все с `/`, есть `pom.xml` в корне.
* В консоли: после загрузки ZIP должны появиться **папки** `src/main/java/...`, а не файлы `src\main`.

## 11. Ссылки

* `E:\hexlet-llm-developer\docs\docs\ru\functions\quickstart\create-function\java-function-quickstart.md` — эталон ZIP с `Handler.java`
* `E:\hexlet-llm-developer\docs\docs\ru\cli\cli-ref\serverless\cli-ref\function\version\create.md` — все флаги `version create`
* `E:\hexlet-llm-developer\docs\docs\ru\functions\concepts\function.md` — лимиты загрузки и форматы
* `helpdesk/pom.xml` — эталон standalone pom для облака
* `infra/deploy/deploy-*.ps1` — рабочие скрипты генерации ZIP
