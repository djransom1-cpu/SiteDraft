@echo off
rem Runs the SiteDraft tests, then builds the installer if they pass. Logs to test-and-build.log.
cd /d "%~dp0"
if not defined JAVA_HOME (
  if exist "C:\Program Files\Android\Android Studio\jbr\bin\java.exe" set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
)
echo JAVA_HOME=%JAVA_HOME% > test-and-build.log
echo Testing and building SiteDraft... this takes a few minutes.
call gradlew.bat :shared:desktopTest :desktopApp:packageMsi --console=plain >> test-and-build.log 2>&1
echo EXIT CODE %ERRORLEVEL% >> test-and-build.log
echo Done. Exit code %ERRORLEVEL%. This window closes in 10 seconds.
timeout /t 10 >nul
