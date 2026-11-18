# DAAR Projet 2 — Moteur de recherche d'une bibliothèque de livres

Application web de recherche dans une bibliothèque de **1684 livres anglais** du Projet Gutenberg
(https://www.gutenberg.org), chacun d'au moins 10 000 mots.

Fonctionnalités :

| Fonctionnalité | Ce qu'elle fait | Algorithme |
|---|---|---|
| **Recherche par mot-clé** | livres dont le texte contient le mot | index inversé (table de hachage) |
| **Recherche « contient »** | livres dont un mot contient la chaîne (ex. `babylon` → `babylonian`) | KMP sur le vocabulaire |
| **Recherche avancée (RegEx)** | livres dont un mot est reconnu par l'expression régulière, sur l'index ou sur le texte complet | automate du projet 1 : NFA → DFA → DFA minimal |
| **Classement** | tri par pertinence, occurrences, fréquence, closeness, betweenness, PageRank ou consultations | indices de centralité sur le graphe de Jaccard |
| **Suggestions** | livres proches des meilleurs résultats | voisins dans le graphe de Jaccard |
| **Lecture** | affiche le texte du livre, mot recherché surligné | — |

Tout est écrit en **Java, sans aucune dépendance externe** (serveur HTTP intégré au JDK).
L'interface utilise **Vue 3**, dont le fichier est inclus : l'application fonctionne sans connexion internet.

---

## 1. Prérequis

| Outil | Version | Pour quoi | Vérifier avec |
|---|---|---|---|
| **Java (JDK)** | **17 ou plus** (testé avec 21) | tout le projet | `java -version` et `javac -version` |
| Connexion internet | — | uniquement pour télécharger les livres (étape 2.1) | — |
| Mémoire | 4 Go de RAM libres conseillés | le serveur utilise jusqu'à 2 Go | — |
| Python 3 + matplotlib | facultatif | uniquement pour redessiner les graphiques de performance | `python --version` |

Aucune autre installation n'est nécessaire (pas de Maven, pas de Node.js, pas de base de données).

---

## 2. Organisation du dossier

```
daar-pro2-librarian-app/
├── README.md                     ce fichier
├── ConstruireBibliotheque.java   étape 1 : télécharge les livres depuis Gutenberg
├── Indexeur.java                 étape 2 : table d'index de chaque livre (mot -> occurrences)
├── IndexInverse.java             étape 3 : index inversé, test en ligne de commande
├── Jaccard.java                  étapes 4-5 : matrice des distances de Jaccard + graphe
├── Dedoublonner.java                       retire les livres en double (même texte)
├── Centralite.java               étape 6 : closeness, betweenness, PageRank
│
├── books/      (généré)  textes des livres + metadata.csv (titre, auteur, nombre de mots)
├── index/      (généré)  une table d'index par livre (<id>.idx)
├── jaccard/    (généré)  distances.bin, ids.txt, graphe.csv, centralite.csv
├── doublons/   (généré)  livres retirés car en double + doublons.csv
│
└── serveur/                      application web (voir serveur/README.md)
    ├── lancer.bat / lancer.sh        compile et lance le serveur
    ├── benchmark.bat / benchmark.sh  tests de performance
    ├── benchmark/                    résultats des tests + graphiques
    ├── src/fr/su/daar/               code Java du serveur
    └── web/                          interface (HTML, CSS, JavaScript, Vue 3)
```

> **Les dossiers `books/`, `index/`, `jaccard/` et `doublons/` ne sont pas fournis** (plusieurs centaines de Mo).
> Ils sont **reconstruits automatiquement** par les commandes de la section 3.

---

## 3. Construire les données (une seule fois)

Ouvrir un terminal **dans le dossier `daar-pro2-librarian-app`** (celui qui contient ce README),
puis lancer les commandes **dans cet ordre**. Chaque programme se lance directement avec
`java Fichier.java`, sans compilation préalable. Les commandes sont les mêmes sous Windows, macOS et Linux.

| # | Commande | Durée | Ce qu'elle produit |
|---|---|---|---|
| 1 | `java ConstruireBibliotheque.java` | **≈ 1 h** (internet) | `books/` : 1700 livres anglais d'au moins 10 000 mots + `books/metadata.csv` |
| 2 | `java Indexeur.java` | ≈ 20 s | `index/` : une table mot → occurrences par livre |
| 3 | `java Jaccard.java` | ≈ 25 s | `jaccard/distances.bin` : distances entre toutes les paires de livres |
| 4 | `java Dedoublonner.java` | < 1 s | retire les 16 livres en double (distance < 0,05) → 1684 livres |
| 5 | `java Jaccard.java` | ≈ 25 s | recalcule les distances sans les doublons |
| 6 | `java Jaccard.java 0.70` | ≈ 2 s | `jaccard/graphe.csv` : graphe de Jaccard pour le seuil θ = 0,70 |
| 7 | `java Centralite.java` | < 1 s | `jaccard/centralite.csv` : closeness, betweenness, PageRank de chaque livre |

Remarques :

- **Étape 1** : le téléchargement peut être interrompu (Ctrl+C) puis relancé avec la même commande :
  les livres déjà téléchargés sont conservés. Une pause d'une seconde entre deux livres respecte le serveur Gutenberg.
- **Étape 4** : les livres retirés ne sont pas supprimés, ils sont déplacés dans `doublons/`.
- Vérifications facultatives :
  - `java IndexInverse.java` : construit l'index inversé et permet de taper des mots pour voir les livres trouvés ;
  - `java Centralite.java test` : vérifie les 3 indices de centralité sur de petits graphes calculés à la main (12 tests « OK »).
- En cas d'erreur `OutOfMemoryError`, ajouter `-Xmx2g` après `java` (ex. `java -Xmx2g Jaccard.java`).

---

## 4. Lancer l'application

Les données de la section 3 doivent exister. Dans un terminal :

**Windows**
```
cd serveur
lancer.bat 8081
```

**macOS / Linux**
```
cd serveur
./lancer.sh .. 8081
```

Le script compile le serveur, charge les données (≈ 10 s), puis affiche :

```
Serveur demarre. Ouvrir dans un navigateur :
   sur cette machine     : http://localhost:8081/
   depuis le reseau local : http://10.42.31.202:8081/
```

- **Sur l'ordinateur** : ouvrir `http://localhost:8081/`.
- **Depuis un téléphone ou un autre ordinateur** : se connecter au **même réseau** (Wi-Fi ou partage de connexion)
  et ouvrir l'adresse « depuis le réseau local » affichée (ignorer les adresses en `172.x` qui sont des cartes réseau virtuelles).
- Laisser le terminal ouvert pendant l'utilisation ; **Ctrl+C** arrête le serveur.
- Le numéro de port (`8081`) est libre : `lancer.bat 8090` lance sur le port 8090. Sans numéro, le port 8080 est utilisé.

### Si un téléphone n'accède pas à l'application (Windows)

1. Mettre la connexion en **réseau privé** : Paramètres → Réseau et Internet → Wi-Fi → (nom du réseau) → *Type de profil réseau* : **Privé**.
2. Autoriser le port dans le pare-feu, dans une invite de commandes **ouverte en tant qu'administrateur** :
   ```
   netsh advfirewall firewall add rule name="DAAR 8081" dir=in action=allow protocol=TCP localport=8081
   ```
3. Si le réseau isole les appareils (Wi-Fi d'école ou public), utiliser le **partage de connexion d'un téléphone** :
   y connecter l'ordinateur et le(s) téléphone(s), relancer le serveur et prendre la nouvelle adresse affichée.

---

## 5. Utiliser l'application

| Essai | Onglet | Saisie | Résultat attendu |
|---|---|---|---|
| Recherche simple | Mot-clé | `sargon` | 12 livres, *A History of Sumer and Akkad* et *A History of Babylon* en tête |
| Recherche « contient » | Contient | `babylon` | 288 livres, mots reconnus : babylonian, babylonia… |
| Expression régulière | RegEx | `S(a|g|r)+on` | 17 livres, « DFA minimal (5 états) », mots sargon, saron, sagon |
| RegEx sur le texte complet | RegEx texte | `Sargon` | 12 livres, ≈ 1 s (bien plus lent que sur l'index) |
| Classement | Mot-clé, puis *Classer par* | `ocean` | *Pertinence* : Factbook (+ éditions regroupées), Jules Verne… ; *Betweenness* : Shakespeare en tête |
| Lecture | — | ouvrir un livre puis **Lire le livre** | texte affiché, mot recherché surligné |

- Opérateurs RegEx acceptés : `( )` groupe, `|` ou, `*` zéro ou plus, `+` un ou plus, `.` n'importe quel caractère.
- Les RegEx sur l'index ne portent que sur des mots en minuscules (`a-z`) ; la RegEx sur le texte complet respecte la casse.
- Chaque ouverture d'un livre compte une **consultation** (enregistrée dans `clics.csv`), utilisée par le tri « Les plus consultés ».

---

## 6. Tests de performance (facultatif)

Arrêter le serveur, puis :

```
cd serveur
benchmark.bat          (Windows)     ou     ./benchmark.sh     (macOS / Linux)
```

Durée : 5 à 10 minutes. Le programme mesure, pour 100, 250, 500, 1000 et 1684 livres, le temps de construction de l'index,
de Jaccard, de la centralité et des différentes recherches (moyenne ± écart-type sur 10 mesures).
Résultats dans `serveur/benchmark/` (`resume.txt` + fichiers `.csv`).

Pour redessiner les graphiques (Python requis) :
```
cd serveur/benchmark
pip install matplotlib
python graphes.py
```
→ 5 graphiques PNG dans `serveur/benchmark/graphes/`.

---

## 7. Choix techniques principaux

- **Index** : texte découpé selon `[^A-Za-z]`, mots en minuscules, mots de moins de 3 lettres ignorés.
- **Jaccard** : formule du cours, Σ(max−min) / Σmax, appliquée aux **fréquences relatives**
  (sinon les très longs livres sont loin de tous les autres) et **sans les mots vides** (the, and, was…).
  Calcul de toutes les paires par fusion de listes triées : d = 1 − Σmin / (2 − Σmin).
- **Graphe** : arête entre deux livres si leur distance est < θ = 0,70 ; un livre isolé est relié à son plus proche voisin,
  pour que chaque livre ait au moins une suggestion.
- **Centralité** : closeness de Wasserman-Faust (le graphe n'est pas connexe), betweenness par l'algorithme de Brandes
  en O(n·m), PageRank (α = 0,85).
- **Pertinence** (tri par défaut) : fréquence du mot dans le livre × (1 + closeness).
- **Éditions très proches** : deux livres à distance de Jaccard < 0,25 (ex. CIA World Factbook de deux années voisines)
  sont regroupés en un seul résultat (« + n éditions très proches »).
- **Doublons** : 16 paires à distance < 0,05 (même texte publié sous deux numéros Gutenberg) → 1700 − 16 = 1684 livres.

Résultats mesurés (1684 livres, 8 cœurs) : recherche par mot-clé ≈ 0,003 ms, RegEx sur l'index ≈ 22 ms,
RegEx sur le texte complet ≈ 1,1 s ; construction de l'index inversé ≈ 7 s ; Jaccard (1,4 million de paires) ≈ 19 s.
