@ECHO OFF
SETLOCAL

SET WRAPPER_JAR=%~dp0gradle\wrapper\gradle-wrapper.jar
IF EXIST "%WRAPPER_JAR%" (
  "%JAVA_HOME%\bin\java.exe" -jar "%WRAPPER_JAR%" %*
  EXIT /B %ERRORLEVEL%
)

WHERE gradle >NUL 2>&1
IF %ERRORLEVEL% EQU 0 (
  gradle %*
  EXIT /B %ERRORLEVEL%
)

echo [ERROR] gradle-wrapper.jar not found and no global gradle command available.
echo Install Gradle or add gradle-wrapper.jar under gradle\wrapper\.
EXIT /B 1