package fr.su.daar.bench;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * DAAR Projet 2 - Etape 6 : indices de centralite des livres dans le graphe de Jaccard.
 *
 * Le graphe (jaccard/graphe.csv) est non oriente et non pondere : la distance d(u, v) entre deux livres
 * est le nombre d'aretes d'un plus court chemin (calcule par parcours en largeur, BFS).
 *
 * 1. Closeness (cours 3) : crank(v) = (n - 1) / somme_{u != v} d(u, v).
 *    Le graphe n'etant pas connexe, d(u, v) peut etre infini. On utilise la variante de Wasserman-Faust :
 *        C(v) = (r / (n - 1)) * (r / somme des distances vers les r livres atteignables depuis v)
 *    qui vaut exactement la formule du cours quand le graphe est connexe (r = n - 1), et qui penalise
 *    les livres situes dans une petite composante.
 *    Calcul : un BFS par sommet, O(n (n + m)).
 *
 * 2. Betweenness (cours 3) : brank(v) = somme_{s != v != t} #pcc(s, t, v) / #pcc(s, t).
 *    Algorithme de Brandes (2001) : un BFS par source s qui compte les plus courts chemins sigma,
 *    puis accumulation des dependances delta dans l'ordre inverse du BFS. O(n m) au lieu de O(n^3).
 *    Graphe non oriente : chaque paire {s, t} est comptee deux fois, on divise par 2.
 *    Normalisation dans [0, 1] : division par (n - 1)(n - 2) / 2.
 *
 * 3. PageRank (Brin et Page, 1998) : PR(v) = (1 - a) / n + a * somme_{u voisin de v} PR(u) / deg(u),
 *    a = 0.85, iteration de la puissance jusqu'a convergence (variation totale < 1e-12).
 *
 * Usage :  java Centralite.java                    (lit jaccard/graphe.csv)
 *          java Centralite.java test               (verification sur de petits graphes faits a la main)
 * Sortie : jaccard/centralite.csv   id;degre;closeness;betweenness;pagerank
 */
public class Centralite {

    // graphe en listes d'adjacence compactes (format CSR) : voisins de u = adj[debut[u] .. debut[u+1][
    final int n;
    final int[] debut, adj;

    Centralite(int n, List<int[]> aretes) {
        this.n = n;
        int[] deg = new int[n];
        for (int[] e : aretes) { deg[e[0]]++; deg[e[1]]++; }
        debut = new int[n + 1];
        for (int u = 0; u < n; u++) debut[u + 1] = debut[u] + deg[u];
        adj = new int[debut[n]];
        int[] pos = Arrays.copyOf(debut, n);
        for (int[] e : aretes) { adj[pos[e[0]]++] = e[1]; adj[pos[e[1]]++] = e[0]; }
    }

    int degre(int u) { return debut[u + 1] - debut[u]; }

    // ------------------------------------------------------------------ closeness

    double[] closeness() {
        double[] c = new double[n];
        IntStream.range(0, n).parallel().forEach(s -> {
            int[] dist = new int[n];
            Arrays.fill(dist, -1);
            int[] file = new int[n];
            int tete = 0, queue = 0;
            dist[s] = 0; file[queue++] = s;
            long somme = 0; int atteints = 0;
            while (tete < queue) {
                int u = file[tete++];
                for (int k = debut[u]; k < debut[u + 1]; k++) {
                    int v = adj[k];
                    if (dist[v] < 0) { dist[v] = dist[u] + 1; somme += dist[v]; atteints++; file[queue++] = v; }
                }
            }
            c[s] = (somme == 0 || n < 2) ? 0 : ((double) atteints / (n - 1)) * ((double) atteints / somme);
        });
        return c;
    }

    // ------------------------------------------------------------------ betweenness (Brandes)

    double[] betweenness() {
        int coeurs = Runtime.getRuntime().availableProcessors();
        double[][] partiel = new double[coeurs][n];                 // un accumulateur par thread
        IntStream.range(0, coeurs).parallel().forEach(t -> {
            double[] cb = partiel[t];
            int[] dist = new int[n], pile = new int[n], file = new int[n];
            long[] sigma = new long[n];
            double[] delta = new double[n];
            for (int s = t; s < n; s += coeurs) {
                Arrays.fill(dist, -1); Arrays.fill(sigma, 0); Arrays.fill(delta, 0);
                int tete = 0, queue = 0, hauteur = 0;
                dist[s] = 0; sigma[s] = 1; file[queue++] = s;
                while (tete < queue) {                              // BFS : compte des plus courts chemins
                    int u = file[tete++];
                    pile[hauteur++] = u;
                    for (int k = debut[u]; k < debut[u + 1]; k++) {
                        int v = adj[k];
                        if (dist[v] < 0) { dist[v] = dist[u] + 1; file[queue++] = v; }
                        if (dist[v] == dist[u] + 1) sigma[v] += sigma[u];
                    }
                }
                while (hauteur > 0) {                               // accumulation, du plus loin au plus proche
                    int w = pile[--hauteur];
                    for (int k = debut[w]; k < debut[w + 1]; k++) {
                        int v = adj[k];
                        if (dist[v] == dist[w] - 1) delta[v] += (double) sigma[v] / sigma[w] * (1 + delta[w]);
                    }
                    if (w != s) cb[w] += delta[w];
                }
            }
        });
        double[] cb = new double[n];
        double norme = n > 2 ? (n - 1) * (n - 2) / 2.0 : 1;
        for (int v = 0; v < n; v++) {
            for (int t = 0; t < coeurs; t++) cb[v] += partiel[t][v];
            cb[v] = cb[v] / 2 / norme;
        }
        return cb;
    }

    // ------------------------------------------------------------------ PageRank

    double[] pagerank(double a, double epsilon, int maxIter) {
        double[] pr = new double[n], nouveau = new double[n];
        Arrays.fill(pr, 1.0 / n);
        for (int it = 0; it < maxIter; it++) {
            double perdu = 0;                                       // masse des sommets sans voisin
            for (int u = 0; u < n; u++) if (degre(u) == 0) perdu += pr[u];
            Arrays.fill(nouveau, (1 - a) / n + a * perdu / n);
            for (int u = 0; u < n; u++) {
                int dg = degre(u);
                if (dg == 0) continue;
                double part = a * pr[u] / dg;
                for (int k = debut[u]; k < debut[u + 1]; k++) nouveau[adj[k]] += part;
            }
            double variation = 0;
            for (int u = 0; u < n; u++) variation += Math.abs(nouveau[u] - pr[u]);
            double[] tmp = pr; pr = nouveau; nouveau = tmp;
            if (variation < epsilon) { break; }
        }
        return pr;
    }

    // ------------------------------------------------------------------ programme principal

}
