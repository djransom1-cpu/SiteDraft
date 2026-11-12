@echo off
rem Builds the SiteDraft Windows installer (packageMsi) and logs to build-msi.log.
cd /d "%~dp0"
if not defined JAVA_HOME (
  if exist "C:\Program Files\Android\Android Studio\jbr\bin\java.exe" set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
)
echo JAVA_HOME=%JAVA_HOME% > build-msi.log
echo Building SiteDraft installer... this takes a few minutes.
call gradlew.bat :desktopApp:packageMsi --console=plain >> build-msi.log 2>&1
echo EXIT CODE %ERRORLEVEL% >> build-msi.log
echo Done. Exit code %ERRORLEVEL%. You can close this window.
pause
