# Ticket 4: Add logging to YdbTicketsHandler

## Status: pending
## Blocked by: 01-extract-nested-classes

## Goal

Добавить `System.out.println` в методы `YdbTicketsHandler`: логирование начала/конца handle + логирование инициализации клиента.

## Files to modify

| File | Action |
|------|--------|
| `helpdesk/src/main/java/ru/anseranser/ydb/YdbTicketsHandler.java` | **EDIT** |

## Steps

1. Добавить логирование в `handle()`: START (первые 100 символов event), action resolved, dispatch result, END/ERROR.
2. Добавить логирование в `getOrCreateYdbClient()`: START (только при lazy init), initialized, already cached.
3. Формат: `[YdbTicketsHandler] methodName: START ...`
4. В catch-блоке: `e.getMessage()` + cause chain.
5. Запустить `mvn compile test -pl helpdesk`.

## Logging details

### handle
```
[YdbTicketsHandler] handle: START event=<первые 100 символов>
[YdbTicketsHandler] handle: Dispatch resolved action=<action>
[YdbTicketsHandler] handle: FINISHED action=<action>
[YdbTicketsHandler] handle: ERROR <message> | caused by: <cause>
```

### getOrCreateYdbClient (только при инициализации)
```
[YdbTicketsHandler] getOrCreateYdbClient: Initializing YDB client...
[YdbTicketsHandler] getOrCreateYdbClient: YDB client initialized successfully
[YdbTicketsHandler] getOrCreateYdbClient: ERROR YDB not configured: <missing vars>
```

## Acceptance criteria

- [ ] `handle()` содержит логирование START, action resolved, FINISHED/ERROR.
- [ ] `getOrCreateYdbClient()` логирует инициализацию (только при первом вызове).
- [ ] catch-блок в `handle()` содержит `e.getMessage()` + cause chain.
- [ ] `mvn compile test` — все тесты зелёные.
