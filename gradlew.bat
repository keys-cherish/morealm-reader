@echo off
setlocal

set "GRADLE_HOME=C:\Users\test\.gradle\wrapper\dists\gradle-8.14-all\8mguqc37c200i71ledpgw8n5m\gradle-8.14"
set "GRADLE_BAT=%GRADLE_HOME%\bin\gradle.bat"

if not exist "%GRADLE_BAT%" (
    echo ERROR: Local Gradle 8.14 not found: %GRADLE_BAT% 1>&2
    exit /b 1
)

call "%GRADLE_BAT%" %*
exit /b %ERRORLEVEL%
