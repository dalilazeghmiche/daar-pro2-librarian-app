import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * DAAR Projet 2 - Etapes 4 et 5 : distance de Jaccard entre livres, puis graphe de Jaccard.
 *
 * Distance du cours 3, sur les tables d'index (w, k) de deux documents D1 et D2 :
 *
 *            somme sur les mots w de  max(k1, k2) - min(k1, k2)
 *   d(D1,D2) = ------------------------------------------------      (k = 0 si w absent du document)
 *            somme sur les mots w de  max(k1, k2)
 *
 * Deux ameliorations (a justifier dans le rapport) :
 *   1. Mots vides : "the", "and", "was"... sont retires AVANT le calcul. Ils sont presents dans tous les
 *      livres anglais et dominent la somme, sans rien dire du sujet. (Ils restent dans l'index de recherche.)
 *   2. Frequences relatives : k est remplace par f = k / (nombre de mots du livre). Sinon un livre tres long
 *      (encyclopedie, 1,3 million de mots) est a distance ~1 de tous les autres, juste a cause de sa taille.
 *
 * Calcul efficace : comme max = a + b - min, on a  somme(max) = S1 + S2 - somme(min), donc
 *       d = 1 - somme(min) / (S1 + S2 - somme(min))          (S = somme des frequences = 1 ici)
 *   et somme(min) ne porte que sur les mots COMMUNS. Chaque livre est un tableau trie d'identifiants de mots ;
 *   somme(min) se calcule par fusion de deux tableaux tries en O(n1 + n2).
 *   Toutes les paires : N(N-1)/2 fusions (1,4 million pour 1700 livres), reparties sur tous les coeurs.
 *
 * Usage :
 *   java Jaccard.java                 -> calcule les distances (jaccard/distances.bin) et affiche
 *                                        la distribution + un tableau pour choisir le seuil theta
 *   java Jaccard.java 0.75            -> (re)utilise les distances deja calculees et ecrit le graphe
 *                                        pour theta = 0.75 dans jaccard/graphe.csv
 *   options : java Jaccard.java [theta] [dossierIndex] [dossierSortie]
 *
 * Sorties (dossier jaccard/) :
 *   ids.txt          les ids des livres, dans l'ordre des lignes/colonnes de la matrice
 *   distances.bin    matrice N x N de float (DataOutputStream), d[i][j]
 *   graphe.csv       aretes "id1;id2;distance" telles que distance < theta
 */
public class Jaccard {

    static final String[] MOTS_VIDES = (
        "the and was that his her with for had you not him she which but they all have this are from were " +
        "said one there when what would been their them who will more out into could then upon very some " +
        "now any our its your than only did can about other made great well over such like see may little " +
        "after before should must these come know time much how two down where own also being here those " +
        "too again shall man men way first might through what yet most even never just back without good " +
        "upon same while still old long went make came take thus every each under thought many say things " +
        "off nor whom why let ever has does done because though against himself herself itself myself " +
        "yourself themselves ourselves between both few once since till until within among around above " +
        "below why per hers ours theirs yours mine got get put told tell away last however rather quite " +
        "whose whether either neither something nothing anything everything someone anyone nobody"
    ).split("\\s+");

    public static void main(String[] args) throws Exception {
        Double theta = args.length > 0 ? Double.parseDouble(args[0].replace(',', '.')) : null;
        Path dossierIndex = Paths.get(args.length > 1 ? args[1] : "index");
        Path sortie = Paths.get(args.length > 2 ? args[2] : "jaccard");
        Files.createDirectories(sortie);
        Path fIds = sortie.resolve("ids.txt"), fDist = sortie.resolve("distances.bin");

        int[] ids;
        float[][] d;
        if (Files.exists(fDist) && Files.exists(fIds) && theta != null) {
            System.out.println("Distances deja calculees : lecture de " + fDist);
            ids = Files.readAllLines(fIds).stream().filter(s -> !s.isBlank()).mapToInt(Integer::parseInt).toArray();
            d = lireMatrice(fDist, ids.length);
        } else {
            // ---- lecture des index et vectorisation
            long t0 = System.nanoTime();
            List<Path> fichiers;
            try (Stream<Path> s = Files.list(dossierIndex)) {
                fichiers = s.filter(p -> p.getFileName().toString().matches("\\d+\\.idx")).sorted().toList();
            }
            int n = fichiers.size();
            ids = new int[n];
            int[][] mots = new int[n][];
            double[][] freq = new double[n][];
            Map<String, Integer> numero = new HashMap<>(1 << 20);
            Set<String> vides = new HashSet<>(Arrays.asList(MOTS_VIDES));
            for (int i = 0; i < n; i++) {
                ids[i] = Integer.parseInt(fichiers.get(i).getFileName().toString().replace(".idx", ""));
                lireVecteur(fichiers.get(i), vides, numero, mots, freq, i);
            }
            System.out.printf("%d livres vectorises en %.1f s (%d mots distincts hors mots vides)%n",
                    n, (System.nanoTime() - t0) / 1e9, numero.size());

            // ---- toutes les paires, en parallele (une ligne i de la matrice par tache)
            long t1 = System.nanoTime();
            d = new float[n][n];
            final float[][] dd = d;
            AtomicInteger faites = new AtomicInteger();
            long paires = (long) n * (n - 1) / 2;
            IntStream.range(0, n).parallel().forEach(i -> {
                for (int j = i + 1; j < n; j++) {
                    float x = (float) distance(mots[i], freq[i], mots[j], freq[j]);
                    dd[i][j] = x;
                    dd[j][i] = x;
                }
                int f = faites.incrementAndGet();
                if (f % 100 == 0) System.out.printf("  %d/%d lignes%n", f, n);
            });
            double sec = (System.nanoTime() - t1) / 1e9;
            System.out.printf("%d paires calculees en %.1f s (%.2f microsecondes par paire, %d coeurs)%n",
                    paires, sec, sec * 1e6 / paires, Runtime.getRuntime().availableProcessors());

            try (BufferedWriter w = Files.newBufferedWriter(fIds)) {
                for (int id : ids) { w.write(Integer.toString(id)); w.write('\n'); }
            }
            ecrireMatrice(fDist, d);
            afficherDistribution(d);
        }

        afficherTableauTheta(d);
        afficherPairesProches(ids, d, Paths.get("books", "metadata.csv"), 25);

        if (theta != null) ecrireGraphe(sortie.resolve("graphe.csv"), ids, d, theta);
        else System.out.println("\nChoisissez theta puis lancez : java Jaccard.java <theta>   (ex. java Jaccard.java 0.75)");
    }

    // ------------------------------------------------------------------ distance

    /** d = 1 - somme(min) / (S1 + S2 - somme(min)), avec fusion de deux listes de mots triees. */
    static double distance(int[] m1, double[] f1, int[] m2, double[] f2) {
        double somMin = 0;
        int a = 0, b = 0;
        while (a < m1.length && b < m2.length) {
            if (m1[a] == m2[b]) { somMin += Math.min(f1[a], f2[b]); a++; b++; }
            else if (m1[a] < m2[b]) a++;
            else b++;
        }
        double somMax = 2.0 - somMin;                   // S1 = S2 = 1 (frequences relatives)
        return somMax == 0 ? 0 : 1.0 - somMin / somMax;
    }

    /** Lit un .idx, retire les mots vides, numerote les mots, normalise en frequences, trie par numero. */
    static void lireVecteur(Path f, Set<String> vides, Map<String, Integer> numero,
                            int[][] mots, double[][] freq, int i) throws IOException {
        List<int[]> paires = new ArrayList<>();
        long total = 0;
        try (BufferedReader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            String l;
            while ((l = r.readLine()) != null) {
                int tab = l.indexOf('\t');
                String mot = l.substring(0, tab);
                if (vides.contains(mot)) continue;
                int k = Integer.parseInt(l, tab + 1, l.length(), 10);
                paires.add(new int[]{numero.computeIfAbsent(mot, m -> numero.size()), k});
                total += k;
            }
        }
        paires.sort((x, y) -> Integer.compare(x[0], y[0]));
        mots[i] = new int[paires.size()];
        freq[i] = new double[paires.size()];
        for (int p = 0; p < paires.size(); p++) {
            mots[i][p] = paires.get(p)[0];
            freq[i][p] = total == 0 ? 0 : (double) paires.get(p)[1] / total;
        }
    }

    // ------------------------------------------------------------------ choix de theta

    static void afficherDistribution(float[][] d) {
        int n = d.length;
        int[] hist = new int[20];                        // tranches de 0.05
        double somme = 0, somme2 = 0; long nb = 0;
        float mini = 1, maxi = 0;
        for (int i = 0; i < n; i++) for (int j = i + 1; j < n; j++) {
            float x = d[i][j];
            hist[Math.min(19, (int) (x * 20))]++;
            somme += x; somme2 += x * x; nb++;
            mini = Math.min(mini, x); maxi = Math.max(maxi, x);
        }
        double moy = somme / nb, ecart = Math.sqrt(somme2 / nb - moy * moy);
        System.out.printf("%nDistribution des distances : min %.3f, max %.3f, moyenne %.3f, ecart-type %.3f%n", mini, maxi, moy, ecart);
        int histMax = Arrays.stream(hist).max().orElse(1);
        for (int t = 0; t < 20; t++) {
            if (hist[t] == 0) continue;
            System.out.printf("  [%.2f-%.2f[ %8d  %s%n", t / 20.0, (t + 1) / 20.0, hist[t], "#".repeat(Math.max(1, 60 * hist[t] / histMax)));
        }
    }

    static void afficherTableauTheta(float[][] d) {
        int n = d.length;
        System.out.printf("%n  theta   aretes   degre moyen   livres isoles%n");
        List<Double> seuils = new ArrayList<>();
        for (int t = 50; t <= 95; t += 5) seuils.add(t / 100.0);
        for (int t = 66; t <= 74; t++) if (t != 70) seuils.add(t / 100.0);   // zone de transition, plus fine
        seuils.sort(null);
        for (double theta : seuils) {
            long aretes = 0;
            int isoles = 0;
            for (int i = 0; i < n; i++) {
                int deg = 0;
                for (int j = 0; j < n; j++) if (j != i && d[i][j] < theta) deg++;
                aretes += deg;
                if (deg == 0) isoles++;
            }
            aretes /= 2;
            System.out.printf("  %.2f  %8d   %10.1f   %8d%n", theta, aretes, 2.0 * aretes / n, isoles);
        }
    }

    /**
     * Graphe de Jaccard : arete {i, j} ssi d(i, j) < theta (definition du cours).
     * Amelioration : un livre qui serait isole est relie a son plus proche voisin, pour que
     * chaque livre ait au moins une suggestion. Ces aretes sont marquees "plusProche" dans le fichier.
     */
    static void ecrireGraphe(Path f, int[] ids, float[][] d, double theta) throws IOException {
        int n = ids.length;
        boolean[][] arete = new boolean[n][n];
        boolean[][] ajoutee = new boolean[n][n];
        int[] degre = new int[n];
        for (int i = 0; i < n; i++)
            for (int j = i + 1; j < n; j++)
                if (d[i][j] < theta) { arete[i][j] = arete[j][i] = true; degre[i]++; degre[j]++; }
        int ajouts = 0;
        for (int i = 0; i < n; i++) {
            if (degre[i] > 0) continue;
            int pp = -1;
            for (int j = 0; j < n; j++) if (j != i && (pp < 0 || d[i][j] < d[i][pp])) pp = j;
            if (pp >= 0 && !arete[i][pp]) {
                arete[i][pp] = arete[pp][i] = true;
                ajoutee[i][pp] = ajoutee[pp][i] = true;
                degre[i]++; degre[pp]++;
                ajouts++;
            }
        }
        long total = 0;
        try (BufferedWriter w = Files.newBufferedWriter(f)) {
            w.write("id1;id2;distance;type\n");
            for (int i = 0; i < n; i++)
                for (int j = i + 1; j < n; j++)
                    if (arete[i][j]) {
                        w.write(ids[i] + ";" + ids[j] + ";" + String.format(java.util.Locale.ROOT, "%.4f", d[i][j])
                                + ";" + (ajoutee[i][j] ? "plusProche" : "seuil") + "\n");
                        total++;
                    }
        }
        System.out.printf("%nGraphe de Jaccard pour theta = %.2f : %d aretes (dont %d ajoutees pour les livres isoles) -> %s%n",
                theta, total, ajouts, f.toAbsolutePath());

        // composantes connexes (parcours en largeur) : utile pour la centralite (etape 6)
        int[] comp = new int[n];
        Arrays.fill(comp, -1);
        List<Integer> tailles = new ArrayList<>();
        for (int s = 0; s < n; s++) {
            if (comp[s] >= 0) continue;
            int c = tailles.size(), taille = 0;
            java.util.ArrayDeque<Integer> file = new java.util.ArrayDeque<>();
            file.add(s); comp[s] = c;
            while (!file.isEmpty()) {
                int u = file.poll(); taille++;
                for (int v = 0; v < n; v++) if (arete[u][v] && comp[v] < 0) { comp[v] = c; file.add(v); }
            }
            tailles.add(taille);
        }
        tailles.sort(java.util.Collections.reverseOrder());
        System.out.printf("Degre min %d, max %d, moyen %.1f ; %d composantes connexes, les plus grandes : %s%n",
                Arrays.stream(degre).min().orElse(0), Arrays.stream(degre).max().orElse(0),
                2.0 * total / n, tailles.size(), tailles.subList(0, Math.min(8, tailles.size())));
    }

    /** Les paires les plus proches, avec leurs titres : doublons probables et exemples pour le rapport. */
    static void afficherPairesProches(int[] ids, float[][] d, Path meta, int combien) throws IOException {
        Map<Integer, String> titres = new HashMap<>();
        if (Files.exists(meta)) {
            List<String> lignes = Files.readAllLines(meta, StandardCharsets.UTF_8);
            for (String l : lignes.subList(1, lignes.size())) {
                String[] c = l.split(";");
                if (c.length >= 2) titres.put(Integer.parseInt(c[0]), c[1].length() > 55 ? c[1].substring(0, 55) + "..." : c[1]);
            }
        }
        List<int[]> paires = new ArrayList<>();
        for (int i = 0; i < ids.length; i++) for (int j = i + 1; j < ids.length; j++) paires.add(new int[]{i, j});
        paires.sort((a, b) -> Float.compare(d[a[0]][a[1]], d[b[0]][b[1]]));
        System.out.printf("%nLes %d paires de livres les plus proches :%n", combien);
        for (int p = 0; p < Math.min(combien, paires.size()); p++) {
            int i = paires.get(p)[0], j = paires.get(p)[1];
            System.out.printf("  %.3f  %6d %-58s  %6d %s%n", d[i][j], ids[i], titres.getOrDefault(ids[i], ""),
                    ids[j], titres.getOrDefault(ids[j], ""));
        }
    }

    // ------------------------------------------------------------------ fichiers binaires

    static void ecrireMatrice(Path f, float[][] d) throws IOException {
        try (DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(Files.newOutputStream(f), 1 << 16))) {
            for (float[] ligne : d) for (float x : ligne) out.writeFloat(x);
        }
    }

    static float[][] lireMatrice(Path f, int n) throws IOException {
        float[][] d = new float[n][n];
        try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(Files.newInputStream(f), 1 << 16))) {
            for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) d[i][j] = in.readFloat();
        }
        return d;
    }
}
