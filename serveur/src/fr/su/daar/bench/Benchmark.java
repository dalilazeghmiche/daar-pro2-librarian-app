package fr.su.daar.bench;

import fr.su.daar.moteur.IndexInverse;
import fr.su.daar.regex.DFA;
import fr.su.daar.regex.KMP;
import fr.su.daar.regex.Minimizer;
import fr.su.daar.regex.NFA;
import fr.su.daar.regex.NFAMatcher;
import fr.su.daar.regex.RegexNode;
import fr.su.daar.regex.RegexParser;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * DAAR Projet 2 - Tests de performance (testbeds du rapport).
 *
 * Pour des bibliotheques de taille croissante (100, 250, 500, 1000, toute la bibliotheque),
 * tirees au hasard (graine fixe, donc reproductible) parmi les livres de books/ :
 *
 *   A. Precalcul  : indexation d'un livre, index inverse, Jaccard (toutes les paires), graphe + centralite.
 *   B. Requetes   : mot-cle (index inverse), "contient" (KMP sur le vocabulaire), RegEx sur l'index (DFA minimal),
 *                   RegEx sur le texte complet (comme egrep).
 *   C. Moteurs    : pour une meme RegEx sur tout le vocabulaire : DFA minimal vs simulation du NFA
 *                   (et KMP quand la RegEx est un simple facteur).
 *
 * Chaque mesure est repetee (apres un echauffement de la JVM) : on donne moyenne et ecart-type.
 * Resultats bruts dans benchmark/*.csv (une ligne par mesure) ; resume dans benchmark/resume.txt.
 *
 * Lancement : depuis serveur/, avec benchmark.bat (Windows) ou ./benchmark.sh (Linux/macOS).
 * Duree : environ 5 a 10 minutes pour 1684 livres.
 */
public class Benchmark {

    static final int REP = 10;            // repetitions des mesures rapides
    static final int REP_LENT = 3;        // repetitions des mesures lentes (texte complet, Jaccard)
    static final int ECHAUFFEMENT = 2;

    static final String[] MOTS = {"sargon", "babylon", "whale", "elizabeth", "science", "love"};
    static final String[] FACTEURS = {"babylon", "ship", "tion"};
    static final String[] REGEX_INDEX = {"s(a|g|r)+on", "whal(e|es|ing)", "(a|e|i|o|u)(a|e|i|o|u)(a|e|i|o|u)", "pre.*tion"};
    static final String[] REGEX_TEXTE = {"Sargon", "S(a|g|r)+on", "whal(e|es|ing)"};

    final Path donnees, sortie;
    final StringBuilder resume = new StringBuilder();
    BufferedWriter csvPre, csvReq, csvMot;

    Benchmark(Path donnees) {
        this.donnees = donnees;
        this.sortie = Paths.get("benchmark");
    }

    public static void main(String[] args) throws Exception {
        Path donnees = Paths.get(args.length > 0 ? args[0] : "..").toAbsolutePath().normalize();
        new Benchmark(donnees).lancer();
    }

    void lancer() throws Exception {
        Files.createDirectories(sortie);
        List<Integer> tous = new ArrayList<>();
        try (Stream<Path> s = Files.list(donnees.resolve("index"))) {
            s.map(p -> p.getFileName().toString()).filter(n -> n.matches("\\d+\\.idx"))
             .forEach(n -> tous.add(Integer.parseInt(n.replace(".idx", ""))));
        }
        Collections.sort(tous);
        Collections.shuffle(tous, new Random(2026));          // tirage reproductible
        int total = tous.size();
        List<Integer> tailles = new ArrayList<>();
        for (int t : new int[]{100, 250, 500, 1000}) if (t < total) tailles.add(t);
        tailles.add(total);

        ligne("Tests de performance DAAR - %d livres, tailles %s, %d coeurs, Java %s, memoire max %d Mo%n",
                total, tailles, Runtime.getRuntime().availableProcessors(), System.getProperty("java.version"),
                Runtime.getRuntime().maxMemory() >> 20);

        try (BufferedWriter p = Files.newBufferedWriter(sortie.resolve("precalcul.csv"));
             BufferedWriter r = Files.newBufferedWriter(sortie.resolve("requetes.csv"));
             BufferedWriter m = Files.newBufferedWriter(sortie.resolve("moteurs.csv"))) {
            csvPre = p; csvReq = r; csvMot = m;
            p.write("taille;etape;repetition;ms\n");
            r.write("taille;type;requete;repetition;ms;nbResultats\n");
            m.write("requete;moteur;repetition;ms;nbMotsReconnus;etatsAutomate\n");

            indexationParLivre(tous);
            for (int t : tailles) {
                List<Integer> ids = tous.subList(0, t);
                ligne("%n===== %d livres =====%n", t);
                IndexInverse idx = precalculIndex(t, ids);
                precalculJaccardCentralite(t, ids);
                requetes(t, ids, idx);
                if (t == total) moteurs(idx);
                idx = null;
                System.gc();
            }
        }
        Files.writeString(sortie.resolve("resume.txt"), resume.toString());
        System.out.println("\nTermine. Resultats dans " + sortie.toAbsolutePath()
                + "\nEnvoyez le dossier benchmark (fichiers .csv + resume.txt).");
    }

