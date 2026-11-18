@echo off
setlocal EnableExtensions DisableDelayedExpansion
cd /d "%~dp0" || exit /b 1
set "APP_NAME=SimpleDatabaseManager"
set "APP_VERSION=1.1.0"
set "SQLITE_VERSION=3.47.1.0"
set "SLF4J_VERSION=2.0.16"
set "LOG=%CD%\build-exe.log"
set "STEP=Initialization"
>"%LOG%" echo [%DATE% %TIME%] Starting portable build
call :log INFO "Project directory: %CD%"

set "STEP=Checking prerequisites"
if /i not "%OS%"=="Windows_NT" goto fail
for %%T in (java javac jar jpackage powershell) do (
  where %%T >nul 2>&1
  if errorlevel 1 (
    call :log ERROR "Missing tool: %%T"
    goto fail
  )
)
if not exist "%APP_NAME%.java" (
  call :log ERROR "Missing source: %APP_NAME%.java"
  goto fail
)
call :log INFO "JDK version:"
java -version 2>>"%LOG%"
java -version
if not exist "lib" (
  mkdir "lib"
  if errorlevel 1 goto fail
)
call :log INFO "Dependency directory ready"

set "STEP=Downloading dependencies"
call :log INFO "Starting dependency checks"
call :download "lib\sqlite-jdbc.jar" "https://repo.maven.apache.org/maven2/org/xerial/sqlite-jdbc/%SQLITE_VERSION%/sqlite-jdbc-%SQLITE_VERSION%.jar"
if errorlevel 1 goto fail
call :download "lib\slf4j-api.jar" "https://repo.maven.apache.org/maven2/org/slf4j/slf4j-api/%SLF4J_VERSION%/slf4j-api-%SLF4J_VERSION%.jar"
if errorlevel 1 goto fail
call :download "lib\slf4j-nop.jar" "https://repo.maven.apache.org/maven2/org/slf4j/slf4j-nop/%SLF4J_VERSION%/slf4j-nop-%SLF4J_VERSION%.jar"
if errorlevel 1 goto fail

call :log INFO "Removing unused H2 dependencies"
if exist "lib\h2.jar" del /q "lib\h2.jar"
if exist "lib\h2-legacy.jar" del /q "lib\h2-legacy.jar"
set "STEP=Preparing build directories"
call :log INFO "[1/4] Preparing build directories"
if exist "build" rmdir /s /q "build"
if exist "build" goto fail
if exist "dist\%APP_NAME%" rmdir /s /q "dist\%APP_NAME%"
if exist "dist\%APP_NAME%" goto fail
if not exist "dist" (
  mkdir "dist"
  if errorlevel 1 goto fail
)
mkdir "build\classes" || goto fail
mkdir "build\input\lib" || goto fail

set "STEP=Compiling Java"
call :log INFO "[2/4] Compiling %APP_NAME%.java"
call :run javac -encoding UTF-8 -d "build\classes" "%APP_NAME%.java"
if errorlevel 1 goto fail
if not exist "build\classes\%APP_NAME%.class" goto fail

set "STEP=Building JAR"
call :log INFO "[3/4] Creating JAR and copying dependencies"
for %%F in (sqlite-jdbc.jar slf4j-api.jar slf4j-nop.jar) do (
  copy /y "lib\%%F" "build\input\lib\%%F" >>"%LOG%" 2>&1
  if errorlevel 1 goto fail
)
>"build\manifest.mf" echo Main-Class: SimpleDatabaseManager
>>"build\manifest.mf" echo Class-Path: lib/sqlite-jdbc.jar lib/slf4j-api.jar lib/slf4j-nop.jar
call :run jar cfm "build\input\%APP_NAME%.jar" "build\manifest.mf" -C "build\classes" .
if errorlevel 1 goto fail
if not exist "build\input\%APP_NAME%.jar" goto fail

set "STEP=Creating portable EXE"
call :log INFO "[4/4] Building portable Windows app-image (WiX not required)"
call :run jpackage --type app-image --name "%APP_NAME%" --app-version "%APP_VERSION%" --input "%CD%\build\input" --main-jar "%APP_NAME%.jar" --main-class "%APP_NAME%" --dest "%CD%\dist" --vendor "Simple Database Manager"
if errorlevel 1 goto fail
if not exist "dist\%APP_NAME%\%APP_NAME%.exe" (
  call :log ERROR "EXE launcher was not created"
  goto fail
)
call :log INFO "SUCCESS: %CD%\dist\%APP_NAME%\%APP_NAME%.exe"
call :log INFO "Copy the entire dist\%APP_NAME% folder to distribute the app."
call :log INFO "Log: %LOG%"
pause
exit /b 0

:download
if exist "%~1" (
  call :log INFO "Using cached dependency: %~1"
  exit /b 0
)
call :log INFO "Downloading: %~1"
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $ProgressPreference='SilentlyContinue'; Invoke-WebRequest -Uri '%~2' -OutFile '%~1'; if ((Get-Item '%~1').Length -le 0) { throw 'Empty download' }" >>"%LOG%" 2>&1
if errorlevel 1 (
  call :log ERROR "Download failed: %~1"
  exit /b 1
)
call :log INFO "Downloaded: %~1"
exit /b 0

:run
call :log INFO "Running: %*"
%* >>"%LOG%" 2>&1
if errorlevel 1 (
  call :log ERROR "Command failed: %*"
  powershell -NoProfile -Command "Get-Content -LiteralPath $env:LOG -Tail 20"
  exit /b 1
)
call :log INFO "Command finished successfully"
exit /b 0

:log
echo [%~1] %~2
>>"%LOG%" echo [%DATE% %TIME%] [%~1] %~2
exit /b 0

:fail
call :log ERROR "Build failed at: %STEP%"
call :log ERROR "See build-exe.log for details"
pause
exit /b 1
