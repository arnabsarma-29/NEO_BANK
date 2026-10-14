# ==============================================================================
# NeoBank Schedule Service -- Live PostgreSQL 17.11 Verification Script
# ==============================================================================
# Purpose:
#   Securely collects role passwords via masked terminal input, runs Schedule
#   Service under its normal (PostgreSQL) profile against PostgreSQL 17.11,
#   and comprehensively verifies migrations, Hibernate schema validation,
#   constraints, and least-privilege runtime permissions.
# ==============================================================================

$ErrorActionPreference = "Stop"

$migratorSecure = $null
$appSecure = $null
$migratorPass = $null
$appPass = $null
$proc = $null

$stdoutLog = "d:\NEO_BANK\scripts\schedule-live-stdout.log"
$stderrLog = "d:\NEO_BANK\scripts\schedule-live-stderr.log"

try {
    Write-Host "======================================================================" -ForegroundColor Cyan
    Write-Host "NeoBank Schedule Service -- Live PostgreSQL 17.11 Verification" -ForegroundColor Cyan
    Write-Host "======================================================================" -ForegroundColor Cyan
    Write-Host "Please enter the passwords configured during setup-local-db-roles.sql."
    Write-Host "Input is securely masked and never written to disk or logs."
    Write-Host ""

    if ($env:FLYWAY_PASSWORD) {
        $migratorPass = $env:FLYWAY_PASSWORD
    } else {
        $migratorSecure = Read-Host "Enter password for neobank_schedule_migrator (schema migration role)" -AsSecureString
        $BSTR_M = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($migratorSecure)
        $migratorPass = [System.Runtime.InteropServices.Marshal]::PtrToStringAuto($BSTR_M)
        [System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($BSTR_M)
    }

    if ($env:SPRING_DATASOURCE_PASSWORD) {
        $appPass = $env:SPRING_DATASOURCE_PASSWORD
    } else {
        $appSecure = Read-Host "Enter password for neobank_schedule_app (runtime application role)" -AsSecureString
        $BSTR_A = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($appSecure)
        $appPass = [System.Runtime.InteropServices.Marshal]::PtrToStringAuto($BSTR_A)
        [System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($BSTR_A)
    }

    # Configure environment variables in current process
    $env:SPRING_DATASOURCE_URL = "jdbc:postgresql://localhost:5432/neobank_schedule"
    $env:SPRING_DATASOURCE_USERNAME = "neobank_schedule_app"
    $env:SPRING_DATASOURCE_PASSWORD = $appPass
    $env:FLYWAY_USER = "neobank_schedule_migrator"
    $env:FLYWAY_PASSWORD = $migratorPass
    $env:JAVA_HOME = "C:\Users\rohan\.jdks\ms-21.0.10"

    $jarPath = "d:\NEO_BANK\backend\services\schedule-service\target\schedule-service-0.0.1-SNAPSHOT.jar"
    $javaExe = "$env:JAVA_HOME\bin\java.exe"

    if (-not (Test-Path $jarPath)) {
        Write-Host "[ERROR] Jar not found at $jarPath. Please package first." -ForegroundColor Red
        exit 1
    }

    Write-Host ""
    Write-Host "[1/3] Starting Schedule Service with default (PostgreSQL) profile..." -ForegroundColor Yellow
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

        if (($stdoutContent -and $stdoutContent -match "Started ScheduleServiceApplication in") -or
            ($stderrContent -and $stderrContent -match "Started ScheduleServiceApplication in")) {
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
            $failReason = "Process terminated unexpectedly with exit code $($proc.ExitCode)."
            break
        }
    }

    if ($failed -or (-not $started)) {
        Write-Host "[FAILED] Schedule Service startup did not succeed." -ForegroundColor Red
        if ($failReason) { Write-Host "Reason: $failReason" -ForegroundColor Red }
        Write-Host ""
        Write-Host "--- STDOUT LOG ---" -ForegroundColor DarkGray
        if (Test-Path $stdoutLog) { Get-Content $stdoutLog | Select-Object -Last 40 }
        Write-Host ""
        Write-Host "--- STDERR LOG ---" -ForegroundColor DarkGray
        if (Test-Path $stderrLog) { Get-Content $stderrLog | Select-Object -Last 40 }
        exit 1
    }

    Write-Host "[SUCCESS] Schedule Service started successfully!" -ForegroundColor Green
    Write-Host "          - Flyway executed V1__init_schedule_schema.sql as $env:FLYWAY_USER"
    Write-Host "          - Hibernate schema validation passed for runtime user $env:SPRING_DATASOURCE_USERNAME"
    Write-Host ""

    # Stop the running application process cleanly
    Write-Host "[2/3] Stopping background application process..." -ForegroundColor Yellow
    if ($proc -and -not $proc.HasExited) {
        Stop-Process -Id $proc.Id -Force
        Start-Sleep -Seconds 1
    }
    Write-Host "[SUCCESS] Application process stopped." -ForegroundColor Green
    Write-Host ""

    # Run SQL verification using psql
    Write-Host "[3/3] Running SQL schema and runtime privileges verification..." -ForegroundColor Yellow
    $env:PGPASSWORD = $migratorPass
    try {
        & psql -h localhost -p 5432 -U neobank_schedule_migrator -d neobank_schedule -f "d:\NEO_BANK\scripts\verify-schedule-schema.sql"
        if ($LASTEXITCODE -ne 0) {
            Write-Host "[FAILED] psql returned exit code $LASTEXITCODE during schema verification." -ForegroundColor Red
            exit $LASTEXITCODE
        }
    } finally {
        $env:PGPASSWORD = $null
    }

    Write-Host ""
    Write-Host "======================================================================" -ForegroundColor Green
    Write-Host "NeoBank Schedule Service -- Live PostgreSQL 17.11 Verification PASSED" -ForegroundColor Green
    Write-Host "======================================================================" -ForegroundColor Green

} finally {
    # Ensure background process is terminated
    if ($proc -and -not $proc.HasExited) {
        try { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue } catch {}
    }

    # Clean up sensitive variables
    $migratorPass = $null
    $appPass = $null
    $env:SPRING_DATASOURCE_PASSWORD = $null
    $env:FLYWAY_PASSWORD = $null
    $env:PGPASSWORD = $null
    [System.GC]::Collect()
}