    // =====================================================================
    //  A. Precalcul
    // =====================================================================

    /** Temps d'indexation de chaque livre (lecture + decoupage + comptage), sur un seul thread. */
    void indexationParLivre(List<Integer> ids) throws IOException {
        ligne("%nIndexation de chaque livre (1 thread) ...%n");
        for (int i = 0; i < 20; i++) indexer(Files.readAllBytes(texte(ids.get(i))));    // echauffement
        double[] ms = new double[ids.size()];
        long mots = 0;
        for (int i = 0; i < ids.size(); i++) {
            long t0 = System.nanoTime();
            byte[] b = Files.readAllBytes(texte(ids.get(i)));
            Map<String, Integer> idx = indexer(b);
            ms[i] = (System.nanoTime() - t0) / 1e6;
            mots += b.length;
            csvPre.write(ids.size() + ";indexation_livre;" + i + ";" + f(ms[i]) + "\n");
            if (idx.isEmpty()) ligne("  (livre %d vide)%n", ids.get(i));
        }
        ligne("  par livre : %s  (total %.1f s pour %.0f Mo de texte)%n", stats(ms),
                Arrays.stream(ms).sum() / 1000, mots / 1e6);
    }

    static Map<String, Integer> indexer(byte[] texte) {
        Map<String, Integer> index = new HashMap<>();
        StringBuilder mot = new StringBuilder();
        for (int i = 0; i <= texte.length; i++) {
            int c = i < texte.length ? texte[i] : ' ';
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) mot.append((char) (c | 0x20));
            else if (mot.length() > 0) {
                if (mot.length() >= 3) index.merge(mot.toString(), 1, Integer::sum);
                mot.setLength(0);
            }
        }
        return index;
    }

    IndexInverse precalculIndex(int t, List<Integer> ids) throws IOException {
        List<Path> fichiers = ids.stream().map(this::idx).toList();
        double[] ms = new double[REP_LENT];
        IndexInverse res = null;
        for (int r = 0; r < REP_LENT; r++) {
            long t0 = System.nanoTime();
            res = IndexInverse.charger(fichiers);
            ms[r] = (System.nanoTime() - t0) / 1e6;
            csvPre.write(t + ";index_inverse;" + r + ";" + f(ms[r]) + "\n");
        }
        ligne("  index inverse           %s   (%d mots distincts, %d entrees)%n", stats(ms), res.nbMots(), res.nbEntrees());
        return res;
    }

    void precalculJaccardCentralite(int t, List<Integer> ids) throws IOException {
        // vectorisation (identique a Jaccard.java : mots vides retires, frequences relatives)
        Set<String> vides = new HashSet<>(Arrays.asList(MOTS_VIDES));
        Map<String, Integer> numero = new HashMap<>(1 << 20);
        int n = ids.size();
        int[][] mots = new int[n][];
        double[][] freq = new double[n][];
        for (int i = 0; i < n; i++) vectoriser(idx(ids.get(i)), vides, numero, mots, freq, i);

        double[] ms = new double[REP_LENT];
        float[][] d = new float[n][n];
        for (int r = 0; r < REP_LENT; r++) {
            long t0 = System.nanoTime();
            IntStream.range(0, n).parallel().forEach(i -> {
                for (int j = i + 1; j < n; j++) {
                    float x = (float) distance(mots[i], freq[i], mots[j], freq[j]);
                    d[i][j] = x; d[j][i] = x;
                }
            });
            ms[r] = (System.nanoTime() - t0) / 1e6;
            csvPre.write(t + ";jaccard;" + r + ";" + f(ms[r]) + "\n");
        }
        long paires = (long) n * (n - 1) / 2;
        ligne("  Jaccard (%d paires)  %s   (%.2f us par paire)%n", paires, stats(ms), moyenne(ms) * 1000 / paires);

        // graphe theta = 0.70 + plus proche voisin pour les isoles (comme Jaccard.java)
        List<int[]> aretes = new ArrayList<>();
        int[] deg = new int[n];
        for (int i = 0; i < n; i++) for (int j = i + 1; j < n; j++)
            if (d[i][j] < 0.70) { aretes.add(new int[]{i, j}); deg[i]++; deg[j]++; }
        for (int i = 0; i < n; i++) if (deg[i] == 0) {
            int pp = -1;
            for (int j = 0; j < n; j++) if (j != i && (pp < 0 || d[i][j] < d[i][pp])) pp = j;
            if (pp >= 0) { aretes.add(new int[]{i, pp}); deg[i]++; deg[pp]++; }
        }
        Centralite g = new Centralite(n, aretes);
        double[] clo = new double[REP_LENT], bet = new double[REP_LENT], pr = new double[REP_LENT];
        for (int r = 0; r < REP_LENT; r++) {
            long t0 = System.nanoTime(); g.closeness();
            long t1 = System.nanoTime(); g.betweenness();
            long t2 = System.nanoTime(); g.pagerank(0.85, 1e-12, 1000);
            long t3 = System.nanoTime();
            clo[r] = (t1 - t0) / 1e6; bet[r] = (t2 - t1) / 1e6; pr[r] = (t3 - t2) / 1e6;
            csvPre.write(t + ";closeness;" + r + ";" + f(clo[r]) + "\n");
            csvPre.write(t + ";betweenness;" + r + ";" + f(bet[r]) + "\n");
            csvPre.write(t + ";pagerank;" + r + ";" + f(pr[r]) + "\n");
        }
        ligne("  graphe : %d aretes ; closeness %s ; betweenness %s ; pagerank %s%n",
                aretes.size(), stats(clo), stats(bet), stats(pr));
    }

    // =====================================================================
    //  B. Requetes
    // =====================================================================

    void requetes(int t, List<Integer> ids, IndexInverse idx) throws IOException {
        for (String w : MOTS)
            mesurer(t, "motcle", w, REP, () -> fusionner(idx, idx.chercher(w).length > 0 ? List.of(w) : List.of()));
        for (String s : FACTEURS) {
            KMP kmp = new KMP(s.getBytes(StandardCharsets.ISO_8859_1));
            mesurer(t, "contient", s, REP, () -> fusionner(idx, filtrer(idx, kmp::matches)));
        }
        for (String re : REGEX_INDEX)
            mesurer(t, "regex_index", re, REP, () -> {
                DFA dfa = Minimizer.minimize(DFA.fromNFA(NFA.fromTree(RegexParser.parse(re)), false));
                return fusionner(idx, filtrer(idx, dfa::accepts));
            });
        for (String re : REGEX_TEXTE)
            mesurer(t, "regex_texte", re, REP_LENT, () -> regexTexte(ids, re));
    }

    interface Requete { int executer() throws IOException; }

    void mesurer(int t, String type, String requete, int rep, Requete q) throws IOException {
        int nb = 0;
        for (int e = 0; e < (rep == REP_LENT ? 1 : ECHAUFFEMENT); e++) nb = q.executer();
        double[] ms = new double[rep];
        for (int r = 0; r < rep; r++) {
            long t0 = System.nanoTime();
            nb = q.executer();
            ms[r] = (System.nanoTime() - t0) / 1e6;
            csvReq.write(t + ";" + type + ";" + requete + ";" + r + ";" + f(ms[r]) + ";" + nb + "\n");
        }
        csvReq.flush();
        ligne("  %-12s %-38s %s   %d livres%n", type, requete, stats(ms), nb);
    }

    static List<String> filtrer(IndexInverse idx, Predicate<String> p) {
        List<String> res = new ArrayList<>();
        for (String w : idx.vocabulaire()) if (p.test(w)) res.add(w);
        return res;
    }

    /** Union des listes de l'index inverse + tri par occurrences ; renvoie le nombre de livres trouves. */
    static int fusionner(IndexInverse idx, List<String> mots) {
        Map<Integer, Long> occ = new HashMap<>();
        for (String w : mots) {
            int[] p = idx.chercher(w);
            for (int i = 0; i < p.length; i += 2) occ.merge(p[i], (long) p[i + 1], Long::sum);
        }
        List<Map.Entry<Integer, Long>> l = new ArrayList<>(occ.entrySet());
        l.sort(Map.Entry.<Integer, Long>comparingByValue().reversed());
        return l.size();
    }

    /** RegEx sur le texte complet : comme le serveur (KMP si simple facteur, sinon DFA minimal en mode recherche). */
    int regexTexte(List<Integer> ids, String re) {
        RegexNode arbre = RegexParser.parse(re);
        byte[] facteur = KMP.literalOf(arbre);
        DFA dfa = facteur == null ? Minimizer.minimize(DFA.fromNFA(NFA.fromTree(arbre), true)) : null;
        return (int) ids.parallelStream().filter(id -> {
            try {
                byte[] buf = Files.readAllBytes(texte(id));
                KMP kmp = facteur != null ? new KMP(facteur) : null;
                int debut = 0;
                for (int i = 0; i <= buf.length; i++) {
                    if (i == buf.length || buf[i] == '\n') {
                        if (kmp != null ? kmp.matches(buf, debut, i) : dfa.matches(buf, debut, i)) return true;
                        debut = i + 1;
                    }
                }
                return false;
            } catch (IOException e) { return false; }
        }).count();
    }

    // =====================================================================
    //  C. Comparaison des moteurs RegEx sur tout le vocabulaire
    // =====================================================================

    void moteurs(IndexInverse idx) throws IOException {
        ligne("%nMoteurs RegEx sur les %d mots du vocabulaire :%n", idx.vocabulaire().length);
        List<String> tests = new ArrayList<>(Arrays.asList(REGEX_INDEX));
        tests.add("babylon");                                           // simple facteur : KMP possible
        for (String re : tests) {
            RegexNode arbre = RegexParser.parse(re);
            NFA nfa = NFA.fromTree(arbre);
            DFA dfa = Minimizer.minimize(DFA.fromNFA(nfa, false));
            NFAMatcher nm = new NFAMatcher(nfa);
            moteur(re, "construction DFA minimal", dfa.size(), () -> Minimizer.minimize(DFA.fromNFA(NFA.fromTree(RegexParser.parse(re)), false)).size());
            moteur(re, "DFA minimal", dfa.size(), () -> filtrer(idx, dfa::accepts).size());
            moteur(re, "simulation NFA", nfa.size(), () -> filtrer(idx, w -> {
                byte[] b = w.getBytes(StandardCharsets.ISO_8859_1);
                return nm.longestMatchEnd(b, 0, b.length) == b.length;
            }).size());
            byte[] fac = KMP.literalOf(arbre);
            if (fac != null) {
                KMP kmp = new KMP(fac);
                // mot exact : comparaison directe ; KMP : mots CONTENANT le facteur (autre semantique, plus de resultats)
                moteur(re, "String.equals (mot exact)", 0, () -> filtrer(idx, w -> w.equals(re)).size());
                moteur(re, "KMP (contient le facteur)", fac.length, () -> filtrer(idx, kmp::matches).size());
                moteur(re, "table de hachage (index)", 0, () -> idx.chercher(re).length > 0 ? 1 : 0);
            }
        }
    }

    interface Mesure { int executer(); }

    void moteur(String re, String nom, int etats, Mesure m) throws IOException {
        int nb = 0;
        for (int e = 0; e < ECHAUFFEMENT; e++) nb = m.executer();
        double[] ms = new double[REP];
        for (int r = 0; r < REP; r++) {
            long t0 = System.nanoTime();
            nb = m.executer();
            ms[r] = (System.nanoTime() - t0) / 1e6;
            csvMot.write(re + ";" + nom + ";" + r + ";" + f(ms[r]) + ";" + nb + ";" + etats + "\n");
        }
        ligne("  %-36s %-26s %s   (%d etats, resultat %d)%n", re, nom, stats(ms), etats, nb);
    }

    // =====================================================================
    //  Jaccard (meme code que Jaccard.java)
    // =====================================================================

    static double distance(int[] m1, double[] f1, int[] m2, double[] f2) {
        double somMin = 0;
        int a = 0, b = 0;
        while (a < m1.length && b < m2.length) {
            if (m1[a] == m2[b]) { somMin += Math.min(f1[a], f2[b]); a++; b++; }
            else if (m1[a] < m2[b]) a++;
            else b++;
        }
        double somMax = 2.0 - somMin;
        return somMax == 0 ? 0 : 1.0 - somMin / somMax;
    }

    static void vectoriser(Path f, Set<String> vides, Map<String, Integer> numero,
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
                paires.add(new int[]{numero.computeIfAbsent(mot, x -> numero.size()), k});
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

    // =====================================================================
    //  Outils
    // =====================================================================

    Path idx(int id)   { return donnees.resolve("index/" + id + ".idx"); }
    Path texte(int id) { return donnees.resolve("books/" + id + ".txt"); }

    static double moyenne(double[] x) { return Arrays.stream(x).average().orElse(0); }

    static double ecart(double[] x) {
        double m = moyenne(x), s = 0;
        for (double v : x) s += (v - m) * (v - m);
        return x.length > 1 ? Math.sqrt(s / (x.length - 1)) : 0;
    }

    static String stats(double[] ms) {
        return String.format(Locale.ROOT, "%10.3f ms +- %8.3f", moyenne(ms), ecart(ms));
    }

    static String f(double ms) { return String.format(Locale.ROOT, "%.4f", ms); }

    void ligne(String format, Object... args) {
        String s = String.format(Locale.ROOT, format, args);
        System.out.print(s);
        resume.append(s);
    }
}
