# Инвентаризация файлов проекта — актуально на 2026-09-02

> Обновлено после: переход на ZIP с исходниками для Yandex Cloud Functions (исправлены `infra/deploy/*.ps1` — теперь `ZipArchive` с `/`, FQN `entrypoint`, standalone `pom`), чистка legacy, `mvn verify` BUILD SUCCESS.

## Принцип

* **Оставить** — нужно для `mvn clean verify`, деплоя `infra/deploy/*.ps1`, воспроизведения RAG/YDB, Hexlet CI (`hexlet-check.yml`).
* **Удалено 2026-09-02** — дубли, legacy-модули, `shaded.jar` артефакты, сгенерированные ZIP, IDE/секреты.
* `steps/` — оставлен по требованию (задания Hexlet).

---

## 1. Оставить (актуальные)

| Путь | Зачем | Примечание |
|---|---|---|
| `pom.xml` | Parent `helpdesk-parent 1.0.0` `packaging pom` `modules [common,email-poller,ydb-tickets,email-sender]` | BOM, `dependencyManagement` |
| `common/` | Shared либа: `PiiMasker`, `InjectionClassifier`, `YdbTransportFactory`, `SmtpEmailSender`, `JsonEventParser` | Зависимость для 3 функций, собирается отдельно |
| `common/pom.xml` | `artifactId common 1.0.0` | Без shade |
| `common/src/main/java/ru/anseranser/**` | 5 классов `json`, `mail`, `pii`, `security`, `ydb` | |
| `common/src/test/java/**` | Тесты `common` | |
| `email-poller/` | CF `email-poller 1.2.0` `entrypoint ru.anseranser.mail.EmailHandler` `java21` | Основная RAG-функция |
| `email-poller/pom.xml` | `shade` + `openai-java`, `jsoup`, `gson`, `angus-mail` | Parent-зависимость, в ZIP заменяется на standalone |
| `email-poller/src/main/java/ru/anseranser/mail/*` | `EmailHandler`, `EmailReceiver`, `EmailTextExtractor`, `AgentClient` | |
| `email-poller/src/test/java/**` | Тесты | |
| `ydb-tickets/` | CF `ydb-tickets 1.1.0` `entrypoint ru.anseranser.ydb.YdbTicketsHandler` | |
| `ydb-tickets/pom.xml` | `shade` + `ydb-sdk-table`, `yc-auth`, `jackson` | |
| `ydb-tickets/src/main/java/ru/anseranser/ydb/*` | `EventDispatcher`, `YdbClient`, `YdbTicketsHandler` | |
| `ydb-tickets/src/test/java/**` | 42 теста | |
| `email-sender/` | CF `email-sender 1.0.0` `entrypoint ru.anseranser.mailsender.EmailSenderFunction` | Wrapper для YaWL `httpCall` |
| `email-sender/pom.xml` | `shade` + `java-sdk-serverless`, `gson`, `angus-mail` | |
| `email-sender/src/main/java/ru/anseranser/mailsender/EmailSenderFunction.java` | Единственный класс CF | |
| `email-sender/src/test/java/**` | 12 тестов | |
| `infra/` | Шаблоны + скрипты деплоя | |
| `infra/ydb/schema.sql` | DDL `tickets`+`messages` utf8 | Единственный источник, `schema.sql` в корне удален |
| `infra/mcp/mcp-tools.yaml.template` | 3 tool-а с `{{YDB_TICKETS_CF_ID}}` | |
| `infra/workflow/daily-escalation.yaml.template` | `yawl: '0.2'` с `{{YDB_DATABASE}}/{{EMAIL_SENDER_CF_ID}}` | |
| `infra/deploy/deploy-ydb-tickets.ps1` | **NEW 2026-09-02**: генерирует `ydb-tickets.zip` (standalone `pom` + 7 `*.java` с `/` через `ZipArchive`), `yc function version create --source-path ydb-tickets.zip --entrypoint ru.anseranser.ydb.YdbTicketsHandler` + `envsubst` + `mcp-gateway` | |
| `infra/deploy/deploy-email-poller.ps1` | **NEW**: генерирует `email-poller.zip` (5 `mail/*.java` + `pom`), FQN `ru.anseranser.mail.EmailHandler`, `--secret environment-variable=...,name=...,version-id=...` | |
| `infra/deploy/deploy-email-sender.ps1` | **NEW**: генерирует `email-sender.zip` (2 `*.java` + `pom`), FQN `ru.anseranser.mailsender.EmailSenderFunction`, `--memory 256MB` | |
| `infra/deploy/deploy-workflow.ps1` | `yc workflow create/update` `yawl: '0.2'` | Без изменений |
| `docs/yc-java-function-deploy.md` | **NEW 2026-09-02**: накопленный опыт деплоя Java Functions (3.5 МБ лимит, ZIP с `/` vs `Compress-Archive`, standalone `pom`, FQN, `--secret` kebab-case) | Чтобы не исследовать повторно |
| `rag_docs/*.md` | RAG корпус 15 доков + `agent-instructions.md` (перемещено из `step7/docs` 2026-09-02) | Источник `vector-store`, используется `AgentClient`/`upload-rag.sh` |
| `steps/step-1..9.md` | Задания Hexlet | Оставлены по требованию |
| `.env.example` | Шаблон без значений | |
| `.env` | Реальные значения, `gitignore` | Не коммитить |
| `.gitignore` | `target/`, `*.iml`, `*.jar`, `*.zip`, `token.txt`, `/.idea/` | `*.zip` игнорирует сгенерированные `*.zip` |
| `.github/workflows/hexlet-check.yml` | CI | |
| `README.md` | Архитектура монорепо, деплой, RAG | |
| `.agents/skills/**` | Локальные скилы | |
| `.scratch/monorepo-refactor/` | Трекер монорепо-рефакторинга (spec + 6 issues `done`) | История, не требуется для сборки |

