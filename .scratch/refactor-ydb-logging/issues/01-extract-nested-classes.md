# Ticket 1: Extract nested classes to separate files

## Status: pending
## Blocked by: nothing

## Goal

Вынести все вложенные классы пакета `ru.anseranser.ydb` в отдельные файлы. После этого тикета код компилируется и все тесты проходят — это чистый структурный рефакторинг без изменения логики.

## Files to modify

| File | Action |
|------|--------|
| `helpdesk/src/main/java/ru/anseranser/ydb/Action.java` | **CREATE** — перенести enum из `EventDispatcher.java:12-16` |
| `helpdesk/src/main/java/ru/anseranser/ydb/DispatchResult.java` | **CREATE** — перенести record из `EventDispatcher.java:19` |
| `helpdesk/src/main/java/ru/anseranser/ydb/StaticTokenProvider.java` | **CREATE** — перенести class из `YdbClient.java:260-271` |
| `helpdesk/src/main/java/ru/anseranser/ydb/EventDispatcher.java` | **EDIT** — удалить вложенные типы, добавить import |
| `helpdesk/src/main/java/ru/anseranser/ydb/YdbClient.java` | **EDIT** — удалить вложенный класс, добавить import |

## Steps

1. Создать `Action.java` с enum `Action` ( package-private доступ — используется только в пределах пакета).
2. Создать `DispatchResult.java` с record `DispatchResult(Action action, String body)`.
3. Создать `StaticTokenProvider.java` с классом `StaticTokenProvider implements AuthProvider`.
4. В `EventDispatcher.java`: удалить строки 12-19 (enum + record), добавить `import` для `Action` и `DispatchResult`.
5. В `YdbClient.java`: удалить строки 260-271 (вложенный класс `StaticTokenProvider`), добавить `import` для `StaticTokenProvider`.
6. Запустить `mvn compile test -pl helpdesk` — убедиться что всё компилируется и тесты зелёные.

## Acceptance criteria

- [ ] `Action.java`, `DispatchResult.java`, `StaticTokenProvider.java` существуют и компилируются.
- [ ] `EventDispatcher.java` не содержит вложенных типов.
- [ ] `YdbClient.java` не содержит вложенных типов.
- [ ] `mvn compile test` — все тесты зелёные.
