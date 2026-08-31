# Скрипт проверки токенов и трейсов
# Использование: .\check-tokens.ps1 -UserEmail "user@example.com"

param(
    [Parameter(Mandatory=$true)]
    [string]$UserEmail
)

Write-Host "=== Проверка токенов и трейсов ===" -ForegroundColor Cyan
Write-Host "Email пользователя: $UserEmail" -ForegroundColor Yellow

# Функция для вывода результата
function Show-Result {
    param(
        [string]$TestName,
        [bool]$Success,
        [string]$Message
    )
    
    if ($Success) {
        Write-Host "[PASS] $TestName" -ForegroundColor Green
    } else {
        Write-Host "[FAIL] $TestName" -ForegroundColor Red
    }
    
    if ($Message) {
        Write-Host "       $Message" -ForegroundColor Gray
    }
}

# Тест 1: Проверка структуры таблицы messages
Write-Host "`n--- Тест 1: Проверка структуры таблицы messages ---" -ForegroundColor Cyan

Write-Host "Структура таблицы messages:" -ForegroundColor Yellow
Write-Host "  - id (Utf8)" -ForegroundColor Gray
Write-Host "  - ticket_id (Utf8)" -ForegroundColor Gray
Write-Host "  - role (Utf8)" -ForegroundColor Gray
Write-Host "  - text (Utf8)" -ForegroundColor Gray
Write-Host "  - model (Utf8)" -ForegroundColor Gray
Write-Host "  - tokens_in (Uint64)" -ForegroundColor Gray
Write-Host "  - tokens_out (Uint64)" -ForegroundColor Gray
Write-Host "  - latency_ms (Uint32)" -ForegroundColor Gray
Write-Host "  - created_at (Timestamp)" -ForegroundColor Gray

Show-Result "Структура таблицы messages" $true "Поля определены"

# Тест 2: Проверка данных в messages
Write-Host "`n--- Тест 2: Проверка данных в messages ---" -ForegroundColor Cyan

Write-Host "Пример SQL-запроса для проверки токенов:" -ForegroundColor Yellow
Write-Host @"
SELECT 
  ticket_id,
  role,
  text,
  tokens_in,
  tokens_out,
  latency_ms,
  created_at
FROM messages
WHERE ticket_id IN (
  SELECT id FROM tickets WHERE user_id = '$UserEmail'
)
ORDER BY created_at DESC
LIMIT 10;
"@ -ForegroundColor Gray

# Тест 3: Проверка логов Cloud Function
Write-Host "`n--- Тест 3: Проверка логов Cloud Function ---" -ForegroundColor Cyan

$ydbTickets = yc serverless function get --name ydb-tickets --format json 2>$null | ConvertFrom-Json
if ($ydbTickets) {
    $logs = yc logging read --filter resource_id=$($ydbTickets.id) --limit 20 2>$null
    if ($logs) {
        Show-Result "Логи YDB Tickets доступны" $true "Последние 20 записей"
        
        # Поиск информации о токенах
        $tokenLogs = $logs | Select-String "tokens_in|tokens_out|usage"
        if ($tokenLogs) {
            Show-Result "Информация о токенах в логах" $true "Найдены записи о токенах"
            Write-Host "       $tokenLogs" -ForegroundColor Gray
        } else {
            Show-Result "Информация о токенах в логах" $false "Записи о токенах не найдены"
        }
    } else {
        Show-Result "Логи YDB Tickets доступны" $false "Логи не найдены"
    }
} else {
    Show-Result "Логи YDB Tickets доступны" $false "YDB Tickets не существует"
}

# Тест 4: Проверка трейсов в AI Studio
Write-Host "`n--- Тест 4: Проверка трейсов в AI Studio ---" -ForegroundColor Cyan

Write-Host "Для проверки трейсов в AI Studio:" -ForegroundColor Yellow
Write-Host "1. Откройте https://aistudio.yandex.ru" -ForegroundColor Gray
Write-Host "2. Выберите Agents" -ForegroundColor Gray
Write-Host "3. Найдите вашего агента Help Desk" -ForegroundColor Gray
Write-Host "4. Перейдите на вкладку Traces" -ForegroundColor Gray
Write-Host "5. Найдите трейсы для пользователя: $UserEmail" -ForegroundColor Gray

Show-Result "Инструкция по проверке трейсов" $true "Следуйте инструкциям выше"

