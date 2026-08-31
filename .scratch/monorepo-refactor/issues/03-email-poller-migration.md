# 03: email-poller — миграция helpdesk + RAG

**What to build:** Перенести `helpdesk/mail/*` в модульный `email-poller` на стандартном `src/main/java` и добавить `file_search`, чтобы `EmailHandler` по `IMAP UNSEEN` вызывал `Responses API` с `file_search` + `mcp` и отвечал по `SMTP`, а `mvn -pl email-poller test` проверял оба tool-а.

**Blocked by:** 01 Parent POM + common модуль, 02 ydb-tickets — рефактор + PII/Injection (нужен `MCP_SERVER_URL` от ydb-tickets)

**Status:** ready-for-agent

- [ ] Модуль `email-poller` (`artifactId email-poller`, `version 1.2.0`, `dependencies: common`, `openai-java 4.50.0`, `angus-mail 2.0.5`, `jsoup 1.23.1`, `java-sdk-serverless`, `gson`) создан из `helpdesk/` (переименован), структура `src/main/java/ru/anseranser/mail/` (`EmailHandler`, `EmailReceiver`, `EmailTextExtractor`, `AgentClient`, использует `common.SmtpEmailSender` + `common.JsonEventParser`), удалён дубль `helpdesk/mail/EmailSender.java` (теперь `common`)
- [ ] `AgentClient.getResponse` принимает `vectorStoreId` из `env VECTOR_STORE_ID`, строит `Tool.ofFileSearch(vector_store_ids=[single_id])` (один id — ограничение `step7/task.md:106`) + `Tool.ofMcp(serverLabel ydb-tickets, serverUrl MCP_SERVER_URL, requireApproval NEVER, allowedTools [append-message,create-ticket,list-my-tickets])`, `ResponseCreateParams` с `prompt id=AGENT_ID` + `organization`, `output.getLast().message().content` + `usage.inputTokens/outputTokens` логируются без PII
- [ ] `EmailHandler.handle` использует `common.JsonEventParser` для `From`/`subject`/`body` (`EmailTextExtractor` предпочитает `text/plain` над `text/html`, `Jsoup` чистит), `Gson Map.of("user_id",from,"text",body)` → `AgentClient`, `SmtpEmailSender.send`, `markAsSeen`, счётчик `mail(s) done`, обработка `MessagingException/IOException` per message (продолжает цикл)
- [ ] Тесты: `EmailHandlerTest` (6 сценариев из `helpdesk/.../EmailHandlerTest.java:26`), `EmailTextExtractorTest`, новый `AgentClientTest` (мок `OpenAIClient`, проверка что `ResponseCreateParams.tools()` содержит `file_search` и `mcp`), `mvn -pl email-poller test` зелёный
- [ ] `mvn -pl common,email-poller -am package -DskipTests` даёт `email-poller/target/email-poller-1.2.0.jar` (`mainClass ru.anseranser.mail.EmailHandler`)
