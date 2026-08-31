# Spec: Рефакторинг helpdesk в монорепо с изолированными Cloud Function модулями

## Problem Statement

Разработчик Help Desk-агента получил сквозной продукт (шаги 1-9 Hexlet 425) но кодовая база фрагментирована: 4 отдельных Maven-проекта (`helpdesk`, `step6/email-sender`, `ydb2`, `ydb_qwen`), дубли `EmailSender` в двух пакетах, `PiiMasker`/`InjectionClassifier` лежат в `step8/` вне сборки, `AgentClient` не поддерживает `file_search` (шаг 7 RAG), `ydb-tickets` без PII-маскирования и защиты от инъекций (шаг 8), `workflow YAML` хардкодит `database`/`functionId` и использует `yawl: '0.2'` без шаблонов, деплой — ручное копирование файлов в `console.yandex.cloud → редактор` с хаком `<sourceDirectory>${project.basedir}</sourceDirectory>`. Невозможно собрать и протестировать одной командой, нельзя версионировать функции независимо, повторный деплой требует помнить секреты и entrypoint. Нужна упорядоченная монорепо-структура где каждая Cloud Function — отдельный модуль со своим `shaded.jar`, общий код — в `common`, деплой — скриптами `yc CLI`.

## Solution

Преобразовать репозиторий в Maven multi-module монорепо с `parent pom` в корне:

* `parent` (`pom`, `1.0.0`) — `dependencyManagement`/`pluginManagement` для всех версий (`java 21`, `yc-sdk 2.14.0`, `ydb 2.4.9`, `angus-mail 2.0.5`, `gson 2.11`, `junit 5.11`, `mockito 5.14`).
* `common` (`1.0.0`, `jar` без shade) — `PiiMasker`, `InjectionClassifier`, `YdbTransportFactory`, `SmtpEmailSender`, `JsonEventParser`, константы масок.
* `email-poller` (`1.2.0`, `shade` → `email-poller-1.2.0.jar`, `entryPoint ru.anseranser.mail.EmailHandler`) — перенос `helpdesk/mail/*`, добавление `file_search` в `AgentClient`, переход на стандартный `src/main/java`.
* `ydb-tickets` (`1.1.0`, `shade` → `ydb-tickets-1.1.0.jar`, `entryPoint ru.anseranser.ydb.YdbTicketsHandler`) — рефактор `ydb2/Handler.java` на `EventDispatcher+YdbClient`, интеграция PII/injection.
* `email-sender` (`1.0.0`, `shade` → `email-sender-1.0.0.jar`, `entryPoint ru.anseranser.mailsender.EmailSenderFunction`) — выделение из `step6`.
* `infra/` — `ydb/schema.sql` (единственный, utf8), `mcp/mcp-tools.yaml.template`, `workflow/daily-escalation.yaml.template` (`yawl: '0.2'`), `deploy/*.ps1` — отдельные скрипты на каждую функцию, берут параметры из `.env` + `yc config`.

Каждый функциональный модуль зависит от `common` и собирается `maven-shade-plugin` с `ServicesResourceTransformer` — в YC грузится один `jar`, `common` уже внутри. Сборка: `mvn clean verify` в корне. Деплой: `.\infra\deploy\deploy-<func>.ps1` → `mvn -pl common,<func> -am package` → `yc serverless function version create --source-path <func>/target/*.jar`.

## User Stories

