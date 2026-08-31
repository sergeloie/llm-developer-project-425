# Скрипт загрузки документов в Yandex AI Studio Vector Store

## Предварительные требования

1. Установлен `yandex-ai-studio` CLI (pip install yandex-ai-studio).
2. Настроен YC CLI (yc).
3. Установлены переменные окружения:
   - `YC_FOLDER_ID` — ID папки Яндекс Облака.
   - `YC_IAM_TOKEN` — IAM-токен для авторизации.

## Шаг 1: Активация виртуального окружения

```bash
source .venv/bin/activate
```

## Шаг 2: Загрузка документов в Vector Store

```bash
yandex-ai-studio vector-stores local docs/*.md --name "help-desk-kb"
```

Команда загрузит все markdown-файлы из папки `docs/` и создаст search index.

**Результат:** команда вернёт `search_index_id` (строка вида `fvt...`) — **сохраните его**.

## Шаг 3: Проверка созданного индекса

```bash
# Просмотреть список всех индексов
yandex-ai-studio vector-stores list

# Просмотреть детали конкретного индекса
yandex-ai-studio vector-stores get <search_index_id>
```

## Шаг 4: Использование индекса в Responses API

Добавьте `file_search` tool в запрос к агенту:

```json
{
  "model": "yandexgpt",
  "messages": [
    {
      "role": "system",
      "instructions": "<содержимое docs/agent-instructions.md>"
    },
    {
      "role": "user",
      "content": "Как оформить отпуск?"
    }
  ],
  "tools": [
    {
      "type": "file_search",
      "vector_store_ids": ["<search_index_id>"]
    }
  ]
}
```

## Шаг 5: Проверка работы

### Тест 1: Вопрос из базы знаний

Отправьте вопрос: «Как оформить командировку?»

Ожидаемый результат:
- В `output[]` видно `type: "file_search_call"` с результатами поиска.
- Агент отвечает с цитатой и ссылкой на документ.

### Тест 2: Вопрос вне базы знаний

Отправьте вопрос: «Как оформить(rename) доменное имя?»

Ожидаемый результат:
- Агент честно говорит «не знаю» и предлагает создать тикет.

## Полезные команды CLI

```bash
# Помощь по команде
yandex-ai-studio vector-stores local --help

# Создание индекса с дополнительными параметрами
yandex-ai-studio vector-stores local docs/*.md \
  --name "help-desk-kb" \
  --max-file-size 10485760 \
  --expires-after-days 365 \
  --poll-timeout 300

# Удаление индекса
yandex-ai-studio vector-stores delete <search_index_id>
```

## Ограничения Yandex Responses API

- Только один `vector_store_id` за раз.
- Только один `file_search` tool за раз.
- Если у вас несколько корпусов — объедините их в один индекс при создании.
