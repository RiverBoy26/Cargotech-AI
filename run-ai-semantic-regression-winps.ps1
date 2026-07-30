param(
    [string]$BaseUrl = "http://localhost:8084",
    [int]$DocumentRepeats = 3
)

$ErrorActionPreference = "Stop"

[Console]::InputEncoding = [System.Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)

$Timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$ResultDir = Join-Path (Get-Location) "ai-semantic-regression-$Timestamp"
New-Item -ItemType Directory -Path $ResultDir -Force | Out-Null

$script:Results = @()

function Write-Utf8Json {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)]$Object
    )

    $json = $Object | ConvertTo-Json -Depth 60 -Compress
    [System.IO.File]::WriteAllText(
        $Path,
        $json,
        [System.Text.UTF8Encoding]::new($false)
    )
}

function Add-Result {
    param(
        [string]$Id,
        [string]$Description,
        [bool]$Passed,
        [int]$HttpCode,
        [string]$Message,
        [string]$ResponseFile,
        [int]$Tokens = 0
    )

    $script:Results += [pscustomobject]@{
        id = $Id
        description = $Description
        passed = $Passed
        http_code = $HttpCode
        tokens = $Tokens
        message = $Message
        response_file = $ResponseFile
    }

    $mark = if ($Passed) { "PASS" } else { "FAIL" }
    Write-Host "[$mark] $Id — $Description :: $Message"
}

