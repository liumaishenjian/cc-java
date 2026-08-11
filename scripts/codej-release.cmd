@echo off
setlocal
if not defined JAVA_HOME (
  where java >nul 2>nul || (echo cc-java requires Java 21+ 1>&2 & exit /b 2)
  set "JAVA=java"
) else set "JAVA=%JAVA_HOME%\bin\java.exe"
"%JAVA%" -cp "%~dp0app\*" io.github.liumaishenjian.ccjava.cli.CcJavaCliMain %*
exit /b %ERRORLEVEL%
