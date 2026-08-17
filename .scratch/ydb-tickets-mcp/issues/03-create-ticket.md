# 03 — create-ticket

**What to build:** Инструмент создания тикета и первой записи в истории диалогов. Принимает user_id, category, text. Возвращает ticket_id и created_at.

**Blocked by:** 01-ydb-client, 02-event-dispatcher

**Status:** done

- [ ] Метод `createTicket(String userId, String category, String text)` в `YdbClient`
- [ ] Транзакция: INSERT в tickets + INSERT в messages
- [ ] Генерация UUID для ticket_id и message_id
- [ ] Timestamp: `Instant.now()` → YDB Timestamp
- [ ] Контракт ответа: `{"ticket_id": "...", "created_at": "..."}`
- [ ] Юнит-тесты: успешное создание, ошибка YDB, невалидные данные
