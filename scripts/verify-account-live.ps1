# ==============================================================================
# NeoBank Account Service -- Live PostgreSQL 17.11 Verification Script
# ==============================================================================
# Purpose:
#   Securely collects role passwords via masked terminal input, runs Account
#   Service under its normal (non-test) profile against PostgreSQL 17.11,
#   and comprehensively verifies migrations, Hibernate schema validation,
#   constraints, and least-privilege runtime permissions.
# ==============================================================================

$ErrorActionPreference = "Stop"

$migratorSecure = $null
$appSecure = $null
$migratorPass = $null
$appPass = $null
$proc = $null

$stdoutLog = "d:\NEO_BANK\scripts\account-live-stdout.log"
$stderrLog = "d:\NEO_BANK\scripts\account-live-stderr.log"

try {
    Write-Host "======================================================================" -ForegroundColor Cyan
    Write-Host "NeoBank Account Service -- Live PostgreSQL 17.11 Verification" -ForegroundColor Cyan
    Write-Host "======================================================================" -ForegroundColor Cyan
    Write-Host "Please enter the passwords configured during setup-local-db-roles.sql."
    Write-Host "Input is securely masked and never written to disk or logs."
    Write-Host ""

    $migratorSecure = Read-Host "Enter password for neobank_account_migrator (schema migration role)" -AsSecureString
    $appSecure = Read-Host "Enter password for neobank_account_app (runtime application role)" -AsSecureString

    $BSTR_M = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($migratorSecure)
    $migratorPass = [System.Runtime.InteropServices.Marshal]::PtrToStringAuto($BSTR_M)
    [System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($BSTR_M)

    $BSTR_A = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($appSecure)
    $appPass = [System.Runtime.InteropServices.Marshal]::PtrToStringAuto($BSTR_A)
    [System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($BSTR_A)

    # Configure environment variables in current process
    $env:SPRING_DATASOURCE_URL = "jdbc:postgresql://localhost:5432/neobank_account"
    $env:SPRING_DATASOURCE_USERNAME = "neobank_account_app"
    $env:SPRING_DATASOURCE_PASSWORD = $appPass
    $env:FLYWAY_USER = "neobank_account_migrator"
    $env:FLYWAY_PASSWORD = $migratorPass
    $env:JAVA_HOME = "C:\Users\rohan\.jdks\ms-21.0.10"

    $jarPath = "d:\NEO_BANK\backend\services\account-service\target\account-service-0.0.1-SNAPSHOT.jar"
    $javaExe = "$env:JAVA_HOME\bin\java.exe"

    if (-not (Test-Path $jarPath)) {
        Write-Host "[ERROR] Jar not found at $jarPath. Please package first." -ForegroundColor Red
        exit 1
    }

    Write-Host ""
    Write-Host "[1/3] Starting Account Service with default (PostgreSQL) profile..." -ForegroundColor Yellow
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

        if (($stdoutContent -and $stdoutContent -match "Started AccountServiceApplication in") -or
            ($stderrContent -and $stderrContent -match "Started AccountServiceApplication in")) {
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
            $failReason = "Account Service process exited prematurely with code $($proc.ExitCode)."
            break
        }
    }

    # Terminate the validation process once startup/migration verification is confirmed
    if ($proc -and (-not $proc.HasExited)) {
        Write-Host "      Terminating Account Service validation instance (PID: $($proc.Id))..."
        try {
            Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
            $proc.WaitForExit(5000)
        } catch {}
        Write-Host "      Account Service process terminated after startup verification."
    }

    if ($started) {
        Write-Host "      [SUCCESS] Account Service started successfully!" -ForegroundColor Green
        Write-Host "      [SUCCESS] Flyway migration executed under neobank_account_migrator." -ForegroundColor Green
        Write-Host "      [SUCCESS] Hibernate schema validation succeeded under neobank_account_app." -ForegroundColor Green
    } else {
        Write-Host ""
        Write-Host "[FAILURE] Account Service live startup failed." -ForegroundColor Red
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
    Write-Host "[2/3] Verifying PostgreSQL 17.11 Schema & Constraints via psql..." -ForegroundColor Yellow

    $psqlExe = "C:\Program Files\PostgreSQL\17\bin\psql.exe"
    $env:PGPASSWORD = $appPass
    $verifySqlPath = "d:\NEO_BANK\scripts\verify-account-schema.sql"

    & $psqlExe -h localhost -p 5432 -U neobank_account_app -d neobank_account -f $verifySqlPath
    $psqlExit = $LASTEXITCODE

    if ($psqlExit -ne 0) {
        Write-Host ""
        Write-Host "[ERROR] PostgreSQL schema and privilege verification query failed with exit code $psqlExit." -ForegroundColor Red
        exit $psqlExit
    }

    Write-Host ""
    Write-Host "[3/3] Live PostgreSQL 17.11 verification completed successfully." -ForegroundColor Green
    Write-Host "======================================================================" -ForegroundColor Cyan
}
finally {
    # Ensure sensitive credentials are cleared from memory
    if ($null -ne $migratorPass) { $migratorPass = $null }
    if ($null -ne $appPass) { $appPass = $null }
    if ($null -ne $migratorSecure) { $migratorSecure.Dispose() }
    if ($null -ne $appSecure) { $appSecure.Dispose() }
    [System.GC]::Collect()

    # Clean up process environment variables
    Remove-Item env:SPRING_DATASOURCE_URL -ErrorAction SilentlyContinue
    Remove-Item env:SPRING_DATASOURCE_USERNAME -ErrorAction SilentlyContinue
    Remove-Item env:SPRING_DATASOURCE_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item env:FLYWAY_USER -ErrorAction SilentlyContinue
    Remove-Item env:FLYWAY_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item env:PGPASSWORD -ErrorAction SilentlyContinue
}
