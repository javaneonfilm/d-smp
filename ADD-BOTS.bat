@echo off
title CartZone test bots
echo.
echo   Test bots join the CartZone test server, ride the ghasts, land and fight.
echo   Start a game first (/cart start in game), then run this within 60 seconds.
echo.
set N=
set /p N=How many bots? (Enter = 4):
if "%N%"=="" set N=4
cd /d "%~dp0tools\bots"
node bots.js %N% Bot 25599 --fight --centre=-478,-489
echo.
echo   Bots finished. Close this window.
pause >nul
