$ErrorActionPreference = "Stop"

if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    throw "Java не найдена в PATH"
}

if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    throw "Maven не найден в PATH"
}

Write-Host "Java:" -ForegroundColor Cyan
java -version

Write-Host "Maven:" -ForegroundColor Cyan
mvn -version

Write-Host "Запуск clean test..." -ForegroundColor Cyan
mvn -f "$PSScriptRoot\pom.xml" clean test

if ($LASTEXITCODE -ne 0) {
    throw "mvn clean test завершился с ошибкой"
}

Write-Host "Критические unit-тесты пройдены." -ForegroundColor Green
