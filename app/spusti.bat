@echo off
rem Spustenie aplikacie Autoservis (potrebna Java 17 alebo novsia)
rem JAR vznikne prikazom "mvn clean package" v priecinku app (target\autoservis-pds.jar)
set "JAR=%~dp0target\autoservis-pds.jar"
if exist "%~dp0autoservis-pds.jar" set "JAR=%~dp0autoservis-pds.jar"
cd /d "%~dp0"
java -jar "%JAR%"
if errorlevel 1 (
  echo.
  echo Aplikaciu sa nepodarilo spustit. Skontrolujte Javu 17+ ^(java -version^) a ci existuje %JAR% ^(mvn clean package^).
  pause
)