---

## 2. Удалено 2026-09-02 (фактически `git rm` / `Remove-Item`)

| Путь | Почему удалено |
|---|---|
| `helpdesk/` | Legacy, полностью мигрирован в `email-poller` (дубль `mail/*`, `pom.xml`, `.mvn/`, `target/`) |
| `helpdesk.iml`, `step6.iml`, `ydb2.iml`, `ydb_qwen.iml`, `*.iml` | IDE, теперь `*.iml` в `.gitignore` |
| `step6/` | Мигрирован в `email-sender` (`elevate-overdue-tickets.yaml` хардкод) → заменен `infra/workflow` |
| `ydb2/` | Дубль `ydb-tickets` (`Handler.java` 309 строк), `Main.java`, `test-payloads/`, `target/`, `.mvn/` |
| `ydb_qwen/` | Прототип `Handler.java` 136 строк, `.mvn/`, `target/` |
| `plans/` | 3 плана (`add-mcp-tools...`, `array-support...`, `refactor-mail...`) — заменены `docs/yc-java-function-deploy.md` + `.scratch` |
| `step8/`, `step9/` | Legacy копии `PiiMasker`, `SecurityTest`, QA `check-tokens.ps1` — перенесено в `common` / `infra` |
| `schema.sql` (корень) | Дубль `infra/ydb/schema.sql` (cp1251 грязь) |
| `mcp-tools.yaml` (корень) | Дубль `infra/mcp/mcp-tools.yaml.template` (хардкод `function_id` вместо `{{YDB_TICKETS_CF_ID}}`) |
| `win_commands.md` | Временные `yc` команды шага 2-3 |
| `token.txt` | Секрет IAM, уже в `.gitignore`, удален физически |
| `email-sender.zip`, `email-poller.zip`, `ydb-tickets.zip` | Сгенерированные ZIP для деплоя (4-12 КБ), игнорируются `*.zip`, пересоздаются скриптами |
| `*/target/`, `*/dependency-reduced-pom.xml`, `*.jar`, `*.class` | Артефакты `mvn package` / `shade`, игнорируются `target/` |

Команда:
```powershell
git rm -r helpdesk step6 ydb2 ydb_qwen plans step8 step9 2>$null; Remove-Item -Recurse -Force helpdesk,step6,ydb2,ydb_qwen,plans,step8,step9 -ErrorAction SilentlyContinue
git rm -f schema.sql mcp-tools.yaml win_commands.md 2>$null; Remove-Item -Force schema.sql,mcp-tools.yaml,win_commands.md,token.txt,*.zip -ErrorAction SilentlyContinue
Get-ChildItem -Filter *.iml -Recurse -Force | Remove-Item -Force
Get-ChildItem -Filter dependency-reduced-pom.xml -Recurse -Force | Remove-Item -Force
mvn clean
```

---

## 3. Итоговая структура после чистки

```
.
├── .agents/
├── .github/workflows/hexlet-check.yml
├── .gitignore
├── .env.example
├── README.md
├── PROJECT_FILES_INVENTORY.md   # этот файл
├── pom.xml
├── common/
├── email-poller/
├── ydb-tickets/
├── email-sender/
├── docs/
│   └── yc-java-function-deploy.md
├── infra/
│   ├── ydb/schema.sql
│   ├── mcp/mcp-tools.yaml.template
│   ├── workflow/daily-escalation.yaml.template
│   └── deploy/
│       ├── deploy-ydb-tickets.ps1   # zip + FQN
│       ├── deploy-email-poller.ps1  # zip + FQN
│       ├── deploy-email-sender.ps1  # zip + FQN
│       └── deploy-workflow.ps1
├── rag_docs/*.md                # RAG корпус 15 + agent-instructions.md (ex-step7/docs)
└── steps/step-1..9.md           # задания Hexlet
```

`*_/*.zip`, `*/target/`, `*.jar`, `*.iml`, `token.txt` — игнорируются `.gitignore`, не коммитить.

`mvn clean verify` → `BUILD SUCCESS` (common + email-poller + ydb-tickets + email-sender).

Архив `helpdesk`/`ydb2`/`step6` сохранен в ветке `archive/legacy` при необходимости.
