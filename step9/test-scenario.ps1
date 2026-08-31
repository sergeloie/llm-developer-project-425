# Скрипт проверки сквозного сценария
# Использование: .\test-scenario.ps1 -UserEmail "user@example.com"

param(
    [Parameter(Mandatory=$true)]
    [string]$UserEmail
)

Write-Host "=== Проверка сквозного сценария ===" -ForegroundColor Cyan
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

# Тест 1: Проверка Cloud Functions
Write-Host "`n--- Тест 1: Проверка Cloud Functions ---" -ForegroundColor Cyan

$emailPoller = yc serverless function get --name email-poller --format json 2>$null | ConvertFrom-Json
if ($emailPoller) {
    Show-Result "Email Poller существует" $true "ID: $($emailPoller.id)"
} else {
    Show-Result "Email Poller существует" $false "Функция email-poller не найдена"
}

$ydbTickets = yc serverless function get --name ydb-tickets --format json 2>$null | ConvertFrom-Json
if ($ydbTickets) {
    Show-Result "YDB Tickets существует" $true "ID: $($ydbTickets.id)"
} else {
    Show-Result "YDB Tickets существует" $false "Функция ydb-tickets не найдена"
}

# Тест 2: Проверка MCP Gateway
Write-Host "`n--- Тест 2: Проверка MCP Gateway ---" -ForegroundColor Cyan

$mcpGateway = yc serverless mcp-gateway list --format json 2>$null | ConvertFrom-Json
if ($mcpGateway -and $mcpGateway.items.Count -gt 0) {
    Show-Result "MCP Gateway существует" $true "Количество: $($mcpGateway.items.Count)"
} else {
    Show-Result "MCP Gateway существует" $false "MCP Gateway не найден"
}

# Тест 3: Проверка тикетов пользователя
Write-Host "`n--- Тест 3: Проверка тикетов пользователя ---" -ForegroundColor Cyan

$ticketsInvoke = yc serverless function invoke ydb-tickets --data "{\"action\":\"list-my-tickets\",\"user_id\":\"$UserEmail\"}" 2>$null
if ($ticketsInvoke) {
    Show-Result "Invoke list-my-tickets" $true "Ответ получен"
    Write-Host "       Ответ: $ticketsInvoke" -ForegroundColor Gray
} else {
    Show-Result "Invoke list-my-tickets" $false "Не удалось выполнить invoke"
}

# Тест 4: Проверка логов Email Poller
Write-Host "`n--- Тест 4: Проверка логов Email Poller ---" -ForegroundColor Cyan

if ($emailPoller) {
    $logs = yc logging read --filter resource_id=$($emailPoller.id) --limit 10 2>$null
    if ($logs) {
        Show-Result "Логи Email Poller доступны" $true "Последние 10 записей"
        Write-Host "       $logs" -ForegroundColor Gray
    } else {
        Show-Result "Логи Email Poller доступны" $false "Логи не найдены"
    }
} else {
    Show-Result "Логи Email Poller доступны" $false "Email Poller не существует"
}

# Тест 5: Проверка логов YDB Tickets
Write-Host "`n--- Тест 5: Проверка логов YDB Tickets ---" -ForegroundColor Cyan

if ($ydbTickets) {
    $logs = yc logging read --filter resource_id=$($ydbTickets.id) --limit 10 2>$null
    if ($logs) {
        Show-Result "Логи YDB Tickets доступны" $true "Последние 10 записей"
        Write-Host "       $logs" -ForegroundColor Gray
    } else {
        Show-Result "Логи YDB Tickets доступны" $false "Логи не найдены"
    }
} else {
    Show-Result "Логи YDB Tickets доступны" $false "YDB Tickets не существует"
}

# Тест 6: Проверка Workflow
Write-Host "`n--- Тест 6: Проверка Workflow ---" -ForegroundColor Cyan

$workflows = yc serverless workflow list --format json 2>$null | ConvertFrom-Json
if ($workflows -and $workflows.items.Count -gt 0) {
    Show-Result "Workflow существует" $true "Количество: $($workflows.items.Count)"
} else {
    Show-Result "Workflow существует" $false "Workflow не найден"
}

# Тест 7: Проверка YDB
Write-Host "`n--- Тест 7: Проверка YDB ---" -ForegroundColor Cyan

try {
    $ydbEndpoint = yc ydb database get help-desk-db --format json 2>$null | ConvertFrom-Json
    if ($ydbEndpoint) {
        Show-Result "YDB база данных существует" $true "Endpoint: $($ydbEndpoint.endpoint)"
    } else {
        Show-Result "YDB база данных существует" $false "База данных help-desk-db не найдена"
    }
} catch {
    Show-Result "YDB база данных существует" $false "Ошибка при проверке: $_"
}

# Итоги
Write-Host "`n=== Итоги проверки ===" -ForegroundColor Cyan
Write-Host "Проверка завершена." -ForegroundColor Green
Write-Host "Для полной проверки сквозного сценария:" -ForegroundColor Yellow
Write-Host "1. Отправьте письмо на адрес Help Desk" -ForegroundColor Gray
Write-Host "2. Дождитесь ответа от агента (~60 секунд)" -ForegroundColor Gray
Write-Host "3. Ответьте на письмо с просьбой создать тикет" -ForegroundColor Gray
Write-Host "4. Проверьте тикет в YDB" -ForegroundColor Gray
