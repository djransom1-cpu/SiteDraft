@echo off
rem Deploys the Site Draft MCP connector (Cloud Function sitedraftMcp) and the Firestore rules.
rem Output goes to deploy-connector.log next to this file.
cd /d "%~dp0"
call :main > deploy-connector.log 2>&1
type deploy-connector.log
echo.
echo Done. Connector URL for Claude: https://sitedraftmcp-ufaob6wjnq-uc.a.run.app
echo (not the cloudfunctions.net "Function URL" above: sign-in only works at the run.app address)
pause
exit /b

:main
echo ==== %date% %time%  Site Draft MCP connector deploy
cd functions-mcp
echo ---- npm install
call npm install --no-audit --no-fund || exit /b 1
echo ---- connector tests
call npm test || exit /b 1
cd ..
echo ---- access key secret
call firebase.cmd functions:secrets:get SITEDRAFT_MCP_KEY --project sitedraft-djransom
if errorlevel 1 (
  echo Setting SITEDRAFT_MCP_KEY from Firebase keys\sitedraft-mcp-key.txt
  call firebase.cmd functions:secrets:set SITEDRAFT_MCP_KEY --data-file "..\Firebase keys\sitedraft-mcp-key.txt" --project sitedraft-djransom --force || exit /b 1
)
echo ---- deploy
call firebase.cmd deploy --only functions:mcp,firestore:rules --project sitedraft-djransom --force || exit /b 1
echo ==== deployed
exit /b 0
