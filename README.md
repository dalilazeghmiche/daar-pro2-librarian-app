# DAAR Projet 2 — Moteur de recherche d'une bibliothèque

Application web de recherche de livres dans une bibliothèque de **1684 livres anglais** du Projet Gutenberg
(https://www.gutenberg.org), chacun d'au moins 10 000 mots.

Fonctionnalités :
1. **Recherche** par mot-clé (index inversé), ou des mots contenant une chaîne (KMP) ;
2. **Recherche avancée** par expression régulière (automate du projet 1 : NFA → DFA → DFA minimal), sur l'index ou sur le texte complet ;
3. **Classement** des résultats : pertinence, occurrences, fréquence, closeness, betweenness, PageRank, consultations ;
4. **Suggestions** : livres voisins des meilleurs résultats dans le graphe de Jaccard.

Tout est écrit en **Java 17+ sans dépendance externe** ; le frontend utilise **Vue 3** (fichier inclus, fonctionne hors ligne).



## Construire les données (une seule fois)

Les livres ne sont pas inclus dans l'archive (plusieurs centaines de Mo). Dans ce dossier :

```
java ConstruireBibliotheque.java      télécharge 1700 livres (≈ 1 h, reprenable si interrompu)
java Indexeur.java                    ≈ 20 s
java Jaccard.java                     ≈ 25 s (8 cœurs)
java Dedoublonner.java                retire les doublons (distance < 0,05)
java Jaccard.java                     recalcul sans les doublons
java Jaccard.java 0.70                construit le graphe pour θ = 0,70
java Centralite.java                  < 1 s   (java Centralite.java test : vérification sur des petits graphes)
```

Chaque programme se lance directement avec `java Fichier.java` (aucune compilation nécessaire).

## Lancer l'application

```
cd serveur
lancer.bat 8081          (Windows)   ou   ./lancer.sh .. 8081   (Linux/macOS)
```

Puis ouvrir `http://localhost:8081/`

## Choix techniques principaux

- **Index** : découpage `[^A-Za-z]`, minuscules, mots de moins de 3 lettres retirés (cours 3).
- **Jaccard** : formule du cours sur les **fréquences relatives** (sinon les très longs livres sont loin de tout) et **sans les mots vides** ; calcul de toutes les paires par fusion de listes triées, d = 1 − Σmin / (2 − Σmin).
- **Graphe** : arête si distance < θ = 0,70 ; un livre isolé est relié à son plus proche voisin (chaque livre a au moins une suggestion).
- **Centralité** : closeness de Wasserman-Faust (le graphe n'est pas connexe), betweenness par l'algorithme de Brandes en O(nm), PageRank (α = 0,85).
- **Doublons** : 16 paires de livres à distance < 0,05 (même texte publié sous deux numéros Gutenberg) → 1700 − 16 = 1684 livres.
