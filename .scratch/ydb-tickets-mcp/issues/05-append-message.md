# 05 — append-message

**What to build:** Инструмент добавления сообщения в историю диалога. Принимает ticket_id, role, text, model, tokens_in, tokens_out, latency_ms.

**Blocked by:** 01-ydb-client, 02-event-dispatcher

**Status:** done

- [ ] Метод `appendMessage(String ticketId, String role, String text, String model, long tokensIn, long tokensOut, int latencyMs)` в `YdbClient`
- [ ] INSERT в messages с UUID и timestamp
- [ ] Контракт ответа: `{"message_id": "...", "ok": true}`
- [ ] Юнит-тесты: успешное добавление, невалидный ticket_id, ошибка YDB
