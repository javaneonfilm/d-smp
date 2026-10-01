@echo off
title CartZone test server
cd /d "%~dp0dev-server"

rem Serves the resource pack to your game (http://127.0.0.1:8788). A small window opens minimized.
start "CartZone pack (close after testing)" /min py -m http.server 8788 --bind 127.0.0.1 --directory "%~dp0resourcepack"

echo.
echo   CartZone TEST server (Paper 1.21.11, The Lost Island + Cubrix lobby)
echo   -----------------------------------------------------------------
echo   Join in Minecraft 1.21.11:  Multiplayer ^> Direct Connection ^> localhost:25599
echo.
echo   First time only, type these here when it says "Done":
echo       whitelist add YOURNAME
echo       op YOURNAME
echo.
echo   Then in game:  /cart start   (game opens, 60 s)   and run ADD-BOTS.bat for opponents.
echo   After each game the server restarts itself, like on Purpify. Close this window to quit.
echo.

:loop
java -Xmx4G -jar paper.jar nogui
echo.
echo   Server stopped. Starting again in 5 seconds (close this window to quit)...
timeout /t 5 >nul
goto loop
