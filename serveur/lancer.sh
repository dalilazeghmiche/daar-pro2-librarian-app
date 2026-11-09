#!/bin/sh
# DAAR Projet 2 - compile puis lance le serveur (Linux / macOS).
# A lancer depuis le dossier serveur/ ; les donnees (books/, index/, jaccard/) sont dans le dossier parent.
cd "$(dirname "$0")"
rm -rf build
javac -encoding UTF-8 -d build $(find src -name "*.java") || exit 1
exec java -Xmx2g -cp build fr.su.daar.Serveur "${1:-..}" "${2:-8080}"
