@echo off
setlocal
set "CODEJ_ROOT=%~dp0"
if exist "%CODEJ_ROOT%runtime\node\node.exe" (
  set "CODEJ_NODE=%CODEJ_ROOT%runtime\node\node.exe"
) else (
  where node >nul 2>nul || (echo codej requires Node.js 22+ 1>&2 & exit /b 2)
  set "CODEJ_NODE=node"
)
if exist "%CODEJ_ROOT%runtime\java\bin\java.exe" (
  set "CODEJ_JAVA=%CODEJ_ROOT%runtime\java\bin\java.exe"
) else if defined JAVA_HOME (
  set "CODEJ_JAVA=%JAVA_HOME%\bin\java.exe"
) else (
  where java >nul 2>nul || (echo codej requires Java 21+ 1>&2 & exit /b 2)
  set "CODEJ_JAVA=java"
)
"%CODEJ_NODE%" "%CODEJ_ROOT%codej-launcher.mjs" %*
exit /b %ERRORLEVEL%