1. Как разработчик, я хочу запустить `mvn clean verify` в корне и собрать все модули одной командой, чтобы не собирать каждый проект вручную.
2. Как разработчик, я хочу иметь `common` модуль с общим кодом, чтобы не дублировать `EmailSender`/`PiiMasker` в трёх местах.
3. Как разработчик `email-poller`, я хочу чтобы `AgentClient` вызывал `file_search` с `VECTOR_STORE_ID` перед ответом, чтобы реализовать RAG шага 7 (ответ из базы знаний со ссылкой, иначе «не знаю» → тикет).
4. Как разработчик `email-poller`, я хочу чтобы `EmailHandler` работал на стандартном `src/main/java` без хака `sourceDirectory`, чтобы сборка была предсказуема.
5. Как разработчик `email-poller`, я хочу чтобы `EmailReceiver` искал `UNSEEN` через `FlagTerm`, а `EmailTextExtractor` предпочитал `text/plain` над `text/html` и чистил HTML через `Jsoup`, чтобы корректно извлекать тело письма.
6. Как пользователь Help Desk, я хочу написать «как оформить командировку?» и получить краткое резюме (≤3 предложения) со ссылкой на документ из `step7/docs/*.md`, чтобы не ждать оператора.
7. Как пользователь, я хочу написать «сломался принтер, заведи тикет bug» и получить `ticket_id` в ответном письме, чтобы тикет появился в `tickets` + `messages`.
8. Как разработчик `ydb-tickets`, я хочу чтобы `Handler` парсил три источника события (direct `{"action":..}`, API Gateway `{"httpMethod":"POST","body":"..."}`, MCP Hub прямые аргументы) и диспетчеризовал по ключам (`user_id+category+text → create-ticket` и т.д.), чтобы не падать с `unknown action`.
9. Как разработчик `ydb-tickets`, я хочу чтобы `create-ticket` делал `UPSERT` в `tickets` + `messages` одним `YdbClient` с `prepared statements` (`$id`, `TxControl.serializableRw().setCommitTx(true)`), чтобы данные консистентны.
10. Как разработчик `ydb-tickets`, я хочу чтобы `list-my-tickets` делал `SELECT ... WHERE user_id=$user_id` и возвращал `[{"id","status","category","text","created_at"},...]`, чтобы агент показывал «мои тикеты».
11. Как разработчик `ydb-tickets`, я хочу чтобы `append-message` делал `UPSERT` в `messages` + `UPDATE tickets SET updated_at`, чтобы история диалога сохранялась и `escalation` видел свежие тикеты.
12. Как безопасник, я хочу чтобы перед каждой записью в YDB текст маскировался (`телефон → +7 (***) ***-**-NN`, `email → [email]`, `карта → ****-****-****-NNNN` последние 4), чтобы в `tickets.text`/`messages.text` не было сырого PII.
13. Как безопасник, я хочу чтобы `InjectionClassifier` работал двухуровнево (`regex` мгновенно на `ignore previous|DROP TABLE|удали все тикеты` → `injection`, иначе `yandexgpt-lite` → `safe|injection|off-topic`, `fail-open` при ошибке), и `create-ticket` с `injection` возвращал `{"error":"Запрос заблокирован модерацией"}` + лог `ALERT_INJECTION_BLOCKED`, чтобы инъекция не создавала мусор.
14. Как безопасник, я хочу чтобы логи CF не содержали сырого PII — только `action`, `user_id`, `text_length`, `has_pii`, `ticket_id`, чтобы пройти проверку шага 8.
15. Как разработчик `email-sender`, я хочу чтобы `EmailSenderFunction` принимал `{"subject","body"}` (body может быть строкой/массивом/объектом — сериализуется в pretty JSON) и отправлял на `OPERATOR_EMAIL` из `env`, чтобы `YaWL httpCall` мог слать дайджест.
16. Как оператор, я хочу чтобы workflow `daily-escalation` (`yawl: '0.2'`) раз в день делал `SELECT ... WHERE status='open' AND created_at < CurrentUtcTimestamp() - Interval('PT24H')` → `switch` (0 → `success`) → `aiStudioAgent` (дайджест по схеме) → `UPDATE ... SET status='escalated'` → `httpCall` `email-sender`, чтобы не пропустить просроченные тикеты.
17. Как DevOps, я хочу хранить `mcp-tools.yaml` и `workflow.yaml` как `*.template` с `{{YDB_TICKETS_CF_ID}}`/`{{YDB_DATABASE}}` и подставлять `CF_ID`/`DATABASE` в `deploy-*.ps1` через `yc ... get --format json | jq`, чтобы не хардкодить `d4evk9...`/`/ru-central1/...`.
18. Как DevOps, я хочу деплоить каждую функцию отдельным скриптом `infra/deploy/deploy-<func>.ps1`, который берёт `SA_ID` из `yc iam service-account get --name ai-studio-sa` и секреты из `.env` (`YDB_ENDPOINT`, `YDB_DATABASE`, `IMAP_*`, `SMTP_*`, `VECTOR_STORE_ID`), чтобы деплой воспроизводим.
19. Как DevOps, я хочу чтобы `schema.sql` был один, в `infra/ydb/schema.sql`, utf8, два `CREATE TABLE` (`tickets` с `INDEX tickets_by_user GLOBAL ON (user_id)`, `messages` с `PRIMARY KEY (ticket_id, id)`), чтобы `scripts/init_schema.py` не ломался на `schema1.sql`.
20. Как разработчик, я хочу чтобы `.gitignore` не игнорил `*.md` (кроме `.env`), чтобы `infra/README` и `step7/docs` попадали в гит, и чтобы `target/`, `*.iml`, `dependency-reduced-pom.xml` игнорелись.
21. Как разработчик, я хочу удалить `ydb_qwen/` (прототип с хардкодом `bug/open`) и `step6.iml`/`ydb*.iml`, чтобы не путаться в мусоре.
22. Как QA, я хочу проверить сквозной сценарий `принтер → bug тикет → list-my-tickets` через `yc serverless function invoke ydb-tickets` и `yc logging read`, чтобы убедиться что `mcp_call name=create-ticket` и `AGENT_OK` видны.
23. Как QA, я хочу сравнить `usage.input_tokens/output_tokens` из `Responses API` с `messages.tokens_in/tokens_out` (расхождение ≤10%), чтобы токены считаются корректно.
24. Как версионер, я хочу чтобы `parent 1.0.0` фиксировал версии зависимостей, а `common 1.0.0`, `email-poller 1.2.0`, `ydb-tickets 1.1.0`, `email-sender 1.0.0` версионировались независимо (бамп только изменённого модуля), чтобы не пересобирать всё.