function Invoke-ApiTest {
    param(
        [Parameter(Mandatory = $true)][string]$Id,
        [Parameter(Mandatory = $true)][string]$Description,
        [Parameter(Mandatory = $true)][string]$Method,
        [Parameter(Mandatory = $true)][string]$Path,
        [string]$BodyFile,
        [string]$ContentType = "application/json; charset=utf-8",
        [Parameter(Mandatory = $true)][scriptblock]$Validate,
        [int]$MaxTimeSeconds = 240
    )

    $responseFile = Join-Path $ResultDir "$Id-response.json"
    $url = "$BaseUrl$Path"

    $curlArgs = @(
        "-sS",
        "--max-time", "$MaxTimeSeconds",
        "-X", $Method,
        $url,
        "-o", $responseFile,
        "-w", "%{http_code}"
    )

    if ($BodyFile) {
        $curlArgs += @(
            "-H", "Content-Type: $ContentType",
            "--data-binary", "@$BodyFile"
        )
    }

    $httpCodeRaw = & curl.exe @curlArgs
    $curlExit = $LASTEXITCODE

    if ($curlExit -ne 0) {
        Add-Result `
            -Id $Id `
            -Description $Description `
            -Passed $false `
            -HttpCode 0 `
            -Message "curl exit code $curlExit" `
            -ResponseFile $responseFile
        return
    }

    $httpCode = 0
    [void][int]::TryParse(($httpCodeRaw | Out-String).Trim(), [ref]$httpCode)

    $raw = ""
    if (Test-Path $responseFile) {
        $raw = Get-Content $responseFile -Raw -Encoding UTF8
    }

    $json = $null
    if (-not [string]::IsNullOrWhiteSpace($raw)) {
        try {
            $json = $raw | ConvertFrom-Json
        } catch {
            $json = $null
        }
    }

    try {
        $validation = & $Validate $json $httpCode $raw
        $passed = [bool]$validation.passed
        $message = [string]$validation.message
    } catch {
        $passed = $false
        $message = "validator error: $($_.Exception.Message)"
    }

    $tokens = 0
    if ($json -and $json.token_usage -and $json.token_usage.total_tokens) {
        $tokens = [int]$json.token_usage.total_tokens
    }

    Add-Result `
        -Id $Id `
        -Description $Description `
        -Passed $passed `
        -HttpCode $httpCode `
        -Message $message `
        -ResponseFile $responseFile `
        -Tokens $tokens
}

function New-PaymentDelayRequest {
    return @{
        case_facts = @{
            claim_id = "claim_pd_regression_001"
            claim_type = "PAYMENT_DELAY"
            creditor = @{
                name = "ООО Экспедитор"
                inn = "7800000000"
                legal_address = "г. Санкт-Петербург"
            }
            debtor = @{
                name = "ООО Клиент"
                inn = "7700000000"
                legal_address = "г. Москва"
            }
            contract = @{
                contract_number = "45/2026"
                contract_date = "10.01.2026"
            }
            shipment = @{
                order_number = "ORD-157"
                route = "Санкт-Петербург — Москва"
                act_number = "157"
                act_date = "01.05.2026"
                ttn_number = "ТТН-157"
                invoice_number = "INV-157"
            }
            payment = @{
                payment_due_date = "31.05.2026"
                payment_status = "UNPAID"
                payment_confirmed_by_accountant = $true
            }
            claim_date = "10.06.2026"
        }
        backend_calculation = @{
            principal_debt = 240000
            penalty_type = "CONTRACT_PENALTY"
            penalty_rate_text = "0,1% от суммы долга за каждый календарный день просрочки"
            overdue_days = 10
            penalty_amount = 2400
            total_amount = 242400
            currency = "RUB"
            formula_text = "240000 × 0,1% × 10 = 2400"
        }
        contract_context = @()
        legal_context = @()
        template_context = $null
        similar_examples = @()
        rag_options = @{
            enabled = $true
            contract_id = "contract_45_2026"
            client_id = "demo_client"
        }
    }
}

function New-LoadingFailureRequest {
    param([bool]$Confirmed = $true)

    return @{
        case_facts = @{
            claim_id = "claim_lf_regression_001"
            claim_type = "LOADING_FAILURE"
            creditor = @{
                name = "ООО Клиент-Заказчик"
                inn = "7700000000"
                legal_address = "Москва"
            }
            debtor = @{
                name = "ООО Перевозчик"
                inn = "7800000000"
                legal_address = "Санкт-Петербург"
            }
            contract = @{
                contract_number = "LF-77/2026"
                contract_date = "05.02.2026"
            }
            shipment = @{
                order_number = "ORD-LF-200"
                route = "Москва - Казань"
                act_number = "ACT-LF-200"
                act_date = "12.06.2026"
                ttn_number = $null
                invoice_number = $null
                loading_date = "12.06.2026"
                loading_address = "Москва, склад №4"
                loading_time_window = "09:00-12:00"
                vehicle_requirements = "тент 20 т"
                carrier_name = "ООО Перевозчик"
                failure_confirmed_by_dispatcher = $Confirmed
            }
            payment = $null
            claim_date = "13.06.2026"
        }
        backend_calculation = @{
            principal_debt = 0
            penalty_type = "CONTRACT_PENALTY"
            penalty_rate_text = "фиксированный штраф за непредоставление транспортного средства"
            overdue_days = 0
            penalty_amount = 15000
            total_amount = 15000
            currency = "RUB"
            formula_text = "фиксированный штраф 15000 RUB"
        }
        contract_context = @()
        legal_context = @()
        template_context = $null
        similar_examples = @()
        rag_options = @{
            enabled = $true
            contract_id = "contract_lf_001"
            client_id = "demo_client"
        }
    }
}

function New-GuardrailPaymentPayload {
    param(
        [switch]$UnknownInn,
        [switch]$UnknownAmount,
        [switch]$UnknownContractChunk
    )

    $claimText = @"
От: ООО Экспедитор, ИНН 7800000000, г. Санкт-Петербург.
Кому: ООО Клиент, ИНН 7700000000, г. Москва.
Претензия по договору №45/2026 от 10.01.2026.
Перевозка по маршруту Санкт-Петербург — Москва, заказ ORD-157.
Услуги подтверждены актом №157 от 01.05.2026.
Срок оплаты истёк 31.05.2026.
Основной долг 240 000 руб., неустойка 2 400 руб., итого 242 400 руб.
"@

    if ($UnknownInn) {
        $claimText += "`nДополнительный получатель: ИНН 7812345678."
    }

    if ($UnknownAmount) {
        $claimText += "`nДополнительно требуется 999 999 руб."
    }

    $contractChunkId = if ($UnknownContractChunk) { "fake_contract_chunk" } else { "chunk_contract_001" }

    return @{
        request = @{
            case_facts = @{
                claim_id = "claim_guardrail_pd_001"
                claim_type = "PAYMENT_DELAY"
                creditor = @{
                    name = "ООО Экспедитор"
                    inn = "7800000000"
                    legal_address = "г. Санкт-Петербург"
                }
                debtor = @{
                    name = "ООО Клиент"
                    inn = "7700000000"
                    legal_address = "г. Москва"
                }
                contract = @{
                    contract_number = "45/2026"
                    contract_date = "10.01.2026"
                }
                shipment = @{
                    order_number = "ORD-157"
                    route = "Санкт-Петербург — Москва"
                    act_number = "157"
                    act_date = "01.05.2026"
                    ttn_number = "ТТН-157"
                    invoice_number = "INV-157"
                }
                payment = @{
                    payment_due_date = "31.05.2026"
                    payment_status = "UNPAID"
                    payment_confirmed_by_accountant = $true
                }
                claim_date = "10.06.2026"
            }
            backend_calculation = @{
                principal_debt = 240000
                penalty_type = "CONTRACT_PENALTY"
                penalty_rate_text = "0,1% в день"
                overdue_days = 10
                penalty_amount = 2400
                total_amount = 242400
                currency = "RUB"
                formula_text = "240000 × 0,1% × 10"
            }
            contract_context = @(
                @{
                    chunk_id = "chunk_contract_001"
                    clause_number = "4.2"
                    section_title = "Оплата"
                    text = "Оплата производится в течение 30 календарных дней."
                },
                @{
                    chunk_id = "chunk_contract_002"
                    clause_number = "6.1"
                    section_title = "Ответственность"
                    text = "Неустойка составляет 0,1% в день."
                }
            )
            legal_context = @(
                @{
                    chunk_id = "chunk_legal_gk_330"
                    law_code = "ГК РФ"
                    article = "330"
                    purpose = "договорная неустойка"
                    text = "Неустойкой признается определенная законом или договором денежная сумма."
                    citation = "ст. 330 ГК РФ"
                    verified_at = "2026-07-30"
                    applicability = "PAYMENT_DELAY"
                }
            )
            template_context = $null
            similar_examples = @()
        }
        response = @{
            claim_type = "PAYMENT_DELAY"
            claim_text = $claimText
            summary_for_lawyer = "Просрочка оплаты по договору №45/2026."
            used_contract_clauses = @(
                @{
                    clause_number = "4.2"
                    chunk_id = $contractChunkId
                    reason = "срок оплаты"
                },
                @{
                    clause_number = "6.1"
                    chunk_id = "chunk_contract_002"
                    reason = "договорная неустойка"
                }
            )
            used_law_articles = @(
                @{
                    chunk_id = "chunk_legal_gk_330"
                    law_code = "ГК РФ"
                    article = "330"
                    reason = "основание договорной неустойки"
                }
            )
            backend_calculation_used = @{
                principal_debt = 240000
                penalty_type = "CONTRACT_PENALTY"
                penalty_amount = 2400
                total_amount = 242400
                overdue_days = 10
                currency = "RUB"
            }
            attachments = @(
                @{
                    document_type = "CONTRACT"
                    document_name = "Договор №45/2026"
                    required = $true
                },
                @{
                    document_type = "ACT"
                    document_name = "Акт №157 от 01.05.2026"
                    required = $true
                },
                @{
                    document_type = "TTN"
                    document_name = "ТТН-157"
                    required = $true
                },
                @{
                    document_type = "INVOICE"
                    document_name = "INV-157"
                    required = $true
                },
                @{
                    document_type = "CALCULATION"
                    document_name = "Расчёт задолженности"
                    required = $true
                }
            )
            warnings = @()
            manual_review_required = $true
        }
    }
}

