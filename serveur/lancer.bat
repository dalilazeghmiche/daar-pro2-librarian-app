@echo off
REM DAAR Projet 2 - compile puis lance le serveur (Windows).
REM A lancer depuis le dossier serveur\ ; les donnees (books\, index\, jaccard\) sont dans le dossier parent.
cd /d "%~dp0"
if exist build rmdir /s /q build
dir /s /b src\*.java > sources.txt
javac -encoding UTF-8 -d build @sources.txt
if errorlevel 1 (
  echo Erreur de compilation.
  pause
  exit /b 1
)
del sources.txt
set PORT=%1
if "%PORT%"=="" set PORT=8080
java -Xmx2g -cp build fr.su.daar.Serveur .. %PORT%
pause
