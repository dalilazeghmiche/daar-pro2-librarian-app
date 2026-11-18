#!/bin/sh
# DAAR Projet 2 - tests de performance (Linux / macOS). A lancer depuis le dossier serveur/
cd "$(dirname "$0")"
rm -rf build
javac -encoding UTF-8 -d build $(find src -name "*.java") || exit 1
exec java -Xmx4g -cp build fr.su.daar.bench.Benchmark "${1:-..}"
