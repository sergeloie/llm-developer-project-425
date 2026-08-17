# Ticket 5: Final verification

## Status: pending
## Blocked by: 02-logging-event-dispatcher, 03-logging-ydb-client, 04-logging-ydb-tickets-handler

## Goal

Полная проверка: компиляция + все тесты + ревью структуры файлов.

## Steps

1. Запустить `mvn clean compile test -pl helpdesk` — полный прогон.
2. Проверить что `EventDispatcher.java` не содержит вложенных типов.
3. Проверить что `YdbClient.java` не содержит вложенных типов.
4. Проверить что `Action.java`, `DispatchResult.java`, `StaticTokenProvider.java` существуют.
5. Проверить что каждый публичный метод содержит как минимум одну строку `System.out.println`.

## Acceptance criteria

- [ ] `mvn clean compile test` — 0 ошибок, все тесты зелёные.
- [ ] Все 3 новых файла на месте.
- [ ] Ни одного вложенного типа в `EventDispatcher.java` и `YdbClient.java`.
- [ ] Логирование присутствует во всех публичных методах пакета `ydb`.
