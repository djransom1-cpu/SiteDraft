@echo off
rem Releases Site Draft: tags the current commit v<version> (the desktop packageVersion, same as Android)
rem and pushes the tag. GitHub then builds all three on that version: the signed Android AAB, the
rem Windows MSI, and the web app (published to Pages). Run commit-to-github.bat first. Output: release.log.
cd /d "%~dp0"
call :main > release.log 2>&1
type release.log
pause
exit /b

:main
for /f "tokens=2 delims==" %%V in ('findstr /c:"packageVersion" desktopApp\build.gradle.kts') do set "RAW=%%V"
set "VER=%RAW: =%"
set "VER=%VER:"=%"
echo ==== Site Draft %VER%
git diff --quiet HEAD -- shared app desktopApp webApp functions-mcp || echo WARNING: there are uncommitted changes; run commit-to-github.bat first.
git status -sb
git tag -a v%VER% -m "Site Draft %VER%"
git push origin v%VER%
echo ==== Tagged v%VER%: GitHub is building Android, desktop and web. See the Actions tab.
exit /b 0
