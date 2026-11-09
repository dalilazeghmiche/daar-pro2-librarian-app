# DAAR Projet 2 — Serveur (API REST + frontend)

Serveur HTTP intégré au JDK (`com.sun.net.httpserver`) : aucune dépendance, Java 17+.



## Lancer

Prérequis : les dossiers `books/`, `index/` et `jaccard/` doivent exister dans le dossier parent (voir le README principal).

Windows (dans un terminal ouvert dans `serveur\\`) :

```
lancer.bat 8081

&#x20;   

```

Linux/macOS : `./lancer.sh` (ou `./lancer.sh .. 8081`)

Le script compile les sources (`javac`) puis démarre le serveur (\~8 s de chargement). Il affiche les adresses à ouvrir :



## API

|Route|Rôle|
|-|-|
|`GET /api/stats`|nombre de livres, de mots, tris disponibles, clics|
|`GET /api/top?tri=pagerank\&limite=10`|livres les mieux classés de toute la bibliothèque (page d'accueil)|
|`GET /api/search?q=sargon`|recherche par mot-clé (mot exact de l'index)|
|`GET /api/search?q=babylon\&mode=contient`|mots de l'index contenant « babylon » (KMP)|
|`GET /api/advanced-search?regex=S(a%7Cg%7Cr)%2Bon`|RegEx sur les mots de l'index (DFA minimal du projet 1)|
|`GET /api/advanced-search?regex=Sargon\&cible=texte`|RegEx sur le texte complet, comme egrep (lent)|
|`\&tri=pertinence\|occurrences\|frequence\|closeness\|betweenness\|pagerank\|clics`|classement des résultats (défaut : `pertinence`)|
|`\&limite=50`|nombre de résultats renvoyés|
|`GET /api/books/56667`|détail + centralité + voisins dans le graphe de Jaccard|
|`GET /api/books/56667/text`|texte du livre|
|`POST /api/books/56667/click`|enregistre une consultation (dans `../clics.csv`)|

Chaque réponse de recherche contient aussi `suggestions` : voisins, dans le graphe de Jaccard, des 3 meilleurs résultats
(classés par nombre de résultats auxquels ils sont reliés, puis clics, puis distance ; complétés par les voisins à distance 2
s'il y en a moins de 10), ainsi que `tempsMs` et `moteur` (algorithme utilisé : index inversé, KMP, DFA minimal, NFA).

## Classement

|Tri|Définition|
|-|-|
|`pertinence` (défaut)|fréquence du mot dans le livre × (1 + closeness) : le livre doit d'abord parler du mot, la centralité départage (bonus au plus ×2)|
|`occurrences`|nombre d'occurrences des mots reconnus dans le livre|
|`frequence`|occurrences / nombre de mots du livre|
|`closeness`, `betweenness`, `pagerank`|indice de centralité du livre dans le graphe de Jaccard|
|`clics`|nombre de consultations du livre dans l'application|

Dans une URL tapée à la main, `+` doit s'écrire `%2B` et `|` `%7C` (sinon `+` devient une espace).

