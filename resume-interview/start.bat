@echo off
setlocal
cd /d "%~dp0"

rem ---- backend ----
echo Starting backend ...
start "backend" cmd /k "cd /d %~dp0backend && mvn spring-boot:run"

rem ---- frontend ----
echo Starting frontend ...
start "frontend" cmd /k "cd /d %~dp0frontend && npm run dev"

echo Done. Open http://localhost:5173 after a few seconds.
endlocal