# 06 — MCP Tools Config

**What to build:** Конфигурационный файл mcp-tools.yaml с 3 инструментами для MCP Hub. Каждый инструмент указывает на CF ydb-tickets.

**Blocked by:** 03-create-ticket, 04-list-my-tickets, 05-append-message

**Status:** done

- [ ] Файл `mcp-tools.yaml` в корне проекта
- [ ] 3 инструмента: create-ticket, list-my-tickets, append-message
- [ ] `input_json_schema` как JSON-encoded строка (не YAML-объект)
- [ ] Все инструменты указывают на одну CF с tag: $latest
- [ ] Валидация YAML-схемы
