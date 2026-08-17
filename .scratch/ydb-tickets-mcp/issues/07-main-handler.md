# 07 — Main Handler

**What to build:** Основной handler-класс `YdbTicketsHandler`, реализующий `YcFunction<String, String>`. Координирует диспетчеризацию, YDB-операции и формирование ответов.

**Blocked by:** 01-ydb-client, 02-event-dispatcher, 03-create-ticket, 04-list-my-tickets, 05-append-message

**Status:** done

- [ ] Класс `YdbTicketsHandler` в пакете `ru.anseranser.ydb`
- [ ] Реализует `YcFunction<String, String>`
- [ ] Конструктор без аргументов (для Cloud Functions)
- [ ] Инициализация YdbClient из env vars (YDB_ENDPOINT, YDB_DATABASE, YDB_TOKEN)
- [ ] Метод `handle(String s, Context context)` вызывает `dispatch`
- [ ] Сериализация ответов через jsoniter
- [ ] Юнит-тесты: мок YdbClient, тест всех 3 action