function New-GuardrailLoadingSemanticPayload {
    $text = @"
От: ООО Клиент-Заказчик, ИНН 7700000000, Москва.
Кому: ООО Перевозчик, ИНН 7800000000, Санкт-Петербург.
Претензия по договору №LF-77/2026 от 05.02.2026.
Заявка ORD-LF-200, маршрут Москва-Казань.
Погрузка назначена 12.06.2026 по адресу Москва, склад №4 в период 09:00-12:00.
Требовалось транспортное средство: тент 20 т.
Факт неподтверждения подачи транспортного средства подтверждён диспетчером.
На основании п. 6.4 просим оплатить договорный штраф 15 000 руб.
"@

    return @{
        request = @{
            case_facts = @{
                claim_id = "claim_guardrail_lf_001"
                claim_type = "LOADING_FAILURE"
                creditor = @{
                    name = "ООО Клиент-Заказчик"
                    inn = "7700000000"
                    legal_address = "Москва"
                }
                debtor = @{
                    name = "ООО Перевозчик"
                    inn = "7800000000"
                    legal_address = "Санкт-Петербург"
                }
                contract = @{
                    contract_number = "LF-77/2026"
                    contract_date = "05.02.2026"
                }
                shipment = @{
                    order_number = "ORD-LF-200"
                    route = "Москва - Казань"
                    act_number = "ACT-LF-200"
                    act_date = "12.06.2026"
                    loading_date = "12.06.2026"
                    loading_address = "Москва, склад №4"
                    loading_time_window = "09:00-12:00"
                    vehicle_requirements = "тент 20 т"
                    carrier_name = "ООО Перевозчик"
                    failure_confirmed_by_dispatcher = $true
                }
                payment = $null
                claim_date = "13.06.2026"
            }
            backend_calculation = @{
                principal_debt = 0
                penalty_type = "CONTRACT_PENALTY"
                penalty_rate_text = "фиксированный штраф"
                overdue_days = 0
                penalty_amount = 15000
                total_amount = 15000
                currency = "RUB"
                formula_text = "15000"
            }
            contract_context = @(
                @{
                    chunk_id = "chunk_lf_contract_001"
                    clause_number = "5.1"
                    section_title = "Подача ТС"
                    text = "Перевозчик обязан предоставить транспортное средство."
                },
                @{
                    chunk_id = "chunk_lf_contract_002"
                    clause_number = "6.4"
                    section_title = "Штраф"
                    text = "Штраф за непредоставление транспортного средства составляет 15000 рублей."
                }
            )
            legal_context = @(
                @{
                    chunk_id = "chunk_lf_legal_gk_330"
                    law_code = "ГК РФ"
                    article = "330"
                    purpose = "договорная неустойка"
                    text = "Понятие неустойки."
                    citation = "ст. 330 ГК РФ"
                    verified_at = "2026-07-30"
                    applicability = "LOADING_FAILURE"
                }
            )
            template_context = $null
            similar_examples = @()
        }
        response = @{
            claim_type = "LOADING_FAILURE"
            claim_text = $text
            summary_for_lawyer = "Претензия в связи с неподтверждением подачи транспортного средства."
            used_contract_clauses = @(
                @{
                    clause_number = "5.1"
                    chunk_id = "chunk_lf_contract_001"
                    reason = "обязанность предоставить ТС"
                },
                @{
                    clause_number = "6.4"
                    chunk_id = "chunk_lf_contract_002"
                    reason = "договорный штраф"
                }
            )
            used_law_articles = @(
                @{
                    chunk_id = "chunk_lf_legal_gk_330"
                    law_code = "ГК РФ"
                    article = "330"
                    reason = "договорная неустойка"
                }
            )
            backend_calculation_used = @{
                principal_debt = 0
                penalty_type = "CONTRACT_PENALTY"
                penalty_amount = 15000
                total_amount = 15000
                overdue_days = 0
                currency = "RUB"
            }
            attachments = @(
                @{
                    document_type = "LOADING_FAILURE_ACT"
                    document_name = "Акт ACT-LF-200 от 12.06.2026"
                    required = $true
                }
            )
            warnings = @()
            manual_review_required = $true
        }
    }
}

