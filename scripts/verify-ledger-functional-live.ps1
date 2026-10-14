# ==============================================================================
# NeoBank Ledger Service -- PostgreSQL 17.11 Functional & Concurrency Verification
# ==============================================================================
# Purpose:
#   Securely executes live PostgreSQL 17.11 functional and concurrency trials
#   under ADR-008, ADR-018, ADR-021, and ADR-024 without modifying existing data.
#   1. Secure masked password input (never logged, never hardcoded)
#   2. Generates a unique GUID/UUID-based verification-run ID ($runId)
#   3. Verifies connectivity via `SELECT 1;`
#   4. Runs targeted multi-thread concurrency & functional tests tagged with $runId
#   5. Executes single-session temporary table capture, foreign-key-safe deletion,
#      residual verification, and temporary table cleanup within the same session.
# ==============================================================================

$ErrorActionPreference = "Stop"

function Get-MaskedSecret([string]$promptMessage, [string]$userRole) {
    $sec = Read-Host $promptMessage -AsSecureString
    $bstr = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec)
    $plain = [System.Runtime.InteropServices.Marshal]::PtrToStringAuto($bstr)
    [System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
    return $plain
}

$migratorPass = $null
$appPass = $null

$functionalLog = "d:\NEO_BANK\scripts\ledger-functional-live.log"

# Generate a unique GUID/UUID-based verification-run ID for this exact invocation
$runId = [System.Guid]::NewGuid().ToString("D").ToUpper()

