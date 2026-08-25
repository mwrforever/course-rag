@echo off
chcp 65001 >nul
rem ================================================================
rem commerce-customer dev startup script (native Windows, cmd batch)
rem
rem Commands (aligned with tools/dev.sh):
rem   all         Start everything (infra + backend + both frontends). Default.
rem   infra       Start infra only (docker compose dev: PG/Redis/etcd/MinIO/Milvus)
rem   backend     Start backend only (mvn spring-boot:run, :8080)
rem   c           Start C-side student frontend (Next.js, :5000)
rem   b           Start B-side admin frontend (Vue3, :5001)
rem   status      Show component status (port probe)
rem   stop        Stop all local processes (optional: backend | c | b)
rem   down        Stop local processes and shut down infra containers
rem   logs        Tail logs under logs\ (Ctrl+C to exit)
rem   envs        Show injected .env vars (sensitive keys masked)
rem   help        Show this help
rem
rem .env injection rules (same as tools/dev.sh):
rem   1. Loads backend\.env at startup and sets vars in this session;
rem      child processes (mvn/node) inherit them.
rem   2. Existing env vars take precedence (never overridden);
rem      KEY="value" quotes are stripped.
rem   3. Comment lines (#) and empty lines are skipped.
rem
rem Process management (differs from dev.sh PID files):
rem   - Each component runs in a minimized window (start /min cmd /c),
rem     logs land in logs\*.log.
rem   - Stopping is port-based (netstat + taskkill): graceful first,
rem     forced after timeout.
rem
rem Encoding notes (keep this file pure ASCII):
rem   - Windows cmd has a notorious parser defect: under code page 65001,
rem     batch lines mixing UTF-8 multibyte text with certain ASCII chars
rem     (/, \, +, -) get split mid-line and executed as garbage commands.
rem     Verified empirically on Win10 26200 (see tools/dev.sh for the
rem     Chinese-featured script that runs fine under Git Bash).
rem   - CRLF line endings are required by cmd; enforced via .gitattributes.
rem ================================================================

setlocal EnableExtensions
rem Delayed expansion is intentionally OFF: values containing "!" in .env
rem would be corrupted otherwise.

rem ---- Path resolution: dev.bat lives at repo root (%cd% == repo root) ----
set "PROJECT_ROOT=%cd%"
set "ENV_FILE=%PROJECT_ROOT%\backend\.env"
set "LOGS_DIR=%PROJECT_ROOT%\logs"
if not exist "%LOGS_DIR%" mkdir "%LOGS_DIR%"

set "CMD=%~1"
if "%CMD%"=="" set "CMD=all"

rem ---- Inject backend\.env (skip existing vars; for /f handles CRLF) ----
if not exist "%ENV_FILE%" (
  echo [dev][warn] .env not found: %ENV_FILE%, backend falls back to application.yml defaults
) else (
  for /f "usebackq eol=# tokens=1,* delims==" %%a in ("%ENV_FILE%") do (
    if not defined %%a set "%%a=%%~b"
  )
)

rem ===================== command dispatch =====================
if "%CMD%"=="all"     goto :all
if "%CMD%"=="start"   goto :all
if "%CMD%"=="up"      goto :all
if "%CMD%"=="infra"   goto :infra
if "%CMD%"=="backend" goto :backend
if "%CMD%"=="c"       goto :c_frontend
if "%CMD%"=="b"       goto :b_frontend
if "%CMD%"=="status"  goto :status
if "%CMD%"=="logs"    goto :logs
if "%CMD%"=="envs"    goto :envs
if "%CMD%"=="down"    goto :down
if "%CMD%"=="stop"    goto :stop
if "%CMD%"=="help"    goto :help
if "%CMD%"=="-h"      goto :help
if "%CMD%"=="--help"  goto :help
echo [dev][error] unknown command: %CMD%
goto :help

:all
call :start_infra
if errorlevel 1 goto :all_abort
call :start_backend
if errorlevel 1 (
  echo [dev][warn] backend failed to start, check the message above, skipping readiness wait
) else (
  call :wait_backend
)
call :start_c_frontend
call :start_b_frontend
call :show_status
echo [dev] tailing logs, Ctrl+C to exit (services keep running; use "dev.bat stop" to stop)...
call :tail_logs
goto :eof

:all_abort
echo [dev][error] startup aborted, fix the reported cause then rerun "dev.bat"
pause
goto :eof

:infra
call :start_infra
goto :eof

:backend
call :port_listening 8080
if "%PL_RESULT%"=="1" (
  echo [dev][error] port 8080 already in use, backend may be running, skip
  goto :eof
)
where mvn >nul 2>&1
if errorlevel 1 (
  echo [dev][error] mvn not found, please install Maven and add it to PATH
  goto :eof
)
where java >nul 2>&1
if errorlevel 1 (
  echo [dev][error] java not found, please install JDK 17+ and add it to PATH
  goto :eof
)
echo [dev] running backend in foreground (mvn spring-boot:run), Ctrl+C to exit...
cd /d "%PROJECT_ROOT%\backend"
mvn spring-boot:run
goto :eof

:c_frontend
call :port_listening 5000
if "%PL_RESULT%"=="1" (
  echo [dev][error] port 5000 already in use, C frontend may be running, skip
  goto :eof
)
call :resolve_pnpm
if "%PNPM_CMD%"=="" goto :eof
echo [dev] running C-side student frontend in foreground, Ctrl+C to exit...
cd /d "%PROJECT_ROOT%"
%PNPM_CMD% --filter student-frontend dev
goto :eof

:b_frontend
call :port_listening 5001
if "%PL_RESULT%"=="1" (
  echo [dev][error] port 5001 already in use, B frontend may be running, skip
  goto :eof
)
call :resolve_pnpm
if "%PNPM_CMD%"=="" goto :eof
echo [dev] running B-side admin frontend in foreground, Ctrl+C to exit...
cd /d "%PROJECT_ROOT%"
%PNPM_CMD% --filter frontend dev
goto :eof

:status
call :show_status
goto :eof

:stop
if "%~2"=="" goto :stop_all
if "%~2"=="backend" (
  call :stop_port backend 8080
  goto :eof
)
if "%~2"=="c" (
  call :stop_port c-frontend 5000
  goto :eof
)
if "%~2"=="b" (
  call :stop_port b-frontend 5001
  goto :eof
)
echo [dev][error] unknown component: %~2, choose backend, c or b
goto :help

:stop_all
call :stop_port backend 8080
call :stop_port c-frontend 5000
call :stop_port b-frontend 5001
goto :eof

:down
call :stop_port backend 8080
call :stop_port c-frontend 5000
call :stop_port b-frontend 5001
call :stop_infra
goto :eof

:logs
call :tail_logs
goto :eof

:envs
call :show_envs
goto :eof

:help
echo.
echo commerce-customer dev startup script
echo.
echo Usage: dev.bat [command]
echo.
echo Commands:
echo   all         Start everything (infra + backend + both frontends). Default.
echo   infra       Start infra only (docker compose dev: PG/Redis/etcd/MinIO/Milvus)
echo   backend     Start backend only (mvn spring-boot:run, :8080)
echo   c           Start C-side student frontend (Next.js, :5000)
echo   b           Start B-side admin frontend (Vue3, :5001)
echo   status      Show component status (port probe)
echo   stop        Stop all local processes (optional: backend ^| c ^| b)
echo   down        Stop local processes and shut down infra containers (data kept)
echo   logs        Tail logs under logs\ ^(Ctrl+C to exit^)
echo   envs        Show injected .env vars (sensitive keys masked)
echo   help        Show this help
echo.
echo Notes:
echo   - Loads backend\.env at startup; existing env vars are never overridden
echo   - Each component runs in a minimized window, logs land in logs\
echo   - Stopping is port-based (netstat + taskkill), no PID files needed
echo   - Ctrl+C does NOT stop the minimized windows, use "dev.bat stop"
echo.
goto :eof

rem ===================== subroutines =====================

rem ---- Check whether a port is being listened on; %1=port, sets PL_RESULT ----
:port_listening
set "PL_RESULT=0"
netstat -ano | findstr /c:"LISTENING" | findstr /c:":%1 " >nul 2>&1
if not errorlevel 1 set "PL_RESULT=1"
goto :eof

rem ---- Start infra (docker compose dev); failure keeps the window open ----
:start_infra
where docker >nul 2>&1
if errorlevel 1 (
  echo [dev][error] docker not found, please install and start Docker Desktop
  pause
  exit /b 1
)
docker info >nul 2>&1
if errorlevel 1 (
  echo [dev][error] Docker daemon unreachable, please make sure Docker Desktop is running
  echo [dev][hint] start Docker Desktop and wait until the whale icon turns steady, then retry
  pause
  exit /b 1
)
echo [dev] starting infra (docker compose -f docker-compose.dev.yml up -d)...
docker compose -f docker-compose.dev.yml up -d
if errorlevel 1 (
  echo [dev][error] infra failed to start. common causes:
  echo [dev][hint] 1. port conflict with other projects: netstat -ano ^| findstr ":5432" etc.
  echo [dev][hint] 2. image pull failed: check network, or run "docker compose -f docker-compose.dev.yml pull" first
  echo [dev][hint] 3. Docker Desktop not ready: check the whale icon
  pause
  exit /b 1
) else (
  echo [dev] infra ready, PG:5432, Redis:6379, Milvus:19530, MinIO:9002
)
goto :eof

rem ---- Start backend (mvn spring-boot:run, :8080) ----
:start_backend
call :port_listening 8080
if "%PL_RESULT%"=="1" (
  echo [dev][error] port 8080 already in use, backend may be running, skip
  exit /b 1
)
where mvn >nul 2>&1
if errorlevel 1 (
  echo [dev][error] mvn not found, please install Maven and add it to PATH
  exit /b 1
)
where java >nul 2>&1
if errorlevel 1 (
  echo [dev][error] java not found, please install JDK 17+ and add it to PATH
  exit /b 1
)
echo [dev] starting backend (mvn spring-boot:run, minimized window, log: logs\backend.log)...
start "commerce-backend" /min cmd /c "cd /d ""%PROJECT_ROOT%\backend"" && mvn spring-boot:run > ""%LOGS_DIR%\backend.log"" 2>&1"
goto :eof

rem ---- Resolve pnpm launcher: pnpm directly, or corepack fallback ----
:resolve_pnpm
set "PNPM_CMD=pnpm"
where pnpm >nul 2>&1
if not errorlevel 1 goto :eof
where corepack >nul 2>&1
if errorlevel 1 (
  echo [dev][error] neither pnpm nor corepack found, install pnpm first
  set "PNPM_CMD="
) else (
  set "PNPM_CMD=corepack pnpm"
)
goto :eof

rem ---- Start C-side student frontend (Next.js, :5000) ----
:start_c_frontend
call :port_listening 5000
if "%PL_RESULT%"=="1" (
  echo [dev][error] port 5000 already in use, C frontend may be running, skip
  goto :eof
)
call :resolve_pnpm
if "%PNPM_CMD%"=="" goto :eof
echo [dev] starting C-side student frontend (%PNPM_CMD% --filter student-frontend dev, minimized window, log: logs\c-frontend.log)...
start "commerce-c-frontend" /min cmd /c "cd /d ""%PROJECT_ROOT%"" && %PNPM_CMD% --filter student-frontend dev > ""%LOGS_DIR%\c-frontend.log"" 2>&1"
goto :eof

rem ---- Start B-side admin frontend (Vue3, :5001) ----
:start_b_frontend
call :port_listening 5001
if "%PL_RESULT%"=="1" (
  echo [dev][error] port 5001 already in use, B frontend may be running, skip
  goto :eof
)
call :resolve_pnpm
if "%PNPM_CMD%"=="" goto :eof
echo [dev] starting B-side admin frontend (%PNPM_CMD% --filter frontend dev, minimized window, log: logs\b-frontend.log)...
start "commerce-b-frontend" /min cmd /c "cd /d ""%PROJECT_ROOT%"" && %PNPM_CMD% --filter frontend dev > ""%LOGS_DIR%\b-frontend.log"" 2>&1"
goto :eof

rem ---- Wait until backend responds (curl probe, any HTTP status counts; max 120s) ----
:wait_backend
where curl >nul 2>&1
if errorlevel 1 (
  echo [dev][warn] curl not found, skipping readiness wait
  goto :eof
)
echo [dev] waiting for backend (http://localhost:8080, max 120s)...
set /a WAIT_COUNT=0
:wait_loop
curl -s -o nul http://localhost:8080/ >nul 2>&1
if not errorlevel 1 (
  echo [dev] backend ready
  goto :eof
)
set /a WAIT_COUNT+=1
if %WAIT_COUNT% GEQ 60 (
  echo [dev][warn] backend wait timed out 120s, check logs\backend.log
  echo [dev][hint] common causes: infra containers not ready yet, DB/Redis/Milvus, or port 8080 conflict
  echo [dev][hint] keep this window open, check logs\backend.log in another terminal, then press any key
  pause
  goto :eof
)
rem 2-second poll; timeout fails when stdin is redirected, fall back to ping
timeout /t 2 /nobreak >nul 2>&1 || ping -n 3 127.0.0.1 >nul 2>&1
goto :wait_loop

rem ---- Stop one component; %1=name, %2=port: graceful, verify, force ----
:stop_port
call :port_listening %2
if "%PL_RESULT%"=="0" (
  echo [dev] %1 not running
  goto :eof
)
echo [dev] stopping %1 (port %2)...
for /f "tokens=5" %%p in ('netstat -ano ^| findstr /c:"LISTENING" ^| findstr /c:":%2 "') do taskkill /pid %%p >nul 2>&1
rem wait ~2s for the process to exit (3 pings round-trip)
ping -n 3 127.0.0.1 >nul 2>&1
call :port_listening %2
if "%PL_RESULT%"=="0" (
  echo [dev] %1 stopped
  goto :eof
)
echo [dev][warn] %1 port %2 still listening, force killing...
for /f "tokens=5" %%p in ('netstat -ano ^| findstr /c:"LISTENING" ^| findstr /c:":%2 "') do taskkill /f /pid %%p >nul 2>&1
ping -n 2 127.0.0.1 >nul 2>&1
call :port_listening %2
if "%PL_RESULT%"=="1" (
  echo [dev][error] %1 port %2 could not be released, check leftover processes in Task Manager
) else (
  echo [dev] %1 stopped
)
goto :eof

rem ---- Shut down infra containers (volumes kept) ----
:stop_infra
where docker >nul 2>&1
if errorlevel 1 (
  echo [dev][warn] docker not found, skipping container shutdown
  goto :eof
)
echo [dev] shutting down infra containers (docker compose down, volumes kept)...
docker compose -f docker-compose.dev.yml down
goto :eof

rem ---- Show component status (single netstat sample, then filter per port) ----
:show_status
echo [dev] project root: %PROJECT_ROOT%
echo [dev] component status:
netstat -ano > "%TEMP%\dev-status.txt" 2>nul
call :status_one backend 8080
call :status_one c-frontend 5000
call :status_one b-frontend 5001
goto :eof

rem ---- One status line; %1=name, %2=port ----
:status_one
set "STATE=not running"
findstr /c:"LISTENING" "%TEMP%\dev-status.txt" | findstr /c:":%2 " >nul 2>&1 && set "STATE=running"
echo    %1       %STATE%     port %2
goto :eof

rem ---- Tail logs (PowerShell Get-Content -Wait, like tail -f); keep window open on failure ----
:tail_logs
if not exist "%LOGS_DIR%\*.log" (
  echo [dev][warn] no log files under logs\ yet, components may have failed to start
  echo [dev][hint] run "dev.bat status" to check, or docker compose -f docker-compose.dev.yml ps for containers
  pause
  goto :eof
)
powershell -NoProfile -Command "Get-Content -Path '%LOGS_DIR%\*.log' -Tail 50 -Wait"
goto :eof

rem ---- Show injected .env vars (sensitive keys masked) ----
:show_envs
echo [dev] injected env vars (from %ENV_FILE%, keys containing KEY/SECRET/PASSWORD/TOKEN are masked):
if not exist "%ENV_FILE%" (
  echo [dev][warn] %ENV_FILE% not found
  goto :eof
)
for /f "usebackq eol=# tokens=1,* delims==" %%a in ("%ENV_FILE%") do (
  echo %%a | findstr /i "KEY SECRET PASSWORD TOKEN" >nul
  if errorlevel 1 (
    echo    %%a=%%~b
  ) else (
    echo    %%a=***
  )
)
goto :eof
