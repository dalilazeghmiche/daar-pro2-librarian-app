# DAAR Projet 2 — Serveur (API REST + interface web)

Serveur HTTP intégré au JDK (`com.sun.net.httpserver`) : **aucune dépendance**, Java 17 ou plus.
Pour construire les données puis lancer l'application, suivre d'abord le **README principal** (dossier parent).

## Organisation

```
serveur/
├── lancer.bat / lancer.sh          compile (javac) puis lance le serveur
├── benchmark.bat / benchmark.sh    tests de performance
├── benchmark/                      résultats des tests (resume.txt, *.csv), graphes.py, graphes/*.png
├── src/fr/su/daar/
│   ├── Serveur.java                routes HTTP, réponses JSON, fichiers de l'interface
│   ├── Json.java                   écriture JSON minimale
│   ├── moteur/Bibliotheque.java    charge les données précalculées au démarrage + compteur de consultations
│   ├── moteur/IndexInverse.java    index inversé : mot -> (livre, occurrences)
│   ├── moteur/Moteur.java          recherche, RegEx, classement, regroupement des éditions, suggestions
│   ├── regex/                      moteur du projet 1 : RegexParser, NFA, DFA, Minimizer, KMP, BoyerMoore, NFAMatcher
│   └── bench/                      programme des tests de performance (Benchmark.java, Centralite.java)
└── web/                            interface : index.html, app.js (Vue 3), style.css, vue.global.prod.js
```

Données lues au démarrage (dans le dossier parent) : `books/metadata.csv`, `books/<id>.txt` (texte, lu à la demande),
`index/*.idx`, `jaccard/graphe.csv`, `jaccard/centralite.csv`. Les consultations sont enregistrées dans `../clics.csv`.

## Lancer

```
lancer.bat 8081            (Windows, terminal ouvert dans serveur\)
./lancer.sh .. 8081        (macOS / Linux)
```

Le serveur écoute sur `0.0.0.0` : il est joignable depuis les autres appareils du même réseau à l'adresse affichée au démarrage.

Problèmes fréquents :
- « le port 8080 est déjà utilisé » → lancer sur un autre port (`lancer.bat 8081`).
- un téléphone n'accède pas au site → voir la section « Si un téléphone n'accède pas à l'application » du README principal.
- après une modification de `web/` : recharger la page avec Ctrl+F5 ; après une modification de `src/` : relancer le serveur.

## API

| Route | Rôle |
|---|---|
| `GET /api/stats` | nombre de livres et de mots, tris disponibles, consultations |
| `GET /api/top?tri=pagerank&limite=10` | livres les mieux classés de toute la bibliothèque (page d'accueil) |
| `GET /api/search?q=sargon` | recherche par mot-clé (mot exact de l'index) |
| `GET /api/search?q=babylon&mode=contient` | mots de l'index contenant « babylon » (KMP) |
| `GET /api/advanced-search?regex=S(a%7Cg%7Cr)%2Bon` | RegEx sur les mots de l'index (DFA minimal du projet 1) |
| `GET /api/advanced-search?regex=Sargon&cible=texte` | RegEx sur le texte complet, comme egrep (lent) |
| `&tri=pertinence\|occurrences\|frequence\|closeness\|betweenness\|pagerank\|clics` | classement (défaut : `pertinence`) |
| `&limite=50` | nombre de résultats renvoyés |
| `GET /api/books/56667` | détail d'un livre + centralité + voisins dans le graphe de Jaccard |
| `GET /api/books/56667/text` | texte du livre |
| `POST /api/books/56667/click` | enregistre une consultation |

Dans une URL tapée à la main, `+` s'écrit `%2B` et `|` s'écrit `%7C` (sinon `+` devient une espace).

Chaque réponse de recherche contient :
- `resultats` : livres classés ; chacun peut contenir `proches`, les éditions très proches regroupées sous lui ;
- `nbLivres` (livres trouvés) et `nbResultats` (après regroupement) ;
- `suggestions` : voisins dans le graphe de Jaccard des 3 meilleurs résultats, classés par nombre de résultats auxquels ils sont reliés,
  puis consultations, puis distance ; complétés par les voisins à distance 2 s'il y en a moins de 10 ; jamais deux suggestions quasi identiques ;
- `tempsMs` et `moteur` : temps de calcul et algorithme utilisé (index inversé, KMP, DFA minimal, simulation du NFA).

## Classement et regroupement

| Tri | Définition |
|---|---|
| `pertinence` (défaut) | fréquence du mot dans le livre × (1 + closeness) : le livre doit d'abord parler du mot, la centralité départage (bonus au plus ×2) |
| `occurrences` | nombre d'occurrences des mots reconnus dans le livre |
| `frequence` | occurrences / nombre de mots du livre |
| `closeness`, `betweenness`, `pagerank` | indice de centralité du livre dans le graphe de Jaccard |
| `clics` | nombre de consultations du livre dans l'application |

**Éditions très proches** : après le classement, un livre à distance de Jaccard < 0,25 d'un livre déjà placé plus haut
(de proche en proche) n'est pas affiché à part, mais rattaché à ce résultat (« + n éditions très proches »).
Exemple : les CIA World Factbook 1990 à 1995 forment un seul résultat pour `ocean`. Deux livres différents sur le même sujet
(ex. *A History of Sumer and Akkad* et *A History of Babylon*, distance 0,575) restent séparés.
