# 01 — YDB Client

**What to build:** Обертка над YDB SDK для подключения и выполнения prepared statements. Класс `YdbClient` с методами для транзакций и запросов.

**Blocked by:** None — can start immediately.

**Status:** done

- [ ] Класс `YdbClient` в пакете `ru.anseranser.ydb`
- [ ] Конструктор принимает endpoint, database, token
- [ ] Метод `executeQuery(String yql, Map<String, Object> params)` возвращает результат
- [ ] Метод `executeInTransaction(List<QueryParams> queries)` для атомарных операций
- [ ] Prepared statements через `session.prepare(yql)`
- [ ] Юнит-тесты с моками YDB Session/Transaction