## Implementation Decisions

- **Модули:** `parent:pom` в корне (`groupId ru.anseranser`, `artifactId helpdesk-parent`, `version 1.0.0`, `packaging pom`, `modules [common, email-poller, ydb-tickets, email-sender]`). `helpdesk/` переименован в `email-poller/` с сохранением `artifactId email-poller`. `ydb_qwen/` удалён. `step6/` мигрирует в `email-sender/`.
- **Версии:** `parent` управляет `dependencyManagement`/`pluginManagement`; модули объявляют `<version>` независимо но наследуют `parent.version` для `common` зависимости через `${project.version}` согласованно. При бампе `common` пересобираются зависимые через `mvn -pl common,<func> -am package`.
- **Интерфейсы:** `common` экспортирует `PiiMasker.maskPii(text)`, `InjectionClassifier.classify(text)→ safe|injection|off-topic`, `YdbTransportFactory.create(endpoint,database)→ TableClient`, `SmtpEmailSender.send(to,subject,text)`, `JsonEventParser.parse(input)→ JsonNode` + `detectAction(root)`. Функциональные модули реализуют `YcFunction<String,String>` (`EmailHandler`, `YdbTicketsHandler`, `EmailSenderFunction`) с package-private конструкторами для тестов.
- **AgentClient RAG:** `AgentClient` принимает `vectorStoreId` из `env VECTOR_STORE_ID`; `getResponse` строит `Tool.ofFileSearch(vector_store_ids=[single_id])` + `Tool.ofMcp(... requireApproval NEVER, allowedTools [append-message,create-ticket,list-my-tickets])` — один `file_search` и один `mcp` (ограничение Responses API). Промпт из `step7/docs/agent-instructions.md` инлайнится в `instructions` (обязательный `file_search` перед ответом, ≤3 предложения, ссылка).
- **YdbTickets:** `Handler.handle` → `JsonEventParser.parse` (ветка `httpMethod+body`) → `EventDispatcher.detectAction` (explicit `action` → по ключам) → `switch create-ticket|list-my-tickets|append-message`. `YdbClient` инкапсулирует `TableClient.createSession(10s)`, `executeDataQuery` с `Params.of("$id", PrimitiveValue.newText(...))`, `Instant.now()` → `Timestamp`. `create-ticket` генерирует `ticketId=UUID`, `status=open`, пишет `tickets` + `messages(role=user)`, возвращает `{"ticket_id","created_at"}`. `append-message` пишет `messages` + `UPDATE tickets SET updated_at`.
- **Security:** `PiiMasker` маскирует `PHONE_PATTERN (+7|8 ...)` → `+7 (***) ***-**-NN` (2 последние цифры), `EMAIL → [email]`, `CARD 16 цифр → ****-****-****-NNNN` (4 последние). `InjectionClassifier` — `INJECTION_PATTERNS` (en/ru: `ignore previous`, `DROP TABLE`, `удали все тикеты` и т.д.) `Pattern.CASE_INSENSITIVE` → `injection`; иначе `callLlmClassifier` (`yandexgpt-lite`, `fail-open`). В `handleCreateTicket`/`handleAppendMessage` маскированный текст идёт в YDB, логи — `System.out.println("INFO: ... has_pii="+containsPii)`, `ALERT_INJECTION_BLOCKED` при блоке.
- **EmailSenderFunction:** парсит `JsonObject`, `getStringField` сериализует массивы/объекты через `Gson prettyPrinting`, `to=HELPDESK_MAILBOX` из env, дефолты `subject=No Subject`, `body=""`, `500` если `HELPDESK_MAILBOX` blank, `502` при `MessagingException`.
- **Workflow:** `infra/workflow/daily-escalation.yaml.template` `yawl: '0.2'`, `start: fetchOverdueTickets`, `databaseQuery` (`host ydb.serverless.yandexcloud.net:2135`, `ssl true`, `iam true`, `query SELECT ... WHERE status='open' AND created_at < CurrentUtcTimestamp() - Interval('PT24H')`), `switch` с `condition: .tickets | length == 0`, `aiStudioAgent` (`promptTemplateId=fvtu3g417klcgdhf6fih`, `autoApprove true`), `databaseQuery UPDATE ... WHERE id IN (...)`, `functionCall` (`functionId={{EMAIL_SENDER_CF_ID}}`, `input {"subject":"Summary of overdue tickets","body":{{.digest.tickets}}}`), `success`. `retryPolicy` на `YDB_CALL_SERVICE_UNAVAILABLE`, `defaultRetryPolicy ALL INCLUDE` (совместимо с `0.2`).
- **Infra templates:** `infra/mcp/mcp-tools.yaml.template` — 3 tool-а с `input_json_schema` JSON-encoded строкой, `function_id: {{YDB_TICKETS_CF_ID}}`. `infra/ydb/schema.sql` — два `CREATE TABLE` utf8.
- **Сборка:** `common` — обычный `jar`, без `shade`. `email-poller`/`ydb-tickets`/`email-sender` — `maven-shade-plugin 3.6.0` `phase package goal shade`, `ManifestResourceTransformer mainClass`, `ServicesResourceTransformer`, `filter META-INF/*.SF/.DSA/.RSA`. Удаляется хак `sourceDirectory`.
- **Deploy скрипты:** `infra/deploy/deploy-*.ps1` — `Get-Content .env` → `env:`, `SA_ID=(yc iam service-account get ...)`, `FOLDER_ID=(yc config get folder-id)`, `mvn -pl common,<func> -am package -DskipTests`, `yc serverless function version create --function-name <func> --runtime java21 --entrypoint <class> --memory 256m/512m --execution-timeout 30s/120s --source-path <func>/target/<func>-<ver>.jar --service-account-id $SA_ID --secret ...`, `CF_ID=(yc serverless function get --name ... --format json | ConvertFrom-Json).id`, `envsubst` шаблонов.