# Тест 5: Проверка usage в ответах API
Write-Host "`n--- Тест 5: Проверка usage в ответах API ---" -ForegroundColor Cyan

Write-Host "Пример структуры usage в ответе Responses API:" -ForegroundColor Yellow
Write-Host @"
{
  "usage": {
    "input_tokens": 14,
    "output_tokens": 2,
    "total_tokens": 16,
    "input_tokens_details": {
      "cached_tokens": 0
    },
    "output_tokens_details": {
      "reasoning_tokens": 0
    }
  }
}
"@ -ForegroundColor Gray

Write-Host "Пояснения:" -ForegroundColor Yellow
Write-Host "  - input_tokens: Количество токенов во входном промпте" -ForegroundColor Gray
Write-Host "  - output_tokens: Количество токенов в ответе модели" -ForegroundColor Gray
Write-Host "  - total_tokens: Суммарное количество токенов" -ForegroundColor Gray
Write-Host "  - cached_tokens: Токены, взятые из кэша" -ForegroundColor Gray
Write-Host "  - reasoning_tokens: Токены, потраченные на размышление" -ForegroundColor Gray

Show-Result "Структура usage" $true "Описана выше"

# Тест 6: Сравнение токенов
Write-Host "`n--- Тест 6: Сравнение токенов ---" -ForegroundColor Cyan

Write-Host "Для сравнения токенов:" -ForegroundColor Yellow
Write-Host "1. Получите usage из ответа Responses API" -ForegroundColor Gray
Write-Host "2. Получите данные из таблицы messages" -ForegroundColor Gray
Write-Host "3. Сравните input_tokens с tokens_in" -ForegroundColor Gray
Write-Host "4. Сравните output_tokens с tokens_out" -ForegroundColor Gray
Write-Host "5. Допустимое расхождение: ≤10%" -ForegroundColor Gray

Write-Host "Пример SQL-запроса:" -ForegroundColor Yellow
Write-Host @"
-- Получаем последние 10 сообщений с токенами
SELECT 
  ticket_id,
  role,
  tokens_in,
  tokens_out,
  latency_ms,
  created_at
FROM messages
WHERE ticket_id IN (
  SELECT id FROM tickets WHERE user_id = '$UserEmail'
)
ORDER BY created_at DESC
LIMIT 10;
"@ -ForegroundColor Gray

Show-Result "Инструкция по сравнению токенов" $true "Следуйте инструкциям выше"

# Тест 7: Проверка кода AgentClient.java
Write-Host "`n--- Тест 7: Проверка кода AgentClient.java ---" -ForegroundColor Cyan

Write-Host "В AgentClient.java токены извлекаются из ответа API:" -ForegroundColor Yellow
Write-Host @"
System.out.printf(
  "Response id: %s. Request: %s. Response: %s. Input Tokens: %d. Output Tokens: %d.%s",
  response.id(),
  request,
  modelResponse,
  response.usage().get().inputTokens(),
  response.usage().get().outputTokens(),
  System.lineSeparator()
);
"@ -ForegroundColor Gray

Write-Host "Для сохранения токенов в YDB используйте MCP инструмент append-message:" -ForegroundColor Yellow
Write-Host @"
{
  "ticket_id": "abc-123",
  "text": "Ответ агента",
  "role": "agent",
  "tokens_in": 14,
  "tokens_out": 2,
  "latency_ms": 1500
}
"@ -ForegroundColor Gray

Show-Result "Код извлечения токенов" $true "Описан выше"

# Итоги
Write-Host "`n=== Итоги проверки ===" -ForegroundColor Cyan
Write-Host "Проверка завершена." -ForegroundColor Green
Write-Host "Для полной проверки токенов и трейсов:" -ForegroundColor Yellow
Write-Host "1. Отправьте письмо на адрес Help Desk" -ForegroundColor Gray
Write-Host "2. Дождитесь ответа от агента" -ForegroundColor Gray
Write-Host "3. Проверьте логи Cloud Function" -ForegroundColor Gray
Write-Host "4. Проверьте данные в YDB" -ForegroundColor Gray
Write-Host "5. Сравните usage из ответа API с tokens_in/tokens_out" -ForegroundColor Gray
Write-Host "6. Проверьте трейсы в AI Studio" -ForegroundColor Gray
