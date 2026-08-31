# Пример Python-скрипта для загрузки через SDK

## Предварительные требования

```bash
pip install yandex-ai-studio-sdk
```

## Переменные окружения

```bash
export YC_FOLDER_ID="ваш_folder_id"
export YC_IAM_TOKEN="ваш_iam_token"
```

## Скрипт загрузки

```python
import os
import glob
from yandex_ai_studio_sdk import AIStudio

# Инициализация клиента
ai = AIStudio()  # подхватит YC_FOLDER_ID и YC_IAM_TOKEN из env

# Загрузка markdown-файлов
docs_dir = "docs/"
md_files = glob.glob(os.path.join(docs_dir, "*.md"))

print(f"Найдено {len(md_files)} markdown-файлов:")
for f in md_files:
    print(f"  - {f}")

# Создание векторного хранилища
# (API может отличаться — проверьте актуальную документацию)
# vector_store = ai.vector_stores.create(
#     name="help-desk-kb",
#     files=md_files
# )
# print(f"Search Index ID: {vector_store.id}")
```

## Просмотр существующих индексов

```python
from yandex_ai_studio_sdk import AIStudio

ai = AIStudio()

# Список всех search indexes
for idx in ai.search_indexes.list():
    print(f"ID: {idx.id}, Name: {idx.name}")
```

## Подключение индекса к агенту

```python
import json
import requests

# Конфигурация
SEARCH_INDEX_ID = "fvt..."  # ваш search_index_id
API_KEY = "ваш_api_key"

# Запрос к Responses API
payload = {
    "model": "yandexgpt",
    "messages": [
        {
            "role": "system",
            "content": open("docs/agent-instructions.md").read()
        },
        {
            "role": "user",
            "content": "Как оформить отпуск?"
        }
    ],
    "tools": [
        {
            "type": "file_search",
            "vector_store_ids": [SEARCH_INDEX_ID]
        }
    ]
}

headers = {
    "Authorization": f"Api-Key {API_KEY}",
    "Content-Type": "application/json"
}

response = requests.post(
    "https://llm.api.cloud.yandex.net/v1/responses",
    headers=headers,
    json=payload
)

print(json.dumps(response.json(), indent=2, ensure_ascii=False))
```
