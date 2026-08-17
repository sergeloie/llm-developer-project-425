# Ticket 3: Add logging to YdbClient

## Status: pending
## Blocked by: 01-extract-nested-classes

## Goal

Добавить `System.out.println` во все методы `YdbClient`: логирование начала/конца каждой операции + подробный вывод ошибок в catch-блоках.

## Files to modify

| File | Action |
|------|--------|
| `helpdesk/src/main/java/ru/anseranser/ydb/YdbClient.java` | **EDIT** |

## Steps

1. Добавить логирование в `createTicket()`: START (userId, category, first 100 chars of text), YDB executed, FINISHED (ticketId), ERROR.
2. Добавить логирование в `listMyTickets()`: START (userId), YDB queried, FINISHED (ticketCount), ERROR.
3. Добавить логирование в `appendMessage()`: START (ticketId, role), YDB executed, FINISHED (messageId), ERROR.
4. Добавить логирование в `executeQuery()`: START (yql preview), FINISHED, ERROR.
5. Добавить логирование в `executeInTransaction()`: START (queryCount), committed, FINISHED, ERROR.
6. Добавить логирование в `convertParams()`: START (paramCount), FINISHED.
7. Формат: `[YdbClient] methodName: START ...`
8. В catch-блоках: `e.getMessage()` + cause chain.
9. Запустить `mvn compile test -pl helpdesk`.

## Logging details

### createTicket
```
[YdbClient] createTicket: START userId=<userId> category=<category> textPreview=<first 100 chars>
[YdbClient] createTicket: Validation passed
[YdbClient] createTicket: YDB transaction executed
[YdbClient] createTicket: FINISHED ticketId=<ticketId>
[YdbClient] createTicket: ERROR <message> | caused by: <cause>
```

### listMyTickets
```
[YdbClient] listMyTickets: START userId=<userId>
[YdbClient] listMyTickets: YDB query executed, resultCount=<count>
[YdbClient] listMyTickets: FINISHED
[YdbClient] listMyTickets: ERROR <message> | caused by: <cause>
```

### appendMessage
```
[YdbClient] appendMessage: START ticketId=<ticketId> role=<role>
[YdbClient] appendMessage: Validation passed
[YdbClient] appendMessage: YDB transaction executed
[YdbClient] appendMessage: FINISHED messageId=<messageId>
[YdbClient] appendMessage: ERROR <message> | caused by: <cause>
```

### executeQuery
```
[YdbClient] executeQuery: START yql=<первые 80 символов YQL>
[YdbClient] executeQuery: FINISHED
[YdbClient] executeQuery: ERROR <message> | caused by: <cause>
```

### executeInTransaction
```
[YdbClient] executeInTransaction: START queryCount=<count>
[YdbClient] executeInTransaction: Committed successfully
[YdbClient] executeInTransaction: FINISHED
[YdbClient] executeInTransaction: ERROR <message> | caused by: <cause>
```

### convertParams
```
[YdbClient] convertParams: START paramCount=<count>
[YdbClient] convertParams: FINISHED
```

## Acceptance criteria

- [ ] Каждый метод `YdbClient` содержит логирование START и FINISHED/ERROR.
- [ ] catch-блоки содержат `e.getMessage()` + cause chain.
- [ ] `createTicket` логирует первые 100 символов text, а не весь текст.
- [ ] `mvn compile test` — все тесты зелёные.
