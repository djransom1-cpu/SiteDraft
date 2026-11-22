@echo off
rem Runs the SiteDraft tests, then builds the installer only if they pass. Logs to test-and-build.log.
rem On a failure the window stays open (press a key to close) and no installer is built.
cd /d "%~dp0"
if not defined JAVA_HOME (
  if exist "C:\Program Files\Android\Android Studio\jbr\bin\java.exe" set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
)
echo JAVA_HOME=%JAVA_HOME% > test-and-build.log
echo %date% %time% >> test-and-build.log

echo Step 1 of 2: running the tests... this takes a few minutes.
echo ==== TESTS >> test-and-build.log
call gradlew.bat :shared:desktopTest --console=plain >> test-and-build.log 2>&1
if errorlevel 1 goto testsfailed

echo Step 2 of 2: tests passed, building the installer...
echo ==== INSTALLER >> test-and-build.log
call gradlew.bat :desktopApp:packageMsi --console=plain >> test-and-build.log 2>&1
if errorlevel 1 goto buildfailed

echo RESULT: SUCCESS >> test-and-build.log
echo.
echo SUCCESS. The installer is in desktopApp\build\compose\binaries\main\msi
dir /b desktopApp\build\compose\binaries\main\msi\*.msi
echo This window closes in 15 seconds.
timeout /t 15 >nul
exit /b 0

:testsfailed
echo RESULT: TESTS FAILED, no installer built >> test-and-build.log
echo.
echo TESTS FAILED. No installer was built. Details are in test-and-build.log.
pause
exit /b 1

:buildfailed
echo RESULT: INSTALLER BUILD FAILED >> test-and-build.log
echo.
echo THE INSTALLER BUILD FAILED. Details are in test-and-build.log.
pause
exit /b 1
