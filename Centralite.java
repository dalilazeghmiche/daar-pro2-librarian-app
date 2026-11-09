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
            if (variation < epsilon) { System.out.printf("  PageRank : convergence en %d iterations%n", it + 1); break; }
        }
        return pr;
    }

    // ------------------------------------------------------------------ programme principal

    public static void main(String[] args) throws IOException {
        if (args.length > 0 && args[0].equals("test")) { tests(); return; }
        Path fGraphe = Paths.get("jaccard", "graphe.csv");
        Path fIds = Paths.get("jaccard", "ids.txt");

        int[] ids = Files.readAllLines(fIds).stream().filter(s -> !s.isBlank()).mapToInt(Integer::parseInt).toArray();
        Map<Integer, Integer> rang = new HashMap<>();
        for (int i = 0; i < ids.length; i++) rang.put(ids[i], i);
        List<int[]> aretes = new ArrayList<>();
        List<String> lignes = Files.readAllLines(fGraphe);
        for (String l : lignes.subList(1, lignes.size())) {
            String[] c = l.split(";");
            Integer a = rang.get(Integer.parseInt(c[0])), b = rang.get(Integer.parseInt(c[1]));
            if (a != null && b != null) aretes.add(new int[]{a, b});
        }
        Centralite g = new Centralite(ids.length, aretes);
        System.out.printf("Graphe : %d livres, %d aretes%n", g.n, aretes.size());

        long t0 = System.nanoTime();
        double[] clo = g.closeness();
        long t1 = System.nanoTime();
        double[] bet = g.betweenness();
        long t2 = System.nanoTime();
        double[] pr = g.pagerank(0.85, 1e-12, 1000);
        long t3 = System.nanoTime();
        System.out.printf("Temps : closeness %.2f s, betweenness %.2f s, pagerank %.2f s%n",
                (t1 - t0) / 1e9, (t2 - t1) / 1e9, (t3 - t2) / 1e9);

        try (BufferedWriter w = Files.newBufferedWriter(Paths.get("jaccard", "centralite.csv"))) {
            w.write("id;degre;closeness;betweenness;pagerank\n");
            for (int i = 0; i < g.n; i++)
                w.write(String.format(Locale.ROOT, "%d;%d;%.6f;%.8f;%.8f%n", ids[i], g.degre(i), clo[i], bet[i], pr[i]));
        }
        System.out.println("Ecrit : jaccard/centralite.csv");

        Map<Integer, String> titres = lireTitres(Paths.get("books", "metadata.csv"));
        afficherTop("CLOSENESS", clo, ids, g, titres);
        afficherTop("BETWEENNESS", bet, ids, g, titres);
        afficherTop("PAGERANK", pr, ids, g, titres);
    }

    static void afficherTop(String nom, double[] score, int[] ids, Centralite g, Map<Integer, String> titres) {
        Integer[] ordre = new Integer[score.length];
        for (int i = 0; i < ordre.length; i++) ordre[i] = i;
        Arrays.sort(ordre, (a, b) -> Double.compare(score[b], score[a]));
        System.out.printf("%nTop 10 %s :%n", nom);
        for (int r = 0; r < Math.min(10, ordre.length); r++) {
            int i = ordre[r];
            String t = titres.getOrDefault(ids[i], "");
            System.out.printf(Locale.ROOT, "  %2d. %.6f  deg %4d  %6d  %s%n", r + 1, score[i], g.degre(i), ids[i],
                    t.length() > 70 ? t.substring(0, 70) + "..." : t);
        }
    }

    static Map<Integer, String> lireTitres(Path meta) throws IOException {
        Map<Integer, String> titres = new HashMap<>();
        if (!Files.exists(meta)) return titres;
        List<String> lignes = Files.readAllLines(meta, StandardCharsets.UTF_8);
        for (String l : lignes.subList(1, lignes.size())) {
            String[] c = l.split(";");
            if (c.length >= 3) titres.put(Integer.parseInt(c[0]), c[1] + " (" + c[2] + ")");
        }
        return titres;
    }

    // ------------------------------------------------------------------ verification a la main

    /** Petits graphes dont on connait les valeurs exactes (a reprendre dans le rapport). */
    static void tests() {
        // Chemin 0-1-2-3-4
        Centralite chemin = new Centralite(5, List.of(new int[]{0, 1}, new int[]{1, 2}, new int[]{2, 3}, new int[]{3, 4}));
        // closeness du milieu : 4 / (2+1+1+2) = 0.6667 ; extremite : 4 / (1+2+3+4) = 0.4
        verifier("chemin closeness(2)", chemin.closeness()[2], 4.0 / 6);
        verifier("chemin closeness(0)", chemin.closeness()[0], 0.4);
        // betweenness brute du milieu = 4 paires {0,3},{0,4},{1,3},{1,4} -> 4 / ((4*3)/2) = 0.6667
        verifier("chemin betweenness(2)", chemin.betweenness()[2], 4.0 / 6);
        // sommet 1 : paires {0,2},{0,3},{0,4} -> 3/6
        verifier("chemin betweenness(1)", chemin.betweenness()[1], 3.0 / 6);
        verifier("chemin betweenness(0)", chemin.betweenness()[0], 0);

        // Etoile : centre 0 relie a 1..4
        Centralite etoile = new Centralite(5, List.of(new int[]{0, 1}, new int[]{0, 2}, new int[]{0, 3}, new int[]{0, 4}));
        verifier("etoile closeness(0)", etoile.closeness()[0], 1.0);
        verifier("etoile betweenness(0)", etoile.betweenness()[0], 1.0);   // tous les 6 chemins passent par 0

        // Carre 0-1-2-3-0 : deux plus courts chemins entre 0 et 2, chacun compte pour 1/2
        Centralite carre = new Centralite(4, List.of(new int[]{0, 1}, new int[]{1, 2}, new int[]{2, 3}, new int[]{3, 0}));
        // seule la paire {0,2} peut passer par 1, et 1 seul de ses 2 plus courts chemins y passe : brut 1/2, /3
        verifier("carre betweenness(1)", carre.betweenness()[1], 0.5 / 3);
        double[] prc = carre.pagerank(0.85, 1e-14, 1000);
        verifier("carre pagerank(0)", prc[0], 0.25);                      // graphe regulier : uniforme

        // Deux composantes : triangle {0,1,2} + arete {3,4} (Wasserman-Faust)
        Centralite deux = new Centralite(5, List.of(new int[]{0, 1}, new int[]{1, 2}, new int[]{2, 0}, new int[]{3, 4}));
        verifier("2 composantes closeness(0)", deux.closeness()[0], (2.0 / 4) * (2.0 / 2));
        verifier("2 composantes closeness(3)", deux.closeness()[3], (1.0 / 4) * (1.0 / 1));
        double somme = Arrays.stream(deux.pagerank(0.85, 1e-14, 1000)).sum();
        verifier("pagerank somme = 1", somme, 1.0);
    }

    static void verifier(String nom, double obtenu, double attendu) {
        boolean ok = Math.abs(obtenu - attendu) < 1e-9;
        System.out.printf(Locale.ROOT, "%s %-28s obtenu %.6f  attendu %.6f%n", ok ? "OK  " : "FAUX", nom, obtenu, attendu);
    }
}
