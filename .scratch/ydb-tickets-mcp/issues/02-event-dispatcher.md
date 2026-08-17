# 02 — Event Dispatcher

**What to build:** Метод диспетчеризации, определяющий тип входного события (direct invoke, API Gateway, MCP Hub) и вызывающий соответствующий обработчик.

**Blocked by:** None — can start immediately.

**Status:** done

- [ ] Enum `Action`: `CREATE_TICKET`, `LIST_MY_TICKETS`, `APPEND_MESSAGE`
- [ ] Метод `dispatch(String event)` определяет тип по ключам:
  - Если есть `httpMethod` → API Gateway (извлекает body)
  - Если есть `action` → direct invoke
  - Иначе → MCP Hub (диспетчеризация по набору ключей)
- [ ] Юнит-тесты для всех 3 типов входов
- [ ] Тест диспетчеризации по набору ключей (user_id/ticket_id/text)
