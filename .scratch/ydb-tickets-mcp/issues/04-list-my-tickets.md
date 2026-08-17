# 04 — list-my-tickets

**What to build:** Инструмент получения списка тикетов пользователя по user_id. Использует вторичный индекс tickets_by_user.

**Blocked by:** 01-ydb-client, 02-event-dispatcher

**Status:** done

- [ ] Метод `listMyTickets(String userId)` в `YdbClient`
- [ ] SELECT с использованием индекса `tickets_by_user`
- [ ] Контракт ответа: `[{"id": "...", "status": "...", "category": "...", "text": "...", "created_at": "..."}]`
- [ ] Пустой список, если тикетов нет
- [ ] Юнит-тесты: есть тикеты, нет тикетов, ошибка YDB
