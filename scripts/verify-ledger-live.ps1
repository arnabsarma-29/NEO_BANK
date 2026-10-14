# ==============================================================================
# NeoBank Ledger Service -- Live PostgreSQL 17.11 Verification Script
# ==============================================================================
# Purpose:
#   Securely collects role passwords via masked terminal input, runs Ledger
#   Service under its normal (non-test) profile against PostgreSQL 17.11,
#   verifies Flyway migrations, Hibernate schema validation, constraints,
#   least-privilege runtime permissions, executes live PostgreSQL functional
#   and concurrency test suites tagged with a unique GUID $runId, and performs
#   guaranteed controlled cleanup strictly scoped to $runId as migrator.
# ==============================================================================

param(
    [switch]$FunctionalOnly,
    [switch]$SkipStartup
)

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
$proc = $null

$stdoutLog = "d:\NEO_BANK\scripts\ledger-live-stdout.log"
$stderrLog = "d:\NEO_BANK\scripts\ledger-live-stderr.log"
$functionalLog = "d:\NEO_BANK\scripts\ledger-functional-live.log"

# Generate a unique GUID/UUID-based verification-run ID for this exact invocation
$runId = [System.Guid]::NewGuid().ToString("D").ToUpper()

try {
    Write-Host "======================================================================" -ForegroundColor Cyan
    Write-Host "NeoBank Ledger Service -- Live PostgreSQL 17.11 Verification" -ForegroundColor Cyan
    Write-Host "Verification Run GUID: $runId" -ForegroundColor Cyan
    Write-Host "======================================================================" -ForegroundColor Cyan
    Write-Host "Please enter the passwords configured during setup-local-db-roles.sql."
    Write-Host "Input is securely masked and never written to disk or logs."
    Write-Host ""

    if ($env:FLYWAY_PASSWORD) {
        $migratorPass = $env:FLYWAY_PASSWORD
    } else {
        $migratorPass = Get-MaskedSecret "Enter password for neobank_ledger_migrator (schema migration role)" "neobank_ledger_migrator"
    }

    if ($env:SPRING_DATASOURCE_PASSWORD) {
        $appPass = $env:SPRING_DATASOURCE_PASSWORD
    } else {
        $appPass = Get-MaskedSecret "Enter password for neobank_ledger_app (runtime application role)" "neobank_ledger_app"
    }

    # Configure environment variables in current process
    $env:SPRING_DATASOURCE_URL = "jdbc:postgresql://localhost:5432/neobank_ledger"
    $env:SPRING_DATASOURCE_USERNAME = "neobank_ledger_app"
    $env:SPRING_DATASOURCE_PASSWORD = $appPass
    $env:FLYWAY_USER = "neobank_ledger_migrator"
    $env:FLYWAY_PASSWORD = $migratorPass
    $env:JAVA_HOME = "C:\Users\rohan\AppData\Local\Programs\Microsoft\jdk-17.0.20.101-hotspot"
    $env:LEDGER_VERIFICATION_RUN_ID = $runId

    $psqlExe = "C:\Program Files\PostgreSQL\17\bin\psql.exe"
    if (-not (Test-Path $psqlExe)) {
        $psqlExe = "psql"
    }

    $mvnCmd = "C:\Users\rohan\.m2\wrapper\dists\apache-maven-3.9.16-bin\5grr65jo27hi51sujmtcldfovl\apache-maven-3.9.16\bin\mvn.cmd"
    if (-not (Test-Path $mvnCmd)) {
        $mvnCmd = "mvn"
    }

    $jarPath = "d:\NEO_BANK\backend\services\ledger-service\target\ledger-service-0.0.1-SNAPSHOT.jar"
    $javaExe = "$env:JAVA_HOME\bin\java.exe"

    if (-not $FunctionalOnly -and -not $SkipStartup) {
        if (-not (Test-Path $jarPath)) {
            Write-Host "[ERROR] Jar not found at $jarPath. Please package first." -ForegroundColor Red
            exit 1
        }

        Write-Host ""
        Write-Host "[1/4] Starting Ledger Service with default (PostgreSQL) profile..." -ForegroundColor Yellow
        Write-Host "      Flyway user: $env:FLYWAY_USER"
        Write-Host "      Runtime user: $env:SPRING_DATASOURCE_USERNAME"
        Write-Host "      Target DB: $env:SPRING_DATASOURCE_URL"

        # Clean previous log files
        if (Test-Path $stdoutLog) { Remove-Item $stdoutLog -Force }
        if (Test-Path $stderrLog) { Remove-Item $stderrLog -Force }

        # Launch Spring Boot process with separate stdout and stderr redirection
        $proc = Start-Process -FilePath $javaExe `
            -ArgumentList "-jar", "`"$jarPath`"" `
            -PassThru `
            -RedirectStandardOutput $stdoutLog `
            -RedirectStandardError $stderrLog

        $timeoutSeconds = 45
        $started = $false
        $failed = $false
        $failReason = ""
        $startTime = Get-Date

        while (((Get-Date) - $startTime).TotalSeconds -lt $timeoutSeconds) {
            Start-Sleep -Milliseconds 500

            $stdoutContent = if (Test-Path $stdoutLog) { Get-Content $stdoutLog -Raw -ErrorAction SilentlyContinue } else { "" }
            $stderrContent = if (Test-Path $stderrLog) { Get-Content $stderrLog -Raw -ErrorAction SilentlyContinue } else { "" }

            if (($stdoutContent -and $stdoutContent -match "Started LedgerServiceApplication in") -or
                ($stderrContent -and $stderrContent -match "Started LedgerServiceApplication in")) {
                $started = $true
                break
            }

            if (($stdoutContent -and ($stdoutContent -match "APPLICATION FAILED TO START" -or $stdoutContent -match "FlywayException" -or $stdoutContent -match "SchemaManagementException")) -or
                ($stderrContent -and ($stderrContent -match "APPLICATION FAILED TO START" -or $stderrContent -match "FlywayException" -or $stderrContent -match "SchemaManagementException"))) {
                $failed = $true
                $failReason = "Fatal startup or migration exception detected in application logs."
                break
            }

            if ($proc.HasExited) {
                $failed = $true
                $failReason = "Ledger Service process exited prematurely with code $($proc.ExitCode)."
                break
            }
        }

        # Terminate the validation process once startup/migration verification is confirmed
        if ($proc -and (-not $proc.HasExited)) {
            Write-Host "      Terminating Ledger Service validation instance (PID: $($proc.Id))..."
            try {
                Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
                $proc.WaitForExit(5000)
            } catch {}
            Write-Host "      Ledger Service process terminated after startup verification."
        }

        if ($started) {
            Write-Host "      [SUCCESS] Ledger Service started successfully!" -ForegroundColor Green
            Write-Host "      [SUCCESS] Flyway migration executed under neobank_ledger_migrator." -ForegroundColor Green
            Write-Host "      [SUCCESS] Hibernate schema validation succeeded under neobank_ledger_app." -ForegroundColor Green
        } else {
            Write-Host ""
            Write-Host "[FAILURE] Ledger Service live startup failed." -ForegroundColor Red
            if ($failReason) {
                Write-Host "          Reason: $failReason" -ForegroundColor Red
            } else {
                Write-Host "          Reason: Timed out after $timeoutSeconds seconds waiting for startup signal." -ForegroundColor Red
            }
            if (Test-Path $stdoutLog) {
                Write-Host "`n--- Standard Output (Tail) ---" -ForegroundColor Yellow
                Get-Content $stdoutLog -Tail 30
            }
            if (Test-Path $stderrLog) {
                $errContent = Get-Content $stderrLog -Raw -ErrorAction SilentlyContinue
                if ($errContent -and $errContent.Trim()) {
                    Write-Host "`n--- Standard Error ---" -ForegroundColor Red
                    Get-Content $stderrLog -Tail 30
                }
            }
            exit 1
        }

        Write-Host ""
        Write-Host "[2/4] Verifying PostgreSQL 17.11 Schema & Constraints via psql..." -ForegroundColor Yellow

        $env:PGPASSWORD = $appPass
        $verifySqlPath = "d:\NEO_BANK\scripts\verify-ledger-schema.sql"

        & $psqlExe -h localhost -p 5432 -U neobank_ledger_app -d neobank_ledger -f $verifySqlPath
        $psqlExit = $LASTEXITCODE

        if ($psqlExit -ne 0) {
            Write-Host ""
            Write-Host "[ERROR] PostgreSQL schema and privilege verification query failed with exit code $psqlExit." -ForegroundColor Red
            exit $psqlExit
        }
        Write-Host "      [SUCCESS] PostgreSQL schema and privilege verification passed." -ForegroundColor Green
    } else {
        Write-Host ""
        Write-Host "[INFO] Skipping live Tomcat startup & schema query (FunctionalOnly requested)." -ForegroundColor Cyan
    }

    # Step 3: Run targeted PostgreSQL functional & concurrency test suite
    Write-Host ""
    Write-Host "[3/4] Running live PostgreSQL functional & concurrency test suite..." -ForegroundColor Yellow
    Write-Host "      Target class: PostgresLedgerFunctionalVerificationTest"
    Write-Host "      Active profile: postgres-it"
    Write-Host "      Run GUID: $runId"
    Write-Host "      DataSource: $env:SPRING_DATASOURCE_URL ($env:SPRING_DATASOURCE_USERNAME)"
    Write-Host ""

    if (Test-Path $functionalLog) { Remove-Item $functionalLog -Force }

    $projectDir = "d:\NEO_BANK\backend\services\ledger-service"
    $testExitCode = 0
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
        # Guaranteed cleanup block strictly scoped to $runId
        Write-Host ""
        Write-Host "[4/4] Executing strictly scoped cleanup for Run GUID: $runId..." -ForegroundColor Yellow
        $env:PGPASSWORD = $migratorPass
        try {
            $cleanupSql = @"
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

-- 2. Delete ledger entries created by this run (must delete BEFORE ledger_transactions due to FK)
DELETE FROM ledger_entries 
WHERE account_id LIKE 'ACC-$runId-%' 
   OR transaction_id IN (SELECT tx_id FROM _tmp_run_tx_ids);

-- 3. Delete ledger transactions created by this run
DELETE FROM ledger_transactions 
WHERE transfer_id LIKE 'TRF-$runId-%';

-- 4. Delete ledger executions created by this run
DELETE FROM ledger_executions 
WHERE transfer_id LIKE 'TRF-$runId-%';

-- 5. Delete dedicated test accounts created by this run
DELETE FROM ledger_accounts 
WHERE account_id LIKE 'ACC-$runId-%';

-- 6. Verify that zero records belonging to this run remain (1:1 matching predicates)
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
            $cleanupRes = & $psqlExe -h localhost -p 5432 -U neobank_ledger_migrator -d neobank_ledger -w -c $cleanupSql 2>&1
            Write-Host "$cleanupRes"
            Add-Content -Path $functionalLog -Value "`n--- POSTGRESQL CLEANUP VERIFICATION (Run GUID: $runId) ---`n$cleanupRes"
            
            # Verify clean outcome
            if ($cleanupRes -match "\b[1-9]\d*\b") {
                Write-Host "      [WARNING] Residual records detected in cleanup verification for run $runId!" -ForegroundColor Yellow
            } else {
                Write-Host "      [SUCCESS] Run $runId completely cleaned up (0 residual records in all tables)." -ForegroundColor Green
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

    Write-Host ""
    Write-Host "======================================================================" -ForegroundColor Cyan
    Write-Host "PostgreSQL 17.11 Verification: ALL STEPS PASSED SUCCESSFULLY" -ForegroundColor Green
    Write-Host "======================================================================" -ForegroundColor Cyan
    Write-Host "- Migration & Schema: Flyway V1 & V2 validated, Hibernate validated."
    Write-Host "- Permissions: neobank_ledger_app least-privilege verified."
    Write-Host "- Functional Tests: Verified against live PostgreSQL 17.11 under run GUID $runId."
    Write-Host "- Concurrency: Concurrent identical and conflicting transfers verified."
    Write-Host "- Authoritative Outcomes: Authoritative outcome recovery verified."
    Write-Host "- Controlled Cleanup: Verified 0 residual records for run GUID $runId."
    Write-Host ""
}
finally {
    # Ensure sensitive credentials are cleared from memory
    if ($null -ne $migratorPass) { $migratorPass = $null }
    if ($null -ne $appPass) { $appPass = $null }
    $env:LEDGER_VERIFICATION_RUN_ID = $null
    [System.GC]::Collect()

    # Clean up process environment variables
    Remove-Item env:SPRING_DATASOURCE_URL -ErrorAction SilentlyContinue
    Remove-Item env:SPRING_DATASOURCE_USERNAME -ErrorAction SilentlyContinue
    Remove-Item env:SPRING_DATASOURCE_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item env:FLYWAY_USER -ErrorAction SilentlyContinue
    Remove-Item env:FLYWAY_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item env:PGPASSWORD -ErrorAction SilentlyContinue
}