## Testing Decisions

- **Принцип:** тестировать внешнее поведение `YcFunction.handle` (вход JSON → выход JSON/DB эффект/`Transport.send`), не детали имплементации. Моки для `YDB/IMAP/SMTP/LLM`, интеграция — через `yc serverless function invoke` вручную (QA шаг 9).
- **Seams (один seam на функцию):** `EmailHandler.handle(String,Context)`, `YdbTicketsHandler.handle(String,Context)`, `EmailSenderFunction.handle(String,Context)` + `PiiMasker.maskPii`/`InjectionClassifier.classify` как unit-seams в `common`. Предпочтение высшему seam (`handle`) — у `helpdesk/mail/EmailHandlerTest` уже есть паттерн `EmailHandler(receiver,sender,agent,extractor)` с `MockitoExtension`; аналогично `EmailSenderFunctionTest` в `step6`. Новые seams не нужны, кроме `YdbTransportFactory` (для теста без реального YDB — `StaticTokenProvider`).
- **Модули с тестами:** `common` — `SecurityTest` (PiiMasker + InjectionClassifier, уже в `step8/SecurityTest.java`), `YdbTransportFactory` unit; `email-poller` — `EmailHandlerTest` (6 сценариев: success, connect fail, fetch fail, send fail, parse fail, empty body), `EmailTextExtractorTest`, `AgentClient` — мок `OpenAIClient` (проверить `file_search` + `mcp` tools в `ResponseCreateParams`); `ydb-tickets` — `EventDispatcherTest` (3 источника event), `YdbClientTest` (моки `TableClient/Session`, проверка `Params`), `Handler` integration; `email-sender` — `EmailSenderFunctionTest` (valid, missing mailbox, smtp error, array/object body).
- **Prior art:** `helpdesk/src/test/java/ru/anseranser/mail/EmailHandlerTest.java:26` (`@ExtendWith(MockitoExtension.class)`, `mockMessage`, `jsonContains`), `step6/src/test/java/ru/anseranser/mailsender/EmailSenderFunctionTest.java:14`, `step8/SecurityTest.java:10` — переиспользовать стиль, добавить проверки `maskPii` на `+7 (***) ***-**-67`.