Write-Host "CargoTech AI semantic regression suite"
Write-Host "Base URL: $BaseUrl"
Write-Host "Results: $ResultDir"
Write-Host ""

# 01. Infrastructure
Invoke-ApiTest `
    -Id "01-qdrant-health" `
    -Description "AI-модуль видит Qdrant" `
    -Method "GET" `
    -Path "/api/ai/qdrant/health" `
    -Validate {
        param($j, $code, $raw)
        if ($code -eq 200 -and $j.success -eq $true) {
            return @{ passed = $true; message = "Qdrant доступен" }
        }
        return @{ passed = $false; message = "ожидался success=true" }
    }

Invoke-ApiTest `
    -Id "02-gigachat-auth" `
    -Description "Авторизация GigaChat" `
    -Method "GET" `
    -Path "/api/ai/gigachat/auth/check" `
    -Validate {
        param($j, $code, $raw)
        if ($code -eq 200 -and $j.authorized -eq $true) {
            return @{ passed = $true; message = "authorized=true" }
        }
        return @{ passed = $false; message = "ожидался authorized=true" }
    }

$embeddingRequest = Join-Path $ResultDir "03-embedding-request.json"
Write-Utf8Json -Path $embeddingRequest -Object @{
    text = "Претензия по просрочке оплаты транспортно-экспедиционных услуг"
}
Invoke-ApiTest `
    -Id "03-embedding" `
    -Description "EmbeddingsGigaR, размерность 2560" `
    -Method "POST" `
    -Path "/api/ai/gigachat/embeddings/test" `
    -BodyFile $embeddingRequest `
    -Validate {
        param($j, $code, $raw)
        if ($code -eq 200 -and $j.success -eq $true -and [int]$j.dimension -eq 2560) {
            return @{ passed = $true; message = "dimension=2560" }
        }
        return @{ passed = $false; message = "неверная размерность embeddings" }
    }

