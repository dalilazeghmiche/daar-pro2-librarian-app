@echo off
REM DAAR Projet 2 - tests de performance (Windows). A lancer depuis le dossier serveur\
REM Duree : 5 a 10 minutes. Resultats dans serveur\benchmark\
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
java -Xmx4g -cp build fr.su.daar.bench.Benchmark ..
pause