## Out of Scope

- Создание `vector-store` в AI Studio (`yandex-ai-studio vector-stores local docs/*.md --name help-desk-kb`) — ручной шаг, только `VECTOR_STORE_ID` пробрасывается в CF.
- Настройка AI Studio moderation rules (toxicity+PII) и классификатора `yandexgpt-lite` через UI — вне кода.
- Миграция на `Telegram` poller (приложение к шагу 4) — только `email-poller`.
- Интеграция `common` как отдельный артефакт в `Maven Central` — `common` только для `install` в `localRepo`.
- Terraform/Pulumi для YDB/MCP Gateway/Workflow — деплой через `yc CLI` скрипты, не IaC.
- Логирование через `SLF4J/Logback` — остаётся `System.out` (CF logging).
- Изменения схемы YDB кроме `infra/ydb/schema.sql` (два CREATE).

## Further Notes

- **Offline docs:** `E:\hexlet-llm-developer\docs\docs\ru` — источник для `YaWL 0.2` (`databaseQuery`, `aiStudioAgent`, `functionCall`) и `Responses API` (`file_search` ограничения: один `vector_store_id`, один `file_search tool`).
- **Hexlet CI:** `hexlet/project-action@release` проверяет линтер/файлы, не сборку — переименование `helpdesk→email-poller` безопасно после обновления `README.md` (убрать `*.md` игнор, добавить чек-лист сдачи шага 9).
- **Workflow SA роли:** `ydb.editor`, `ai.assistants.editor`, `ai.languageModels.user` на `folder` + `serverless.workflows.executor/viewer` на workflow — в `deploy-workflow.ps1` через `yc resource-manager folder add-access-binding` и `yc serverless workflow add-access-binding`.
- **Порядок волн:** `common → ydb-tickets → email-poller → email-sender → infra/clean → QA` — `ydb-tickets` раньше `email-poller` потому что `email-poller` зависит от `MCP URL` `ydb-tickets`.
