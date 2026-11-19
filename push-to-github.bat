@echo off
rem Pushes SiteDraft and ProCalcMultiplatform (and Crewsync, if it has anything new) to GitHub.
rem Output goes to push-to-github.log next to this file.
cd /d "%~dp0"
call :main > push-to-github.log 2>&1
type push-to-github.log
pause
exit /b

:main
for %%R in (SiteDraft ProCalcMultiplatform Crewsync) do (
  echo ==== %%R
  pushd "..\%%R"
  git status -sb
  git push origin HEAD
  git status -sb
  popd
)
exit /b 0
