$ErrorActionPreference = "Stop"

[Console]::InputEncoding = [System.Text.UTF8Encoding]::new()
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new()
$OutputEncoding = [System.Text.UTF8Encoding]::new()

$envFile = Join-Path (Split-Path $PSScriptRoot -Parent) ".env"

if (-not (Test-Path $envFile)) {
    throw "Файл .env не найден: $envFile"
}

Get-Content $envFile -Encoding UTF8 |
    Where-Object {
        $_.Trim() -ne "" -and
        -not $_.TrimStart().StartsWith("#")
    } |
    ForEach-Object {
        $name, $value = $_ -split "=", 2

        if ([string]::IsNullOrWhiteSpace($name)) {
            return
        }

        $name = $name.Trim()
        $value = $value.Trim().Trim('"').Trim("'")

        [Environment]::SetEnvironmentVariable(
            $name,
            $value,
            "Process"
        )
    }

$env:SPRING_DOCKER_COMPOSE_ENABLED = "false"

Write-Host "Переменные из .env загружены."
Write-Host "Запуск CargoTech AI..."

mvn -f "$PSScriptRoot\pom.xml" spring-boot:run

exit $LASTEXITCODE