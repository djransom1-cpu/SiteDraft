@echo off
rem Runs the SiteDraft desktop tests (compiles the shared code) and logs to test-desktop.log.
cd /d "%~dp0"
if not defined JAVA_HOME (
  if exist "C:\Program Files\Android\Android Studio\jbr\bin\java.exe" set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
)
echo JAVA_HOME=%JAVA_HOME% > test-desktop.log
echo Running SiteDraft tests... this takes a few minutes.
call gradlew.bat :shared:desktopTest --console=plain >> test-desktop.log 2>&1
echo EXIT CODE %ERRORLEVEL% >> test-desktop.log
echo Done. Exit code %ERRORLEVEL%. This window closes in 10 seconds.
timeout /t 10 >nul
