@echo off
cd /d "%~dp0"
echo.
echo  Brainchair UI - local preview
echo  ---------------------------------
echo  Opening http://localhost:8000 in 2 seconds...
echo  Close this window to stop the server.
echo.

start "" cmd /c "timeout /t 2 >nul & start http://localhost:8000/"

where py >nul 2>nul
if %errorlevel%==0 goto :usepy

where python >nul 2>nul
if %errorlevel%==0 goto :usepython

echo  Python not found. Either install Python, or use the VS Code
echo  "Live Server" extension: right click index.html - Open with Live Server.
pause
goto :eof

:usepy
py -3 -m http.server 8000
goto :eof

:usepython
python -m http.server 8000
goto :eof
