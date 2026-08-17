# Ticket 2: Add logging to EventDispatcher

## Status: pending
## Blocked by: 01-extract-nested-classes

## Goal

Добавить `System.out.println` во все методы `EventDispatcher`: логирование начала/конца каждой операции + подробный вывод ошибок в catch-блоках.

## Files to modify

| File | Action |
|------|--------|
| `helpdesk/src/main/java/ru/anseranser/ydb/EventDispatcher.java` | **EDIT** |

## Steps

1. Добавить логирование в `dispatchAndExtract()`: START (с первыми 100 символами event), resolved action, FINISHED.
2. Добавить логирование в `parseEvent()`: START, FINISHED, ERROR.
3. Добавить логирование в `extractBodyFromJson()`: START, FINISHED.
4. Добавить логирование в `dispatchFromDirectInvoke()`: START, FINISHED, ERROR.
5. Добавить логирование в `dispatchFromMcpHub()`: START, FINISHED, ERROR.
6. Формат: `[EventDispatcher] methodName: START ...`
7. В catch-блоках: `e.getMessage()` + cause chain через `e.getCause()`.
8. Запустить `mvn compile test -pl helpdesk`.

## Logging details

### dispatchAndExtract
```
[EventDispatcher] dispatchAndExtract: START event=<первые 100 символов>
[EventDispatcher] dispatchAndExtract: API Gateway detected, unwrapping body
[EventDispatcher] dispatchAndExtract: Direct invoke detected, action=<action>
[EventDispatcher] dispatchAndExtract: MCP Hub detected, resolving by keys
[EventDispatcher] dispatchAndExtract: FINISHED action=<action>
```

### parseEvent
```
[EventDispatcher] parseEvent: START
[EventDispatcher] parseEvent: FINISHED
[EventDispatcher] parseEvent: ERROR Invalid JSON: <message> | caused by: <cause>
```

### extractBodyFromJson
```
[EventDispatcher] extractBodyFromJson: START
[EventDispatcher] extractBodyFromJson: FINISHED bodyLength=<length>
```

### dispatchFromDirectInvoke
```
[EventDispatcher] dispatchFromDirectInvoke: START actionName=<name>
[EventDispatcher] dispatchFromDirectInvoke: FINISHED action=<action>
[EventDispatcher] dispatchFromDirectInvoke: ERROR Unknown action: <name>
```

### dispatchFromMcpHub
```
[EventDispatcher] dispatchFromMcpHub: START keys=<keys>
[EventDispatcher] dispatchFromMcpHub: FINISHED action=<action>
[EventDispatcher] dispatchFromMcpHub: ERROR Cannot determine action from keys: <keys>
```

## Acceptance criteria

- [ ] Каждый метод `EventDispatcher` содержит логирование START и FINISHED/ERROR.
- [ ] catch-блоки содержат `e.getMessage()` + cause chain.
- [ ] `mvn compile test` — все тесты зелёные.
