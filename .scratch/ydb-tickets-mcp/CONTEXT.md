# Glossary: ydb-tickets-mcp

## Термины

- **Cloud Function** — серверная функция Yandex Cloud, работающая по событию (JSON → JSON)
- **YDB** — Yandex Database, серверная NoSQL база данных с поддержкой SQL-подобного языка (YQL)
- **MCP Hub** — Managed Connection Protocol Hub в Yandex AI Studio для подключения инструментов к AI-агенту
- **MCP Tool** — инструмент, доступный AI-агенту через MCP Hub (create-ticket, list-my-tickets, append-message)
- **Prepared Statement** — предкомпилированный YQL-запрос для YDB, параметры передаются отдельно с префиксом `$`
- **Ticket** — обращение пользователя в системе поддержки (тикет/заявка)
- **Message** — сообщение в истории диалога по тикету (реплика пользователя или агента)
- **Direct Invoke** — прямой вызов Cloud Function через `yc serverless function invoke`
- **API Gateway** — HTTP-шлюз для вызова Cloud Function через REST API
- **Dispatcher** — метод диспетчеризации, определяющий тип входного события и вызывающий соответствующий обработчик
