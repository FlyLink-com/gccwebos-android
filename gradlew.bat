@echo off
setlocal
set "ROOT=%~dp0"
if defined JAVA_HOME (
    set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
) else (
    set "JAVA_EXE=java.exe"
)
"%JAVA_EXE%" -version >nul 2>&1
if errorlevel 1 (
    echo Java is unavailable. Install JDK 17 and set JAVA_HOME. 1>&2
    exit /b 1
)
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%ROOT%scripts\bootstrap-wrapper.ps1"
if errorlevel 1 exit /b 1
"%JAVA_EXE%" -Dfile.encoding=UTF-8 -Dorg.gradle.appname=gradlew -jar "%ROOT%gradle\wrapper\gradle-wrapper.jar" %*
exit /b %ERRORLEVEL%
