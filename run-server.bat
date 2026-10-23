@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"
title MailFlow Server Dashboard
set "JDK_BIN=C:\Program Files\Eclipse Adoptium\jdk-21.0.6.7-hotspot\bin"
set "FX_PATH=%~dp0lib\javafx-sdk-21.0.5\lib"
set "IKONLI=%~dp0lib\ikonli-core-12.3.1.jar;%~dp0lib\ikonli-javafx-12.3.1.jar;%~dp0lib\ikonli-fontawesome5-pack-12.3.1.jar"
set "MYSQL=%~dp0lib\mysql-connector-j-26.7.0.jar"
set "MAILJARS=%~dp0lib\jakarta.mail-api-2.1.5.jar;%~dp0lib\angus-mail-2.0.5.jar;%~dp0lib\jakarta.activation-api-2.1.4.jar;%~dp0lib\angus-activation-2.0.3.jar"
set "CP=%IKONLI%;%MYSQL%;%MAILJARS%"
if not exist "%JDK_BIN%\javac.exe" (echo JDK 21 not found: "%JDK_BIN%" & pause & exit /b 1)
if not exist "%FX_PATH%\javafx.controls.jar" (echo JavaFX SDK is missing: "%FX_PATH%" & pause & exit /b 1)
if not exist "%MYSQL%" (echo MySQL connector missing: "%MYSQL%" & pause & exit /b 1)
echo Compiling MailFlow server and client...
"%JDK_BIN%\javac.exe" --module-path "%FX_PATH%" --add-modules javafx.controls,javafx.web -encoding UTF-8 -cp "%CP%" -d out src\server\MailDatabase.java src\server\ServerCore.java src\server\ServerApp.java src\client\ClientCore.java src\client\MailConfig.java src\client\GoogleOAuthService.java src\client\ClientApp.java src\client\GmailSmtpService.java src\client\GmailPop3Service.java src\client\GmailImapService.java
if errorlevel 1 goto failed
echo Starting MailFlow Server Dashboard...
"%JDK_BIN%\java.exe" -Xms128m -Xmx768m --module-path "%FX_PATH%" --add-modules javafx.controls -cp "out;%CP%" server.ServerApp
if errorlevel 1 goto failed
exit /b 0
:failed
echo.
echo MailFlow server could not start. Review the error above.
echo Tip: Start MySQL (XAMPP) and check db.properties (port 3306 or 3307).
pause
exit /b 1