# 04. Seed demo data
Invoke-ApiTest `
    -Id "04-seed-payment-delay" `
    -Description "Идемпотентный seed PAYMENT_DELAY" `
    -Method "POST" `
    -Path "/api/ai/rag/demo/seed-payment-delay" `
    -Validate {
        param($j, $code, $raw)
        if ($code -eq 200 -and $j.success -eq $true -and [int]$j.indexed_chunks -ge 8) {
            return @{ passed = $true; message = "indexed_chunks=$($j.indexed_chunks)" }
        }
        return @{ passed = $false; message = "seed не выполнен" }
    }

# 05-06. Tenant isolation
$tenantAllowedFile = Join-Path $ResultDir "05-tenant-allowed-request.json"
Write-Utf8Json -Path $tenantAllowedFile -Object @{
    query = "payment term 30 calendar days after signing the service act"
    filters = @{
        rag_collection = "CONTRACT_CONTEXT"
        claim_type = "PAYMENT_DELAY"
        client_id = "demo_client"
        contract_id = "contract_45_2026"
        chunk_type = "PAYMENT_TERM"
        is_current = $true
    }
    limit = 5
    min_score = 0.0
}
Invoke-ApiTest `
    -Id "05-tenant-allowed" `
    -Description "Договор доступен своему client_id" `
    -Method "POST" `
    -Path "/api/ai/rag/search/chunks" `
    -BodyFile $tenantAllowedFile `
    -Validate {
        param($j, $code, $raw)
        $found = $false
        foreach ($hit in @($j.hits)) {
            if ($hit.chunk.chunkId -eq "chunk_contract_001" -and
                $hit.chunk.clientId -eq "demo_client" -and
                $hit.chunk.contractId -eq "contract_45_2026") {
                $found = $true
            }
        }
        if ($code -eq 200 -and $j.success -eq $true -and $found) {
            return @{ passed = $true; message = "найден chunk_contract_001" }
        }
        return @{ passed = $false; message = "ожидаемый договорный чанк не найден" }
    }

$tenantDeniedFile = Join-Path $ResultDir "06-tenant-denied-request.json"
Write-Utf8Json -Path $tenantDeniedFile -Object @{
    query = "payment term 30 calendar days after signing the service act"
    filters = @{
        rag_collection = "CONTRACT_CONTEXT"
        claim_type = "PAYMENT_DELAY"
        client_id = "other_client"
        contract_id = "contract_45_2026"
        chunk_type = "PAYMENT_TERM"
        is_current = $true
    }
    limit = 5
    min_score = 0.0
}
Invoke-ApiTest `
    -Id "06-tenant-denied" `
    -Description "Чужой client_id не получает договор" `
    -Method "POST" `
    -Path "/api/ai/rag/search/chunks" `
    -BodyFile $tenantDeniedFile `
    -Validate {
        param($j, $code, $raw)
        if ($code -eq 200 -and [int]$j.hits_count -eq 0) {
            return @{ passed = $true; message = "hits_count=0" }
        }
        return @{ passed = $false; message = "tenant isolation нарушена" }
    }

# 07-08. RAG input validation
$piiIndexFile = Join-Path $ResultDir "07-rag-pii-rejection-request.json"
Write-Utf8Json -Path $piiIndexFile -Object @{
    source_batch_id = "negative-pii-regression"
    source_system = "regression-suite"
    chunks = @(
        @{
            chunk_id = "negative_pii_001"
            rag_collection = "CONTRACT_CONTEXT"
            chunk_type = "PAYMENT_TERM"
            claim_type = "PAYMENT_DELAY"
            client_id = "demo_client"
            contract_id = "negative_contract"
            contract_number = "NEG-1"
            contract_date = "01.01.2026"
            contour = "exp_client"
            contract_type = "ТЭУ"
            source_id = "negative-source"
            source_title = "Негативный тест"
            section_title = "Оплата"
            section_path = "4. Оплата"
            clause_number = "4.1"
            clause_topic = "срок оплаты"
            text = "Телефон водителя +7 999 123-45-67 и email driver@example.com"
            citation = "п. 4.1"
            is_current = $true
            extra = @{}
        }
    )
}
Invoke-ApiTest `
    -Id "07-rag-pii-rejection" `
    -Description "RAG отклоняет телефон и email" `
    -Method "POST" `
    -Path "/api/ai/rag/index/chunks" `
    -BodyFile $piiIndexFile `
    -Validate {
        param($j, $code, $raw)
        $lower = $raw.ToLowerInvariant()
        if ($code -ge 400 -and ($lower.Contains("raw phone") -or $lower.Contains("raw email") -or $lower.Contains("mask pii"))) {
            return @{ passed = $true; message = "PII отклонены" }
        }
        return @{ passed = $false; message = "ожидался отказ индексации PII" }
    }

$missingClientFile = Join-Path $ResultDir "08-rag-client-required-request.json"
Write-Utf8Json -Path $missingClientFile -Object @{
    source_batch_id = "negative-client-regression"
    source_system = "regression-suite"
    chunks = @(
        @{
            chunk_id = "negative_client_001"
            rag_collection = "CONTRACT_CONTEXT"
            chunk_type = "PAYMENT_TERM"
            claim_type = "PAYMENT_DELAY"
            client_id = $null
            contract_id = "negative_contract"
            contract_number = "NEG-2"
            contract_date = "01.01.2026"
            contour = "exp_client"
            contract_type = "ТЭУ"
            source_id = "negative-source"
            source_title = "Негативный тест"
            section_title = "Оплата"
            section_path = "4. Оплата"
            clause_number = "4.1"
            clause_topic = "срок оплаты"
            text = "Оплата производится в течение 30 дней."
            citation = "п. 4.1"
            is_current = $true
            extra = @{}
        }
    )
}
Invoke-ApiTest `
    -Id "08-rag-client-required" `
    -Description "CONTRACT_CONTEXT требует client_id" `
    -Method "POST" `
    -Path "/api/ai/rag/index/chunks" `
    -BodyFile $missingClientFile `
    -Validate {
        param($j, $code, $raw)
        if ($code -ge 400 -and $raw.ToLowerInvariant().Contains("client_id")) {
            return @{ passed = $true; message = "client_id обязателен" }
        }
        return @{ passed = $false; message = "чанк без client_id не был отклонён" }
    }

# 09-12. Positive end-to-end
$paymentRequestFile = Join-Path $ResultDir "09-payment-delay-request.json"
Write-Utf8Json -Path $paymentRequestFile -Object (New-PaymentDelayRequest)
Invoke-ApiTest `
    -Id "09-payment-delay-e2e" `
    -Description "PAYMENT_DELAY: RAG → GigaChat → parser → guardrails" `
    -Method "POST" `
    -Path "/api/ai/claims/generate" `
    -BodyFile $paymentRequestFile `
    -Validate {
        param($j, $code, $raw)
        if ($code -eq 200 -and $j.success -eq $true -and $j.status -eq "PASSED" -and $j.guardrail_result.decision -eq "PASS") {
            return @{ passed = $true; message = "PASSED, tokens=$($j.token_usage.total_tokens)" }
        }
        return @{ passed = $false; message = "ожидался PASSED/PASS" }
    }

$loadingRequestFile = Join-Path $ResultDir "10-loading-failure-request.json"
Write-Utf8Json -Path $loadingRequestFile -Object (New-LoadingFailureRequest -Confirmed $true)
Invoke-ApiTest `
    -Id "10-loading-claim-e2e" `
    -Description "LOADING_FAILURE claim: полный pipeline" `
    -Method "POST" `
    -Path "/api/ai/claims/generate" `
    -BodyFile $loadingRequestFile `
    -Validate {
        param($j, $code, $raw)
        if ($code -eq 200 -and $j.success -eq $true -and $j.status -eq "PASSED" -and $j.guardrail_result.decision -eq "PASS") {
            return @{ passed = $true; message = "PASSED, tokens=$($j.token_usage.total_tokens)" }
        }
        return @{ passed = $false; message = "ожидался PASSED/PASS" }
    }

for ($i = 1; $i -le $DocumentRepeats; $i++) {
    $id = "11-notification-e2e-$i"
    Invoke-ApiTest `
        -Id $id `
        -Description "Уведомление LOADING_FAILURE, повтор $i/$DocumentRepeats" `
        -Method "POST" `
        -Path "/api/ai/documents/loading-failure/notification/generate" `
        -BodyFile $loadingRequestFile `
        -Validate {
            param($j, $code, $raw)
            if ($code -eq 200 -and $j.success -eq $true -and $j.status -eq "PASSED" -and $j.guardrail_result.decision -eq "PASS") {
                return @{ passed = $true; message = "PASSED, tokens=$($j.token_usage.total_tokens)" }
            }
            return @{ passed = $false; message = "уведомление не прошло guardrails" }
        }
}

for ($i = 1; $i -le $DocumentRepeats; $i++) {
    $id = "12-act-e2e-$i"
    Invoke-ApiTest `
        -Id $id `
        -Description "Акт LOADING_FAILURE, повтор $i/$DocumentRepeats" `
        -Method "POST" `
        -Path "/api/ai/documents/loading-failure/act/generate" `
        -BodyFile $loadingRequestFile `
        -Validate {
            param($j, $code, $raw)
            if ($code -eq 200 -and $j.success -eq $true -and $j.status -eq "PASSED" -and $j.guardrail_result.decision -eq "PASS") {
                return @{ passed = $true; message = "PASSED, tokens=$($j.token_usage.total_tokens)" }
            }
            return @{ passed = $false; message = "акт не прошёл guardrails" }
        }
}

# 13. Early rejection before LLM for document generation
$unconfirmedRequestFile = Join-Path $ResultDir "13-unconfirmed-loading-request.json"
Write-Utf8Json -Path $unconfirmedRequestFile -Object (New-LoadingFailureRequest -Confirmed $false)
Invoke-ApiTest `
    -Id "13-unconfirmed-loading" `
    -Description "Документ не создаётся без подтверждения диспетчера" `
    -Method "POST" `
    -Path "/api/ai/documents/loading-failure/notification/generate" `
    -BodyFile $unconfirmedRequestFile `
    -Validate {
        param($j, $code, $raw)
        if ($code -ge 400 -and $raw.ToLowerInvariant().Contains("failure_confirmed_by_dispatcher")) {
            return @{ passed = $true; message = "запрос отклонён до LLM" }
        }
        return @{ passed = $false; message = "неподтверждённый факт не был отклонён" }
    }

# 14-17. Deterministic claim guardrail tests
$unknownInnFile = Join-Path $ResultDir "14-guardrail-unknown-inn-request.json"
Write-Utf8Json -Path $unknownInnFile -Object (New-GuardrailPaymentPayload -UnknownInn)
Invoke-ApiTest `
    -Id "14-guardrail-unknown-inn" `
    -Description "Guardrail блокирует чужой ИНН" `
    -Method "POST" `
    -Path "/api/ai/claims/guardrails/test" `
    -BodyFile $unknownInnFile `
    -Validate {
        param($j, $code, $raw)
        $joined = (@($j.result.errors) -join " ").ToLowerInvariant()
        if ($code -eq 200 -and $j.result.decision -eq "BLOCK" -and $joined.Contains("unknown inn")) {
            return @{ passed = $true; message = "BLOCK: unknown INN" }
        }
        return @{ passed = $false; message = "чужой ИНН не заблокирован" }
    }

$unknownAmountFile = Join-Path $ResultDir "15-guardrail-unknown-amount-request.json"
Write-Utf8Json -Path $unknownAmountFile -Object (New-GuardrailPaymentPayload -UnknownAmount)
Invoke-ApiTest `
    -Id "15-guardrail-unknown-amount" `
    -Description "Guardrail блокирует выдуманную сумму" `
    -Method "POST" `
    -Path "/api/ai/claims/guardrails/test" `
    -BodyFile $unknownAmountFile `
    -Validate {
        param($j, $code, $raw)
        $joined = (@($j.result.errors) -join " ").ToLowerInvariant()
        if ($code -eq 200 -and $j.result.decision -eq "BLOCK" -and ($joined.Contains("amount") -or $joined.Contains("sum"))) {
            return @{ passed = $true; message = "BLOCK: неизвестная сумма" }
        }
        return @{ passed = $false; message = "выдуманная сумма не заблокирована" }
    }

$unknownChunkFile = Join-Path $ResultDir "16-guardrail-unknown-chunk-request.json"
Write-Utf8Json -Path $unknownChunkFile -Object (New-GuardrailPaymentPayload -UnknownContractChunk)
Invoke-ApiTest `
    -Id "16-guardrail-unknown-chunk" `
    -Description "Guardrail блокирует выдуманный chunk_id договора" `
    -Method "POST" `
    -Path "/api/ai/claims/guardrails/test" `
    -BodyFile $unknownChunkFile `
    -Validate {
        param($j, $code, $raw)
        $joined = (@($j.result.errors) -join " ").ToLowerInvariant()
        if ($code -eq 200 -and $j.result.decision -eq "BLOCK" -and $joined.Contains("unknown contract chunk_id")) {
            return @{ passed = $true; message = "BLOCK: неизвестный contract chunk_id" }
        }
        return @{ passed = $false; message = "выдуманный chunk_id не заблокирован" }
    }

$semanticSubstitutionFile = Join-Path $ResultDir "17-guardrail-semantic-substitution-request.json"
Write-Utf8Json -Path $semanticSubstitutionFile -Object (New-GuardrailLoadingSemanticPayload)
Invoke-ApiTest `
    -Id "17-guardrail-semantic-substitution" `
    -Description "Guardrail блокирует «неподтверждение подачи»" `
    -Method "POST" `
    -Path "/api/ai/claims/guardrails/test" `
    -BodyFile $semanticSubstitutionFile `
    -Validate {
        param($j, $code, $raw)
        $joined = (@($j.result.errors) -join " ").ToLowerInvariant()
        if ($code -eq 200 -and $j.result.decision -eq "BLOCK" -and
            ($joined.Contains("absence of confirmation") -or $joined.Contains("non-provision"))) {
            return @{ passed = $true; message = "BLOCK: смысловая подмена" }
        }
        return @{ passed = $false; message = "смысловая подмена не заблокирована" }
    }

# 18-19. Parser regression
$parserAliasFile = Join-Path $ResultDir "18-parser-alias-request.txt"
$parserAliasJson = @{
    claim_type = "PAYMENT_DELAY"
    claim_text = "Тестовая претензия"
    summary_for_lawyer = "Тест parser alias"
    used_contract_clauses = @()
    used_law_articles = @()
    backend_calculation_used = @{
        principal_debt = 0
        penalty_type = "NONE"
        penalty_amount = 0
        total_amount = 0
        overdue_days = 0
        currency = "RUB"
    }
    attachments = @(
        @{
            document_type = "TIR_TRANSPORT_DOCUMENT"
            document_name = "ТТН-1"
            required = $true
        }
    )
    warnings = @()
    manual_review_required = $true
} | ConvertTo-Json -Depth 20 -Compress
[System.IO.File]::WriteAllText(
    $parserAliasFile,
    $parserAliasJson,
    [System.Text.UTF8Encoding]::new($false)
)
Invoke-ApiTest `
    -Id "18-parser-alias" `
    -Description "Parser нормализует TIR_TRANSPORT_DOCUMENT → TTN" `
    -Method "POST" `
    -Path "/api/ai/claims/parser/test" `
    -BodyFile $parserAliasFile `
    -ContentType "text/plain; charset=utf-8" `
    -Validate {
        param($j, $code, $raw)
        $documentType = $j.parsed.attachments[0].document_type
        if ($code -eq 200 -and $documentType -eq "TTN") {
            return @{ passed = $true; message = "alias нормализован в TTN" }
        }
        return @{ passed = $false; message = "alias не нормализован" }
    }

