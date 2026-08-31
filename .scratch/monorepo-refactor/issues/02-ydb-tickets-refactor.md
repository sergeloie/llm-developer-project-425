# 02: ydb-tickets — рефактор + PII/Injection

**What to build:** Превратить `ydb2/Handler.java` в модульный `ydb-tickets` с `EventDispatcher+YdbClient` и встроенной защитой, чтобы `yc serverless function invoke ydb-tickets` по трём источникам event создавал маскированные тикеты и блокировал инъекции, а логи не текли PII.

**Blocked by:** 01 Parent POM + common модуль

**Status:** ready-for-agent

- [ ] Создан модуль `ydb-tickets` (`artifactId ydb-tickets`, `version 1.1.0`, `dependencies: common`, `tech.ydb ydb-sdk-table`, `yc-auth`, `jackson-databind`, `java-sdk-serverless`), `maven-shade-plugin` (`ManifestResourceTransformer mainClass ru.anseranser.ydb.YdbTicketsHandler`, `ServicesResourceTransformer`)
- [ ] Рефактор `ydb2/Handler.java:23` → `ru.anseranser.ydb.YdbTicketsHandler` (YcFunction) + `EventDispatcher` (парсит `direct {"action"}`, `API Gateway {"httpMethod","body"}`, `MCP Hub` прямые ключи `user_id+category+text / user_id / ticket_id+role+text`) + `YdbClient` (методы `createTicket/listMyTickets/appendMessage`, `Params.of("$id", PrimitiveValue.newText)`, `TxControl.serializableRw().setCommitTx(true)`, `Instant.now()`)
- [ ] Интеграция `common.PiiMasker` перед каждым `UPSERT` (`tickets.text`/`messages.text` → `+7 (***) ***-**-NN`, `[email]`, `****-****-****-NNNN`), `common.InjectionClassifier` двухуровнево (`regex` на `ignore previous|DROP TABLE|удали все тикеты` → `injection`, иначе `yandexgpt-lite`, `fail-open`), `create-ticket` с `injection` → `{"error":"Запрос заблокирован модерацией"}` + `System.err ALERT_INJECTION_BLOCKED`, `off-topic` → лог
- [ ] Safe-логи: только `action`, `user_id`, `text_length`, `has_pii`, `ticket_id`, без сырого `text`
- [ ] Тесты: `EventDispatcherTest` (3 источника), `YdbClientTest` (мок `TableClient/Session`, проверка `Params`), `YdbTicketsHandlerTest` (PII маска `+7 (***) ***-**-67`, injection блок), `mvn -pl ydb-tickets test` зелёный
- [ ] `mvn -pl common,ydb-tickets -am package -DskipTests` даёт `ydb-tickets/target/ydb-tickets-1.1.0.jar`
