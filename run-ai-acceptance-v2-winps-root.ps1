param(
    [string]$BaseUrl = "http://localhost:8084",
    [int]$LoadingRepeats = 2
)

$ErrorActionPreference = "Stop"
[Console]::InputEncoding = [System.Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$RagFile = Join-Path $Root "acceptance-rag-v2.json"
if (-not (Test-Path $RagFile)) {
    throw "RAG data file not found: $RagFile"
}

$Stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$ResultDir = Join-Path (Get-Location) "ai-acceptance-v2-$Stamp"
New-Item -ItemType Directory -Path $ResultDir -Force | Out-Null

$script:Results = @()
$script:TokenGroups = @{
    agro = 0
    efes = 0
    loading = 0
}

function Write-Utf8Json {
    param([string]$Path, $Object)
    $json = $Object | ConvertTo-Json -Depth 80 -Compress
    [System.IO.File]::WriteAllText($Path, $json, [System.Text.UTF8Encoding]::new($false))
}

function Add-Result {
    param(
        [string]$Id,
        [string]$Description,
        [bool]$Passed,
        [int]$HttpCode,
        [long]$ElapsedMs,
        [int]$Tokens,
        [string]$Message,
        [string]$ResponseFile
    )
    $script:Results += [pscustomobject]@{
        id = $Id
        description = $Description
        passed = $Passed
        http_code = $HttpCode
        elapsed_ms = $ElapsedMs
        tokens = $Tokens
        message = $Message
        response_file = $ResponseFile
    }
    $mark = if ($Passed) { "PASS" } else { "FAIL" }
    Write-Host "[$mark] $Id — $Description :: $Message"
}

function Invoke-JsonTest {
    param(
        [string]$Id,
        [string]$Description,
        [string]$Method,
        [string]$Path,
        [string]$BodyFile,
        [scriptblock]$Validate,
        [string]$TokenGroup = "",
        [int]$MaxSeconds = 240
    )

    $responseFile = Join-Path $ResultDir "$Id-response.json"
    $url = "$BaseUrl$Path"
    $args = @("-sS", "--max-time", "$MaxSeconds", "-X", $Method, $url, "-o", $responseFile, "-w", "%{http_code}")
    if ($BodyFile) {
        $args += @("-H", "Content-Type: application/json; charset=utf-8", "--data-binary", "@$BodyFile")
    }

    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $httpRaw = & curl.exe @args
    $curlExit = $LASTEXITCODE
    $sw.Stop()

    if ($curlExit -ne 0) {
        Add-Result $Id $Description $false 0 $sw.ElapsedMilliseconds 0 "curl exit code $curlExit" $responseFile
        return
    }

    $http = 0
    [void][int]::TryParse(($httpRaw | Out-String).Trim(), [ref]$http)
    $raw = if (Test-Path $responseFile) { Get-Content $responseFile -Raw -Encoding UTF8 } else { "" }
    $json = $null
    try { if ($raw) { $json = $raw | ConvertFrom-Json } } catch { $json = $null }

    $tokens = 0
    if ($json -and $json.token_usage -and $json.token_usage.total_tokens) {
        $tokens = [int]$json.token_usage.total_tokens
        if ($TokenGroup -and $script:TokenGroups.ContainsKey($TokenGroup)) {
            $script:TokenGroups[$TokenGroup] += $tokens
        }
    }

    try {
        $result = & $Validate $json $http $raw $sw.ElapsedMilliseconds
        Add-Result $Id $Description ([bool]$result.passed) $http $sw.ElapsedMilliseconds $tokens ([string]$result.message) $responseFile
    } catch {
        Add-Result $Id $Description $false $http $sw.ElapsedMilliseconds $tokens "validator error: $($_.Exception.Message)" $responseFile
    }
}

function New-SearchRequest {
    param([string]$Query, [hashtable]$Filters, [int]$Limit = 10, [double]$MinScore = 0.0)
    return @{
        query = $Query
        filters = $Filters
        limit = $Limit
        min_score = $MinScore
    }
}

function New-PaymentRequest {
    param(
        [string]$ClaimId,
        [string]$ClientId,
        [string]$ContractId,
        [string]$ContractNumber,
        [string]$ContractDate,
        [string]$CreditorName,
        [string]$CreditorInn,
        [string]$DebtorName,
        [string]$DebtorInn,
        [string]$ActNumber,
        [string]$ActDate,
        [string]$TtnNumber,
        [string]$InvoiceNumber,
        [string]$OrderNumber,
        [string]$Route,
        [string]$DueDate,
        [string]$ClaimDate,
        [decimal]$Debt,
        [decimal]$Penalty,
        [decimal]$Total,
        [int]$OverdueDays,
        [string]$RateText,
        [string]$Formula
    )
    return @{
        case_facts = @{
            claim_id = $ClaimId
            claim_type = "PAYMENT_DELAY"
            creditor = @{ name = $CreditorName; inn = $CreditorInn; legal_address = "Москва" }
            debtor = @{ name = $DebtorName; inn = $DebtorInn; legal_address = "Санкт-Петербург" }
            contract = @{ contract_number = $ContractNumber; contract_date = $ContractDate }
            shipment = @{
                order_number = $OrderNumber
                route = $Route
                act_number = $ActNumber
                act_date = $ActDate
                ttn_number = $TtnNumber
                invoice_number = $InvoiceNumber
            }
            payment = @{
                payment_due_date = $DueDate
                payment_status = "UNPAID"
                payment_confirmed_by_accountant = $true
            }
            claim_date = $ClaimDate
        }
        backend_calculation = @{
            principal_debt = $Debt
            penalty_type = "CONTRACT_PENALTY"
            penalty_rate_text = $RateText
            overdue_days = $OverdueDays
            penalty_amount = $Penalty
            total_amount = $Total
            currency = "RUB"
            formula_text = $Formula
        }
        contract_context = @()
        legal_context = @()
        template_context = $null
        similar_examples = @()
        rag_options = @{ enabled = $true; contract_id = $ContractId; client_id = $ClientId }
    }
}

function New-LoadingRequest {
    return @{
        case_facts = @{
            claim_id = "acc-loading-001"
            claim_type = "LOADING_FAILURE"
            creditor = @{ name = "ООО Маршалл Заказчик"; inn = "7700000001"; legal_address = "Москва" }
            debtor = @{ name = "ООО Тестовый Перевозчик"; inn = "7800000001"; legal_address = "Санкт-Петербург" }
            contract = @{ contract_number = "LOAD-25/2026"; contract_date = "05.03.2026" }
            shipment = @{
                order_number = "ORD-ACC-250"
                route = "Москва - Тверь"
                act_number = "ACT-ACC-250"
                act_date = "20.07.2026"
                ttn_number = $null
                invoice_number = $null
                loading_date = "20.07.2026"
                loading_address = "Москва, терминал №7"
                loading_time_window = "08:30-10:30"
                vehicle_requirements = "рефрижератор 10 т"
                carrier_name = "ООО Тестовый Перевозчик"
                failure_confirmed_by_dispatcher = $true
            }
            payment = $null
            claim_date = "21.07.2026"
        }
        backend_calculation = @{
            principal_debt = 0
            penalty_type = "CONTRACT_PENALTY"
            penalty_rate_text = "фиксированный договорный штраф за непредоставление транспортного средства"
            overdue_days = 0
            penalty_amount = 25000
            total_amount = 25000
            currency = "RUB"
            formula_text = "фиксированный штраф 25000 RUB"
        }
        contract_context = @()
        legal_context = @()
        template_context = $null
        similar_examples = @()
        rag_options = @{ enabled = $true; contract_id = "acc_contract_loading_2026"; client_id = "acc_client_marshall" }
    }
}

function Get-ClaimText($j) {
    if ($j -and $j.generated_claim) { return [string]$j.generated_claim.claim_text }
    return ""
}
function Get-DocumentText($j) {
    if ($j -and $j.generated_document) { return [string]$j.generated_document.document_text }
    return ""
}
function Get-UsedContractIds($j) {
    $items = @()
    if ($j.generated_claim) { $items = @($j.generated_claim.used_contract_clauses) }
    elseif ($j.generated_document) { $items = @($j.generated_document.used_contract_clauses) }
    return @($items | ForEach-Object { $_.chunk_id })
}
function Has-RequiredAttachment($j, [string]$Type) {
    $items = if ($j.generated_claim) { @($j.generated_claim.attachments) } else { @($j.generated_document.attachments) }
    return @($items | Where-Object { $_.document_type -eq $Type -and $_.required -eq $true }).Count -gt 0
}
function Has-DispatcherAttribution([string]$Text) {
    return $Text -match '(?iu)диспетчер\p{L}*\s+(?:ООО|ИП|перевозчик\p{L}*|заказчик\p{L}*|кредитор\p{L}*|должник\p{L}*|нашей|вашей|своей)'
}
function Has-ActIdentity([string]$Text, [string]$Number, [string]$Date) {
    if (-not $Text -or -not $Number -or -not $Date) { return $false }
    $flat = ($Text -replace '\s+', ' ')
    $numberIndex = $flat.IndexOf($Number, [System.StringComparison]::OrdinalIgnoreCase)
    $dateIndex = $flat.IndexOf($Date, [System.StringComparison]::OrdinalIgnoreCase)
    if ($numberIndex -lt 0 -or $dateIndex -lt 0) { return $false }
    $actIndex = $flat.LastIndexOf("Акт", $numberIndex, [System.StringComparison]::OrdinalIgnoreCase)
    if ($actIndex -lt 0) { return $false }
    return (($numberIndex - $actIndex) -le 60 -and [Math]::Abs($dateIndex - $numberIndex) -le 180)
}

Write-Host "CargoTech AI Acceptance v2"
Write-Host "Base URL: $BaseUrl"
Write-Host "Results: $ResultDir"
Write-Host ""

# 01 Infrastructure
Invoke-JsonTest "01-health" "Qdrant доступен" "GET" "/api/ai/qdrant/health" $null {
    param($j,$code,$raw,$ms)
    @{ passed = ($code -eq 200 -and $j.success -eq $true); message = "HTTP=$code, ${ms}ms" }
}
Invoke-JsonTest "02-auth" "GigaChat авторизован" "GET" "/api/ai/gigachat/auth/check" $null {
    param($j,$code,$raw,$ms)
    @{ passed = ($code -eq 200 -and $j.authorized -eq $true); message = "authorized=$($j.authorized)" }
}

# 02 Index rich RAG
Invoke-JsonTest "03-index-rag-v2" "Индексируется расширенный RAG-корпус" "POST" "/api/ai/rag/index/chunks" $RagFile {
    param($j,$code,$raw,$ms)
    @{ passed = ($code -eq 200 -and $j.success -eq $true -and [int]$j.indexed_chunks -eq 22); message = "indexed=$($j.indexed_chunks)" }
}

# Search helper files
$searchCurrent = Join-Path $ResultDir "04-search-current-request.json"
Write-Utf8Json $searchCurrent (New-SearchRequest "срок оплаты 15 календарных дней после акта" @{
    rag_collection="CONTRACT_CONTEXT"; claim_type="PAYMENT_DELAY"; client_id="acc_client_agro";
    contract_id="acc_contract_agro_2026"; chunk_type="PAYMENT_TERM"; is_current=$true
})
Invoke-JsonTest "04-current-not-stale" "RAG берет актуальную редакцию и исключает архивную" "POST" "/api/ai/rag/search/chunks" $searchCurrent {
    param($j,$code,$raw,$ms)
    $ids = @($j.hits | ForEach-Object { $_.chunk.chunkId })
    @{ passed = ($code -eq 200 -and $ids -contains "acc_agro_payterm_current" -and $ids -notcontains "acc_agro_payterm_stale");
       message = "ids=$($ids -join ',')" }
}

$searchForeign = Join-Path $ResultDir "05-search-foreign-request.json"
Write-Utf8Json $searchForeign (New-SearchRequest "штраф 50000 за непредоставление" @{
    rag_collection="CONTRACT_CONTEXT"; claim_type="LOADING_FAILURE"; client_id="acc_client_marshall";
    contract_id="acc_contract_loading_2026"; chunk_type="LOADING_FAILURE_PENALTY"; is_current=$true
})
Invoke-JsonTest "05-foreign-tenant-excluded" "Чужой штраф 50000 не попадает в контекст" "POST" "/api/ai/rag/search/chunks" $searchForeign {
    param($j,$code,$raw,$ms)
    $ids = @($j.hits | ForEach-Object { $_.chunk.chunkId })
    @{ passed = ($code -eq 200 -and $ids -contains "acc_loading_penalty_current" -and $ids -notcontains "acc_other_loading_penalty");
       message = "ids=$($ids -join ',')" }
}

$searchWrongContract = Join-Path $ResultDir "06-search-wrong-contract-request.json"
Write-Utf8Json $searchWrongContract (New-SearchRequest "срок оплаты" @{
    rag_collection="CONTRACT_CONTEXT"; claim_type="PAYMENT_DELAY"; client_id="acc_client_agro";
    contract_id="unknown_contract"; chunk_type="PAYMENT_TERM"; is_current=$true
})
Invoke-JsonTest "06-contract-isolation" "Неверный contract_id возвращает 0 результатов" "POST" "/api/ai/rag/search/chunks" $searchWrongContract {
    param($j,$code,$raw,$ms)
    @{ passed = ($code -eq 200 -and [int]$j.hits_count -eq 0); message = "hits=$($j.hits_count)" }
}

$searchLegal = Join-Path $ResultDir "07-search-legal-request.json"
Write-Utf8Json $searchLegal (New-SearchRequest "надлежащее исполнение срок договорная неустойка" @{
    rag_collection="LEGAL_CONTEXT"; claim_type="PAYMENT_DELAY"; is_current=$true
} 10 0.0)
Invoke-JsonTest "07-legal-context" "RAG содержит проверенные правовые основания" "POST" "/api/ai/rag/search/chunks" $searchLegal {
    param($j,$code,$raw,$ms)
    $ids = @($j.hits | ForEach-Object { $_.chunk.chunkId })
    $ok = ($ids -contains "acc_legal_309_payment_delay") -and
          ($ids -contains "acc_legal_314_payment") -and
          (($ids -contains "acc_legal_330_payment_delay") -or ($ids -contains "acc_legal_395_payment"))
    @{ passed = ($code -eq 200 -and $ok); message = "ids=$($ids -join ',')" }
}

$searchTemplate = Join-Path $ResultDir "08-search-template-request.json"
Write-Utf8Json $searchTemplate (New-SearchRequest "шаблон претензии просрочка оплаты приложения расчет" @{
    rag_collection="TEMPLATE_CONTEXT"; claim_type="PAYMENT_DELAY"; is_current=$true
} 5 0.0)
Invoke-JsonTest "08-template-context" "RAG возвращает шаблон PAYMENT_DELAY" "POST" "/api/ai/rag/search/chunks" $searchTemplate {
    param($j,$code,$raw,$ms)
    $ids = @($j.hits | ForEach-Object { $_.chunk.chunkId })
    @{ passed = ($code -eq 200 -and $ids -contains "acc_template_payment_v2"); message = "ids=$($ids -join ',')" }
}

$searchExample = Join-Path $ResultDir "09-search-example-request.json"
Write-Utf8Json $searchExample (New-SearchRequest "стилевой пример срыв погрузки" @{
    rag_collection="SIMILAR_EXAMPLE"; claim_type="LOADING_FAILURE"; is_current=$true
} 5 0.0)
Invoke-JsonTest "09-example-context" "RAG возвращает безопасный стилевой пример" "POST" "/api/ai/rag/search/chunks" $searchExample {
    param($j,$code,$raw,$ms)
    $ids = @($j.hits | ForEach-Object { $_.chunk.chunkId })
    @{ passed = ($code -eq 200 -and $ids -contains "acc_example_loading_v2"); message = "ids=$($ids -join ',')" }
}

# Payment AGRO
$agroFile = Join-Path $ResultDir "10-agro-request.json"
$agro = New-PaymentRequest `
    "acc-agro-001" "acc_client_agro" "acc_contract_agro_2026" "AGRO-15/2026" "01.02.2026" `
    "ООО Экспедитор Агро" "7700000011" "ООО Агро Клиент" "7800000011" `
    "ACT-AGRO-001" "01.07.2026" "ТТН-AGRO-001" "INV-AGRO-001" "ORD-AGRO-001" `
    "Москва - Тула" "16.07.2026" "20.07.2026" 100000 200 100200 4 `
    "0,05% от задолженности за каждый календарный день" "100000 × 0,05% × 4 = 200"
Write-Utf8Json $agroFile $agro
Invoke-JsonTest "10-agro-payment-generation" "PAYMENT_DELAY использует 15 дней и только договор Agro" "POST" "/api/ai/claims/generate" $agroFile {
    param($j,$code,$raw,$ms)
    $text = Get-ClaimText $j
    $ids = Get-UsedContractIds $j
    $foreign = @($ids | Where-Object { $_ -notlike "acc_agro_*" })
    $attachmentsOk = (Has-RequiredAttachment $j "CONTRACT") -and (Has-RequiredAttachment $j "ACT") -and
                     (Has-RequiredAttachment $j "TTN") -and (Has-RequiredAttachment $j "INVOICE") -and
                     (Has-RequiredAttachment $j "CALCULATION")
    $ok = $code -eq 200 -and $j.status -eq "PASSED" -and $j.guardrail_result.decision -eq "PASS" -and
          $j.rag_used -eq $true -and @($j.rag_warnings).Count -eq 0 -and
          $text -match '15\s+календарн' -and $text -notmatch '75\s+календарн|30\s+календарн' -and
          ($ids -contains "acc_agro_payterm_current") -and ($ids -contains "acc_agro_penalty_current") -and
          $ids -notcontains "acc_agro_payterm_stale" -and $foreign.Count -eq 0 -and $attachmentsOk -and $ms -le 60000
    @{ passed=$ok; message="status=$($j.status), ids=$($ids -join ','), attachments=$attachmentsOk, ${ms}ms" }
} "agro"

# Payment EFES
$efesFile = Join-Path $ResultDir "11-efes-request.json"
$efes = New-PaymentRequest `
    "acc-efes-001" "acc_client_efes" "acc_contract_efes_2026" "EFES-75/2026" "15.01.2026" `
    "ООО Экспедитор Эфес" "7700000022" "ООО Эфес Клиент" "7800000022" `
    "ACT-EFES-001" "01.05.2026" "ТТН-EFES-001" "INV-EFES-001" "ORD-EFES-001" `
    "Москва - Калуга" "15.07.2026" "20.07.2026" 200000 1000 201000 5 `
    "0,1% от задолженности за каждый день" "200000 × 0,1% × 5 = 1000"
Write-Utf8Json $efesFile $efes
Invoke-JsonTest "11-efes-payment-generation" "PAYMENT_DELAY использует 75 дней и не смешивает клиентов" "POST" "/api/ai/claims/generate" $efesFile {
    param($j,$code,$raw,$ms)
    $text = Get-ClaimText $j
    $ids = Get-UsedContractIds $j
    $foreign = @($ids | Where-Object { $_ -notlike "acc_efes_*" })
    $ok = $code -eq 200 -and $j.status -eq "PASSED" -and $j.guardrail_result.decision -eq "PASS" -and
          $text -match '75\s+календарн' -and $text -notmatch '15\s+календарн|30\s+календарн' -and
          ($ids -contains "acc_efes_payterm_current") -and ($ids -contains "acc_efes_penalty_current") -and
          $foreign.Count -eq 0 -and $ms -le 60000
    @{ passed=$ok; message="status=$($j.status), ids=$($ids -join ','), ${ms}ms" }
} "efes"

# Loading full case repeated
$loadingFile = Join-Path $ResultDir "12-loading-request.json"
Write-Utf8Json $loadingFile (New-LoadingRequest)

for ($i=1; $i -le $LoadingRepeats; $i++) {
    Invoke-JsonTest "12-loading-claim-$i" "LOADING_FAILURE claim, прогон $i/$LoadingRepeats" "POST" "/api/ai/claims/generate" $loadingFile {
        param($j,$code,$raw,$ms)
        $text = Get-ClaimText $j
        $ids = Get-UsedContractIds $j
        $foreign = @($ids | Where-Object { $_ -notlike "acc_loading_*" })
        $ok = $code -eq 200 -and $j.status -eq "PASSED" -and $j.guardrail_result.decision -eq "PASS" -and
              $text -match '25\s*000|25000' -and $text -notmatch '50\s*000|50000' -and
              ($ids -contains "acc_loading_duty_current") -and ($ids -contains "acc_loading_penalty_current") -and
              $foreign.Count -eq 0 -and -not (Has-DispatcherAttribution $text) -and
              (Has-RequiredAttachment $j "TRANSPORT_ORDER") -and (Has-RequiredAttachment $j "LOADING_FAILURE_ACT") -and
              $ms -le 60000
        @{ passed=$ok; message="status=$($j.status), dispatcherAttribution=$(Has-DispatcherAttribution $text), ids=$($ids -join ','), ${ms}ms" }
    } "loading"

    Invoke-JsonTest "13-notification-$i" "LOADING_FAILURE notification, прогон $i/$LoadingRepeats" "POST" "/api/ai/documents/loading-failure/notification/generate" $loadingFile {
        param($j,$code,$raw,$ms)
        $text = Get-DocumentText $j
        $ok = $code -eq 200 -and $j.status -eq "PASSED" -and $j.guardrail_result.decision -eq "PASS" -and
              $j.generated_document.document_title -eq "Уведомление о составлении акта о непредоставлении транспортного средства" -and
              $text -match '21\.07\.2026' -and $text -match 'Москва\s*[-—]\s*Тверь' -and
              $text -notmatch 'ACT-ACC-250' -and -not (Has-DispatcherAttribution $text) -and $ms -le 60000
        @{ passed=$ok; message="status=$($j.status), dispatcherAttribution=$(Has-DispatcherAttribution $text), ${ms}ms" }
    } "loading"

    Invoke-JsonTest "14-act-$i" "LOADING_FAILURE act, прогон $i/$LoadingRepeats" "POST" "/api/ai/documents/loading-failure/act/generate" $loadingFile {
        param($j,$code,$raw,$ms)
        $text = Get-DocumentText $j
        $identity = Has-ActIdentity $text "ACT-ACC-250" "20.07.2026"
        $ok = $code -eq 200 -and $j.status -eq "PASSED" -and $j.guardrail_result.decision -eq "PASS" -and
              $j.generated_document.document_title -eq "Акт о непредоставлении транспортного средства" -and
              $identity -and $text -match 'односторонн' -and $text -match 'ООО "Маршалл Заказчик"' -and
              -not (Has-DispatcherAttribution $text) -and $ms -le 60000
        @{ passed=$ok; message="status=$($j.status), actIdentity=$identity, dispatcherAttribution=$(Has-DispatcherAttribution $text), ${ms}ms" }
    } "loading"
}

# Token budgets
$agroBudgetPass = $script:TokenGroups.agro -le 32000
Add-Result "15-token-budget-agro" "AGRO case укладывается в 32K токенов" $agroBudgetPass 0 0 $script:TokenGroups.agro "tokens=$($script:TokenGroups.agro)" ""
$efesBudgetPass = $script:TokenGroups.efes -le 32000
Add-Result "16-token-budget-efes" "EFES case укладывается в 32K токенов" $efesBudgetPass 0 0 $script:TokenGroups.efes "tokens=$($script:TokenGroups.efes)" ""
$loadingPerCase = if ($LoadingRepeats -gt 0) { [math]::Ceiling($script:TokenGroups.loading / $LoadingRepeats) } else { 0 }
$loadingBudgetPass = $loadingPerCase -le 32000
Add-Result "17-token-budget-loading" "Полный LOADING_FAILURE case укладывается в 32K" $loadingBudgetPass 0 0 $loadingPerCase "avg full-case tokens=$loadingPerCase" ""

# Final
$total = $script:Results.Count
$passed = @($script:Results | Where-Object { $_.passed }).Count
$failed = $total - $passed
$summary = [pscustomobject]@{
    started_at = $Stamp
    base_url = $BaseUrl
    loading_repeats = $LoadingRepeats
    total = $total
    passed = $passed
    failed = $failed
    token_groups = $script:TokenGroups
    loading_average_full_case_tokens = $loadingPerCase
    results = $script:Results
}
[System.IO.File]::WriteAllText(
    (Join-Path $ResultDir "summary.json"),
    ($summary | ConvertTo-Json -Depth 30),
    [System.Text.UTF8Encoding]::new($false)
)
$table = $script:Results | Select-Object id,passed,http_code,elapsed_ms,tokens,description,message | Format-Table -AutoSize | Out-String -Width 260
[System.IO.File]::WriteAllText(
    (Join-Path $ResultDir "summary.txt"),
    "CargoTech AI Acceptance v2`r`nTotal: $total`r`nPassed: $passed`r`nFailed: $failed`r`nTokens: $($script:TokenGroups | ConvertTo-Json -Compress)`r`n`r`n$table",
    [System.Text.UTF8Encoding]::new($false)
)
$zip = "$ResultDir.zip"
Compress-Archive -Path (Join-Path $ResultDir "*") -DestinationPath $zip -Force

Write-Host ""
Write-Host "========================================"
Write-Host "TOTAL:  $total"
Write-Host "PASSED: $passed"
Write-Host "FAILED: $failed"
Write-Host "ARCHIVE: $zip"
Write-Host "========================================"
if ($failed -gt 0) { exit 1 }
exit 0