$parserBrokenFile = Join-Path $ResultDir "19-parser-malformed-request.txt"
[System.IO.File]::WriteAllText(
    $parserBrokenFile,
    "{not-valid-json",
    [System.Text.UTF8Encoding]::new($false)
)
Invoke-ApiTest `
    -Id "19-parser-malformed" `
    -Description "Parser отклоняет повреждённый JSON" `
    -Method "POST" `
    -Path "/api/ai/claims/parser/test" `
    -BodyFile $parserBrokenFile `
    -ContentType "text/plain; charset=utf-8" `
    -Validate {
        param($j, $code, $raw)
        if ($code -ge 400) {
            return @{ passed = $true; message = "повреждённый JSON отклонён" }
        }
        return @{ passed = $false; message = "повреждённый JSON принят" }
    }

# Final summary
$passedCount = @($script:Results | Where-Object { $_.passed }).Count
$failedCount = @($script:Results | Where-Object { -not $_.passed }).Count
$totalCount = $script:Results.Count
$totalTokens = ($script:Results | Measure-Object -Property tokens -Sum).Sum
if ($null -eq $totalTokens) { $totalTokens = 0 }

$summaryObject = [pscustomobject]@{
    started_at = $Timestamp
    base_url = $BaseUrl
    document_repeats = $DocumentRepeats
    total = $totalCount
    passed = $passedCount
    failed = $failedCount
    total_llm_tokens = [int]$totalTokens
    results = $script:Results
}

$summaryJsonPath = Join-Path $ResultDir "summary.json"
[System.IO.File]::WriteAllText(
    $summaryJsonPath,
    ($summaryObject | ConvertTo-Json -Depth 20),
    [System.Text.UTF8Encoding]::new($false)
)

$summaryTextPath = Join-Path $ResultDir "summary.txt"
$table = $script:Results |
    Select-Object id, passed, http_code, tokens, description, message |
    Format-Table -AutoSize |
    Out-String -Width 240

$summaryText = @"
CargoTech AI semantic regression suite
Base URL: $BaseUrl
Total: $totalCount
Passed: $passedCount
Failed: $failedCount
Total LLM tokens: $totalTokens

$table
"@

[System.IO.File]::WriteAllText(
    $summaryTextPath,
    $summaryText,
    [System.Text.UTF8Encoding]::new($false)
)

$zipPath = "$ResultDir.zip"
Compress-Archive -Path (Join-Path $ResultDir "*") -DestinationPath $zipPath -Force

Write-Host ""
Write-Host "========================================"
Write-Host "TOTAL:  $totalCount"
Write-Host "PASSED: $passedCount"
Write-Host "FAILED: $failedCount"
Write-Host "TOKENS: $totalTokens"
Write-Host "RESULTS: $ResultDir"
Write-Host "ARCHIVE: $zipPath"
Write-Host "========================================"

if ($failedCount -gt 0) {
    exit 1
}

exit 0
