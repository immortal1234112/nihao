@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"
set "PATH=%PATH%;C:\Users\immortal\tools\apache-maven-3.9.16\bin"

set MYSQL_PORT=3307
set MYSQL_PASS=123456
set REQUIRE_API_KEY=false
REM Strip trailing backslash from path to avoid escaping issues in cmd /k
set "PROJ_DIR=%~dp0"
set "PROJ_DIR=%PROJ_DIR:~0,-1%"

echo ================================================================
echo    AI Agent Platform - Start
echo ================================================================
echo.

echo [0/3] Cleaning up old processes...
REM Kill all Java processes related to spring-boot:run (catches any port)
for /f "tokens=2" %%A in ('tasklist /fi "imagename eq java.exe" /fo list 2^>nul ^| findstr "PID:"') do (
    wmic process where "ProcessId=%%A" get CommandLine 2^>nul | findstr "spring-boot:run" >nul 2>&1
    if not errorlevel 1 (
        echo   Stopping spring-boot process PID %%A
        taskkill /PID %%A /F >nul 2>&1
    )
)
REM Also kill any processes on known service ports
for %%P in (8080 8081 8082 8083 8084 8085 8086 18081 18082 18083 18084 18085 18086 3000) do (
    for /f "tokens=5" %%A in ('netstat -ano 2^>nul ^| findstr ":%%P " ^| findstr "LISTENING"') do (
        if "%%A" neq "0" (
            echo   Stopping PID %%A on port %%P
            taskkill /PID %%A /F >nul 2>&1
        )
    )
)
timeout /t 3 /nobreak >nul
echo   Done.
echo.

echo [1/3] Starting Docker infrastructure...
docker compose up -d
if errorlevel 1 (
    echo Docker start failed! Is Docker Desktop running?
    pause
    exit /b 1
)
echo Wait MySQL...
set MYSQL_WAIT=0
:wait_mysql
timeout /t 2 /nobreak >nul
set /a MYSQL_WAIT=!MYSQL_WAIT!+2
docker exec mysql mysqladmin ping -uroot -p%MYSQL_PASS% --silent >nul 2>&1
if errorlevel 1 (
    if !MYSQL_WAIT! lss 60 goto wait_mysql
    echo MySQL start timeout.
    pause
    exit /b 1
)
echo MySQL ready.
echo.

echo [2/3] Building project...
call mvn install -DskipTests -q
if errorlevel 1 (
    echo Build failed!
    pause
    exit /b 1
)
echo Build OK.
echo.

echo [3/3] Starting microservices...

start "Gateway-8080" cmd /k "cd /d !PROJ_DIR! && set MYSQL_PORT=%MYSQL_PORT% && set MYSQL_PASS=%MYSQL_PASS% && set REQUIRE_API_KEY=%REQUIRE_API_KEY% && mvn spring-boot:run -pl gateway"
timeout /t 5 /nobreak >nul

start "UserService-8081" cmd /k "cd /d !PROJ_DIR! && set MYSQL_PORT=%MYSQL_PORT% && set MYSQL_PASS=%MYSQL_PASS% && set REQUIRE_API_KEY=%REQUIRE_API_KEY% && mvn spring-boot:run -pl user-service"
start "StorageService-8084" cmd /k "cd /d !PROJ_DIR! && set MYSQL_PORT=%MYSQL_PORT% && set MYSQL_PASS=%MYSQL_PASS% && set REQUIRE_API_KEY=%REQUIRE_API_KEY% && mvn spring-boot:run -pl storage-service"
timeout /t 3 /nobreak >nul

start "AgentCore-8082" cmd /k "cd /d !PROJ_DIR! && set MYSQL_PORT=%MYSQL_PORT% && set MYSQL_PASS=%MYSQL_PASS% && set REQUIRE_API_KEY=%REQUIRE_API_KEY% && mvn spring-boot:run -pl agent-core"
start "SecurityService-8083" cmd /k "cd /d !PROJ_DIR! && set MYSQL_PORT=%MYSQL_PORT% && set MYSQL_PASS=%MYSQL_PASS% && set REQUIRE_API_KEY=%REQUIRE_API_KEY% && mvn spring-boot:run -pl security-service"
timeout /t 3 /nobreak >nul

start "ImageEngine-8085" cmd /k "cd /d !PROJ_DIR! && set MYSQL_PORT=%MYSQL_PORT% && set MYSQL_PASS=%MYSQL_PASS% && set REQUIRE_API_KEY=%REQUIRE_API_KEY% && mvn spring-boot:run -pl image-engine"
start "VideoEngine-8086" cmd /k "cd /d !PROJ_DIR! && set MYSQL_PORT=%MYSQL_PORT% && set MYSQL_PASS=%MYSQL_PASS% && set REQUIRE_API_KEY=%REQUIRE_API_KEY% && mvn spring-boot:run -pl video-engine"

echo.
echo Waiting for Gateway (port 8080)...
set WAIT=0
:wait_gw
timeout /t 3 /nobreak >nul
set /a WAIT=!WAIT!+3
netstat -ano 2>nul | findstr ":8080 " | findstr "LISTENING" >nul
if not errorlevel 1 goto gw_ready
if !WAIT! lss 120 goto wait_gw
echo Timeout. Services may still be starting.
echo Open http://localhost:3000 manually when ready.
goto end

:gw_ready
echo Gateway ready. Starting frontend...
start "WebAdmin-3000" cmd /k "cd /d !PROJ_DIR!\web-admin && npm run dev"

:end
echo.
echo ================================================================
echo    Frontend : http://localhost:3000
echo    Gateway  : http://localhost:8080
echo    Login    : admin / admin123
echo ================================================================
pause
