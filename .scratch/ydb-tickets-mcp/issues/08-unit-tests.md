# 08 — Unit Tests

**What to build:** Полный набор юнит-тестов для всех компонентов: YdbClient, EventDispatcher, Action handlers, YdbTicketsHandler.

**Blocked by:** 07-main-handler

**Status:** done

- [ ] Тесты YdbClient: executeQuery, executeInTransaction, prepared statements
- [ ] Тесты EventDispatcher: direct invoke, API Gateway, MCP Hub
- [ ] Тесты createTicket: успешное создание, ошибка YDB
- [ ] Тесты listMyTickets: есть тикеты, нет тикетов
- [ ] Тесты appendMessage: успешное добавление, невалидный ticket_id
- [ ] Тесты YdbTicketsHandler: интеграция всех компонентов
- [ ] Все тесты проходят: `mvn test`
