@echo off
rem Commits the SiteDraft source (tracked changes, new source and tests, connector) with the message in
rem commit-message.txt, then pushes. Backups, logs and screenshots are left out. Output: commit-to-github.log.
cd /d "%~dp0"
call :main > commit-to-github.log 2>&1
type commit-to-github.log
pause
exit /b

:main
git add -u
git add shared/src functions-mcp/sitedraft functions-mcp/package-lock.json build-test-copy.bat commit-to-github.bat release.bat
git status --short
git commit -F commit-message.txt
git push origin HEAD
git log -1 --oneline
git status -sb
exit /b 0
