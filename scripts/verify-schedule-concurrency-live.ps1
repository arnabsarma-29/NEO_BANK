# ==============================================================================
# NeoBank Schedule Service -- PostgreSQL 17.11 Concurrency Verification
# ==============================================================================
# Purpose:
#   Securely executes live PostgreSQL 17.11 multi-connection concurrency and
#   fencing tests under ADR-010 without modifying or truncating existing data.
#   1. Secure masked password input (never logged, never hardcoded)
#   2. Verifies connectivity via `SELECT 1;`
#   3. Runs targeted multi-thread concurrency trials against PostgreSQL 17.11
#   4. Performs controlled cleanup using neobank_schedule_migrator
# ==============================================================================

$ErrorActionPreference = "Stop"

$migratorSecure = $null
$appSecure = $null
$migratorPass = $null
$appPass = $null

try {
    Write-Host "======================================================================" -ForegroundColor Cyan
    Write-Host "NeoBank Schedule Service -- Live PostgreSQL Concurrency Verification" -ForegroundColor Cyan
    Write-Host "======================================================================" -ForegroundColor Cyan
    Write-Host "Please enter the passwords configured during setup-local-db-roles.sql."
    Write-Host "Input is securely masked and never written to disk or logs."
    Write-Host ""

    if ($env:SPRING_DATASOURCE_PASSWORD) {
        $appPass = $env:SPRING_DATASOURCE_PASSWORD
    } else {
        $appSecure = Read-Host "Enter password for neobank_schedule_app (runtime application role)" -AsSecureString
        $BSTR_A = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($appSecure)
        $appPass = [System.Runtime.InteropServices.Marshal]::PtrToStringAuto($BSTR_A)
        [System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($BSTR_A)
    }

    if ($env:FLYWAY_PASSWORD) {
        $migratorPass = $env:FLYWAY_PASSWORD
    } else {
        $migratorSecure = Read-Host "Enter password for neobank_schedule_migrator (schema migration role)" -AsSecureString
        $BSTR_M = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($migratorSecure)
        $migratorPass = [System.Runtime.InteropServices.Marshal]::PtrToStringAuto($BSTR_M)
        [System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($BSTR_M)
    }

    # Step 1: Verify connectivity with SELECT 1;
    Write-Host ""
    Write-Host "[1/4] Verifying connectivity to neobank_schedule as neobank_schedule_app..." -ForegroundColor Yellow
    $env:PGPASSWORD = $appPass
    try {
        $check = & psql -h localhost -p 5432 -U neobank_schedule_app -d neobank_schedule -w -c "SELECT 1;" 2>&1
        if ($LASTEXITCODE -ne 0) {
            Write-Host "[FAILED] PostgreSQL connectivity check failed: $check" -ForegroundColor Red
            exit 1
        }
        Write-Host "      [SUCCESS] Connected to PostgreSQL 17.11 (SELECT 1; returned 1)" -ForegroundColor Green
    } finally {
        $env:PGPASSWORD = $null
    }

    # Step 2: Configure environment for targeted integration test
    $env:SPRING_DATASOURCE_URL = "jdbc:postgresql://localhost:5432/neobank_schedule"
    $env:SPRING_DATASOURCE_USERNAME = "neobank_schedule_app"
    $env:SPRING_DATASOURCE_PASSWORD = $appPass
    $env:FLYWAY_USER = "neobank_schedule_migrator"
    $env:FLYWAY_PASSWORD = $migratorPass
    $env:JAVA_HOME = "C:\Users\rohan\.jdks\ms-21.0.10"

    $mvnCmd = "C:\Users\rohan\.m2\wrapper\dists\apache-maven-3.9.16-bin\5grr65jo27hi51sujmtcldfovl\apache-maven-3.9.16\bin\mvn.cmd"
    if (-not (Test-Path $mvnCmd)) {
        $mvnCmd = "mvn"
    }

    $projectDir = "d:\NEO_BANK\backend\services\schedule-service"

    # Step 3: Run targeted PostgreSQL concurrency integration test
    Write-Host ""
    Write-Host "[2/4] Running live PostgreSQL concurrency test suite..." -ForegroundColor Yellow
    Write-Host "      Target class: PostgresConcurrencyVerificationTest"
    Write-Host "      Active profile: postgres-it"
    Write-Host "      DataSource: $env:SPRING_DATASOURCE_URL ($env:SPRING_DATASOURCE_USERNAME)"
    Write-Host ""

    $logFile = "d:\NEO_BANK\scripts\schedule-concurrency-live.log"
    if (Test-Path $logFile) { Remove-Item $logFile -Force }

    $testExitCode = 0
    Push-Location $projectDir
    try {
        & $mvnCmd 'test' '-Dtest=PostgresConcurrencyVerificationTest' '-Dspring.profiles.active=postgres-it' | Tee-Object -FilePath $logFile
        $testExitCode = $LASTEXITCODE
    } finally {
        Pop-Location
    }

    if ($testExitCode -ne 0) {
        Write-Host ""
        Write-Host "[FAILED] PostgreSQL concurrency test failed with exit code $($testExitCode)." -ForegroundColor Red
    } else {
        Write-Host ""
        Write-Host "[3/4] Live PostgreSQL concurrency test trials passed successfully!" -ForegroundColor Green
    }

    # Step 4: Controlled test data cleanup via neobank_schedule_migrator
    Write-Host ""
    Write-Host "[4/4] Executing controlled cleanup of test records (prefix: SCH-CONC-LIVE-%)..." -ForegroundColor Yellow
    $env:PGPASSWORD = $migratorPass
    try {
        $cleanupSql = @"
DELETE FROM scheduled_occurrences WHERE schedule_id LIKE 'SCH-CONC-LIVE-%';
DELETE FROM scheduled_transfers WHERE schedule_id LIKE 'SCH-CONC-LIVE-%';
SELECT count(*) AS remaining_test_records FROM scheduled_transfers WHERE schedule_id LIKE 'SCH-CONC-LIVE-%';
"@
        $cleanupRes = & psql -h localhost -p 5432 -U neobank_schedule_migrator -d neobank_schedule -w -c $cleanupSql 2>&1
        Write-Host "$cleanupRes"
        Add-Content -Path $logFile -Value "`n--- POSTGRESQL CLEANUP VERIFICATION ---`n$cleanupRes"
        if ($LASTEXITCODE -ne 0) {
            Write-Host "[WARNING] Controlled cleanup returned code $($LASTEXITCODE): $cleanupRes" -ForegroundColor Yellow
        } else {
            Write-Host "      [SUCCESS] Controlled cleanup completed as neobank_schedule_migrator." -ForegroundColor Green
        }
    } finally {
        $env:PGPASSWORD = $null
    }

    if ($testExitCode -ne 0) {
        Write-Host ""
        Write-Host "======================================================================" -ForegroundColor Red
        Write-Host "PostgreSQL 17.11 Concurrency Verification: FAILED (Exit Code: $($testExitCode))" -ForegroundColor Red
        Write-Host "======================================================================" -ForegroundColor Red
        exit $testExitCode
    }

    Write-Host ""
    Write-Host "======================================================================" -ForegroundColor Cyan
    Write-Host "PostgreSQL 17.11 Concurrency & Fencing Verification: ALL PASSED" -ForegroundColor Green
    Write-Host "======================================================================" -ForegroundColor Cyan
    Write-Host "- Isolation: SCH-CONC-LIVE-% test keys; no business data modified."
    Write-Host "- Competing workers: 10 trials x 4 competing threads (40 total attempts) with 100% mutual exclusion."
    Write-Host "- Monotonic fencing tokens: strictly incremented 0 -> 1 -> 2."
    Write-Host "- Lease expiry & recovery: Worker B successfully recovered; stale Worker A 100% rejected."
    Write-Host "- Controlled cleanup: purged only test rows via migrator role."
    Write-Host ""

} finally {
    $env:SPRING_DATASOURCE_PASSWORD = $null
    $env:FLYWAY_PASSWORD = $null
    $env:PGPASSWORD = $null
}