try {
    Write-Host "======================================================================" -ForegroundColor Cyan
    Write-Host "NeoBank Ledger Service -- Live PostgreSQL Functional Verification" -ForegroundColor Cyan
    Write-Host "Verification Run GUID: $runId" -ForegroundColor Cyan
    Write-Host "======================================================================" -ForegroundColor Cyan
    Write-Host "Please enter the passwords configured during setup-local-db-roles.sql."
    Write-Host "Input is securely masked and never written to disk or logs."
    Write-Host ""

    if ($env:SPRING_DATASOURCE_PASSWORD) {
        $appPass = $env:SPRING_DATASOURCE_PASSWORD
    } else {
        $appPass = Get-MaskedSecret "Enter password for neobank_ledger_app (runtime application role)" "neobank_ledger_app"
    }

    if ($env:FLYWAY_PASSWORD) {
        $migratorPass = $env:FLYWAY_PASSWORD
    } else {
        $migratorPass = Get-MaskedSecret "Enter password for neobank_ledger_migrator (schema migration role)" "neobank_ledger_migrator"
    }

    $psqlExe = "C:\Program Files\PostgreSQL\17\bin\psql.exe"
    if (-not (Test-Path $psqlExe)) {
        $psqlExe = "psql"
    }

    # Step 1: Verify connectivity with SELECT 1;
    Write-Host ""
    Write-Host "[1/3] Verifying connectivity to neobank_ledger as neobank_ledger_app..." -ForegroundColor Yellow
    $env:PGPASSWORD = $appPass
    try {
        $check = & $psqlExe -h localhost -p 5432 -U neobank_ledger_app -d neobank_ledger -w -c "SELECT 1;" 2>&1
        if ($LASTEXITCODE -ne 0) {
            Write-Host "[FAILED] PostgreSQL connectivity check failed: $check" -ForegroundColor Red
            exit 1
        }
        Write-Host "      [SUCCESS] Connected to PostgreSQL 17.11 (SELECT 1; returned 1)" -ForegroundColor Green
    } finally {
        $env:PGPASSWORD = $null
    }

    # Step 2: Configure environment for targeted integration test
    $env:SPRING_DATASOURCE_URL = "jdbc:postgresql://localhost:5432/neobank_ledger"
    $env:SPRING_DATASOURCE_USERNAME = "neobank_ledger_app"
    $env:SPRING_DATASOURCE_PASSWORD = $appPass
    $env:FLYWAY_USER = "neobank_ledger_migrator"
    $env:FLYWAY_PASSWORD = $migratorPass
    $env:JAVA_HOME = "C:\Users\rohan\AppData\Local\Programs\Microsoft\jdk-17.0.20.101-hotspot"
    $env:LEDGER_VERIFICATION_RUN_ID = $runId

    $mvnCmd = "C:\Users\rohan\.m2\wrapper\dists\apache-maven-3.9.16-bin\5grr65jo27hi51sujmtcldfovl\apache-maven-3.9.16\bin\mvn.cmd"
    if (-not (Test-Path $mvnCmd)) {
        $mvnCmd = "mvn"
    }

    $projectDir = "d:\NEO_BANK\backend\services\ledger-service"

    # Step 3: Run targeted PostgreSQL concurrency integration test
    Write-Host ""
    Write-Host "[2/3] Running live PostgreSQL functional & concurrency test suite..." -ForegroundColor Yellow
    Write-Host "      Target class: PostgresLedgerFunctionalVerificationTest"
    Write-Host "      Active profile: postgres-it"
    Write-Host "      Run GUID: $runId"
    Write-Host "      DataSource: $env:SPRING_DATASOURCE_URL ($env:SPRING_DATASOURCE_USERNAME)"
    Write-Host ""

    if (Test-Path $functionalLog) { Remove-Item $functionalLog -Force }

    $testExitCode = 0
    $cleanupFailed = $false
    try {
        Push-Location $projectDir
        try {
            & $mvnCmd 'test' '-Dtest=PostgresLedgerFunctionalVerificationTest' '-Dspring.profiles.active=postgres-it' "-Dledger.verification.runId=$runId" | Tee-Object -FilePath $functionalLog
            $testExitCode = $LASTEXITCODE
        } finally {
            Pop-Location
        }
    }
    finally {
        # Guaranteed cleanup block: executed in one single PostgreSQL session
        Write-Host ""
        Write-Host "[3/3] Executing strictly scoped single-session cleanup for Run GUID: $runId..." -ForegroundColor Yellow
        $env:PGPASSWORD = $migratorPass
        try {
            $cleanupAndVerifySql = @"
-- Step 0: Capture transaction IDs created by this run before any deletion
CREATE TEMPORARY TABLE _tmp_run_tx_ids AS 
SELECT id AS tx_id 
FROM ledger_transactions 
WHERE transfer_id LIKE 'TRF-$runId-%';

-- Step 1: Delete outbox events created by this run (matching transaction or account aggregate_ids, or payload)
DELETE FROM outbox_events 
WHERE aggregate_id LIKE 'ACC-$runId-%' 
   OR aggregate_id LIKE 'TX-$runId-%' 
   OR aggregate_id IN (SELECT tx_id FROM _tmp_run_tx_ids) 
   OR payload LIKE '%$runId%';

-- Step 2: Delete ledger entries created by this run (must delete BEFORE ledger_transactions due to FK)
DELETE FROM ledger_entries 
WHERE account_id LIKE 'ACC-$runId-%' 
   OR transaction_id IN (SELECT tx_id FROM _tmp_run_tx_ids);

-- Step 3: Delete ledger transactions created by this run
DELETE FROM ledger_transactions 
WHERE transfer_id LIKE 'TRF-$runId-%';

-- Step 4: Delete ledger executions created by this run
DELETE FROM ledger_executions 
WHERE transfer_id LIKE 'TRF-$runId-%';

-- Step 5: Delete dedicated test accounts created by this run
DELETE FROM ledger_accounts 
WHERE account_id LIKE 'ACC-$runId-%';

-- Step 6: Verify that zero records belonging to this run remain (1:1 matching predicates)
SELECT 
    (SELECT count(*) FROM outbox_events 
     WHERE aggregate_id LIKE 'ACC-$runId-%' 
        OR aggregate_id LIKE 'TX-$runId-%' 
        OR aggregate_id IN (SELECT tx_id FROM _tmp_run_tx_ids) 
        OR payload LIKE '%$runId%') AS remaining_outbox,
    (SELECT count(*) FROM ledger_entries 
     WHERE account_id LIKE 'ACC-$runId-%' 
        OR transaction_id IN (SELECT tx_id FROM _tmp_run_tx_ids)) AS remaining_entries,
    (SELECT count(*) FROM ledger_transactions 
     WHERE transfer_id LIKE 'TRF-$runId-%') AS remaining_transactions,
    (SELECT count(*) FROM ledger_executions 
     WHERE transfer_id LIKE 'TRF-$runId-%') AS remaining_executions,
    (SELECT count(*) FROM ledger_accounts 
     WHERE account_id LIKE 'ACC-$runId-%') AS remaining_accounts;

-- Step 7: Drop temporary table
DROP TABLE IF EXISTS _tmp_run_tx_ids;
"@
            $cleanupRes = & $psqlExe -h localhost -p 5432 -U neobank_ledger_migrator -d neobank_ledger -w -c $cleanupAndVerifySql 2>&1
            Write-Host "$cleanupRes"
            Add-Content -Path $functionalLog -Value "`n--- POSTGRESQL CLEANUP & RESIDUAL VERIFICATION (Run GUID: $runId) ---`n$cleanupRes"
            
            # Parse residual counts from output table
            $dataRow = ($cleanupRes | Where-Object { $_ -match '^\s*\d+\s*\|\s*\d+\s*\|\s*\d+\s*\|\s*\d+\s*\|\s*\d+\s*$' })
            if ($dataRow) {
                $counts = ($dataRow -split '\|').Trim()
                $remOutbox = [int]$counts[0]
                $remEntries = [int]$counts[1]
                $remTx = [int]$counts[2]
                $remExec = [int]$counts[3]
                $remAcc = [int]$counts[4]

                if ($remOutbox -eq 0 -and $remEntries -eq 0 -and $remTx -eq 0 -and $remExec -eq 0 -and $remAcc -eq 0) {
                    Write-Host "      [SUCCESS] Cleanup verified: 0 residual records across all tables for run $runId (Outbox: 0, Entries: 0, Tx: 0, Exec: 0, Accounts: 0)." -ForegroundColor Green
                } else {
                    Write-Host "      [ERROR] Non-zero residual records detected for run $runId! (Outbox: $remOutbox, Entries: $remEntries, Transactions: $remTx, Executions: $remExec, Accounts: $remAcc)" -ForegroundColor Red
                    $cleanupFailed = $true
                }
            } else {
                Write-Host "      [WARNING] Could not parse residual count verification table from psql output." -ForegroundColor Yellow
                $cleanupFailed = $true
            }
        } finally {
            $env:PGPASSWORD = $null
        }
    }

    if ($testExitCode -ne 0) {
        Write-Host ""
        Write-Host "======================================================================" -ForegroundColor Red
        Write-Host "PostgreSQL 17.11 Functional Verification: FAILED (Exit Code: $($testExitCode))" -ForegroundColor Red
        Write-Host "======================================================================" -ForegroundColor Red
        exit $testExitCode
    }

    if ($cleanupFailed) {
        Write-Host ""
        Write-Host "======================================================================" -ForegroundColor Red
        Write-Host "PostgreSQL 17.11 Cleanup Verification: FAILED (Residual records detected)" -ForegroundColor Red
        Write-Host "======================================================================" -ForegroundColor Red
        exit 2
    }

    Write-Host ""
    Write-Host "======================================================================" -ForegroundColor Cyan
    Write-Host "PostgreSQL 17.11 Functional & Concurrency Verification: ALL PASSED" -ForegroundColor Green
    Write-Host "======================================================================" -ForegroundColor Cyan
    Write-Host "- Isolation: Scoped strictly to run GUID $runId; no business data or prior runs modified."
    Write-Host "- Atomic balance mutations & audit entries verified on PostgreSQL 17.11."
    Write-Host "- Terminal business rejection durability verified without balance mutation."
    Write-Host "- Replay idempotency & payload conflict detection (HTTP 409) verified."
    Write-Host "- Concurrent identical transfers: exactly 1 financial mutation; loser rolled back & replayed."
    Write-Host "- Concurrent conflicting transfers: mutual exclusion enforced; conflict rejected."
    Write-Host "- Authoritative outcome retrieval: COMMITTED and REJECTED recovered."
    Write-Host "- Controlled cleanup: verified 0 residual records for run GUID $runId via migrator role."
    Write-Host ""
}
finally {
    $env:SPRING_DATASOURCE_PASSWORD = $null
    $env:FLYWAY_PASSWORD = $null
    $env:PGPASSWORD = $null
    $env:LEDGER_VERIFICATION_RUN_ID = $null
    [System.GC]::Collect()
}
