@echo off
rem Builds a TEST COPY of SiteDraft and puts it on the desktop in a "SiteDraft Test" folder.
rem Nothing is installed, the version number doesn't change, nothing is committed or pushed.
rem The test copy keeps its own files (Documents\SiteDraft Test) and its own sign-in, and says
rem TEST in its title bar. Cloud projects are the same account's: test on a test project.
rem Close the test copy before running this again. Logs to build-test-copy.log.
cd /d "%~dp0"
if not defined JAVA_HOME (
  if exist "C:\Program Files\Android\Android Studio\jbr\bin\java.exe" set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
)
echo JAVA_HOME=%JAVA_HOME% > build-test-copy.log
echo %date% %time% >> build-test-copy.log

echo Step 1 of 3: running the tests... this takes a few minutes.
echo ==== TESTS >> build-test-copy.log
call gradlew.bat :shared:desktopTest --console=plain >> build-test-copy.log 2>&1
if errorlevel 1 goto testsfailed

echo Step 2 of 3: tests passed, building the test copy...
echo ==== BUILD >> build-test-copy.log
call gradlew.bat :desktopApp:createDistributable --console=plain >> build-test-copy.log 2>&1
if errorlevel 1 goto buildfailed

echo Step 3 of 3: putting it on the desktop...
set "DESK="
for /f "usebackq delims=" %%D in (`powershell -NoProfile -Command "[Environment]::GetFolderPath('Desktop')"`) do set "DESK=%%D"
if not defined DESK set "DESK=%USERPROFILE%\Desktop"
set "DEST=%DESK%\SiteDraft Test"
echo ==== COPY to %DEST% >> build-test-copy.log
robocopy "desktopApp\build\compose\binaries\main\app\SiteDraft" "%DEST%" /MIR /NFL /NDL /NJH /NP >> build-test-copy.log
if errorlevel 8 goto copyfailed

echo RESULT: SUCCESS, test copy at %DEST% >> build-test-copy.log
echo.
echo SUCCESS. The test copy is in "%DEST%". Starting it now...
start "" "%DEST%\SiteDraft.exe"
echo This window closes in 10 seconds.
timeout /t 10 >nul
exit /b 0

:testsfailed
echo RESULT: TESTS FAILED, no test copy built >> build-test-copy.log
echo.
echo TESTS FAILED. No test copy was made. Details are in build-test-copy.log.
pause
exit /b 1

:buildfailed
echo RESULT: BUILD FAILED >> build-test-copy.log
echo.
echo THE BUILD FAILED. Details are in build-test-copy.log.
pause
exit /b 1

:copyfailed
echo RESULT: COPY FAILED (is the test copy still open?) >> build-test-copy.log
echo.
echo COULD NOT COPY TO THE DESKTOP. Close the SiteDraft TEST window and run this again.
pause
exit /b 1
