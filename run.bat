@echo off
setlocal
cd /d "%~dp0"
if not exist "lib\sqlite-jdbc.jar" (
  echo Chybi lib\sqlite-jdbc.jar. Nejdrive spustte build-exe.bat.
  pause
  exit /b 1
)
java -cp "SimpleDatabaseManager.jar;lib/*" SimpleDatabaseManager
