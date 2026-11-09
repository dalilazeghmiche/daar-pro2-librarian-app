import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * DAAR Projet 2 - Etape 3 : index inverse (mot -> livres qui le contiennent).
 *
 * L'Indexeur (etape 2) produit, pour chaque livre D, sa table (w, k).
 * L'index inverse "retourne" ces tables : pour chaque mot w, la liste des (D, k) tels que
 * w apparait k fois dans D. Une recherche par mot-cle devient alors un simple acces
 * dans une table de hachage : O(1) en moyenne + O(r) pour lire les r livres trouves,
 * au lieu de relire les 1700 livres a chaque requete.
 *
 * Representation compacte (memoire) : pour chaque mot, un tableau int[] alterne
 *   [id1, k1, id2, k2, ...]  trie par k decroissant (le livre le plus pertinent en premier).
 *
 * Cette classe sera reutilisee telle quelle par le serveur Spring Boot.
 *
 * Test en ligne de commande :
 *   java IndexInverse.java index books/metadata.csv babylon sargon elizabeth
 */
public class IndexInverse {

    private final Map<String, int[]> postings;
    private final String[] vocabulaire;          // tous les mots distincts, tries (pour la recherche RegEx)
    private final int nbLivres;

    private IndexInverse(Map<String, int[]> postings, int nbLivres) {
        this.postings = postings;
        this.nbLivres = nbLivres;
        this.vocabulaire = postings.keySet().toArray(new String[0]);
        Arrays.sort(this.vocabulaire);
    }

    /** Construit l'index inverse a partir du dossier des fichiers <id>.idx. */
    public static IndexInverse charger(Path dossierIndex) throws IOException {
        List<Path> fichiers;
        try (Stream<Path> s = Files.list(dossierIndex)) {
            fichiers = s.filter(p -> p.getFileName().toString().matches("\\d+\\.idx")).sorted().toList();
        }
        // 1re passe : on accumule les (id, k) de chaque mot dans des listes extensibles
        Map<String, IntListe> tmp = new HashMap<>(1 << 20);
        for (Path f : fichiers) {
            int id = Integer.parseInt(f.getFileName().toString().replace(".idx", ""));
            try (BufferedReader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                String ligne;
                while ((ligne = r.readLine()) != null) {
                    int tab = ligne.indexOf('\t');
                    String mot = ligne.substring(0, tab);
                    int k = Integer.parseInt(ligne, tab + 1, ligne.length(), 10);
                    tmp.computeIfAbsent(mot, m -> new IntListe()).ajouter(id, k);
                }
            }
        }
        // 2e passe : listes -> tableaux compacts, tries par nombre d'occurrences decroissant
        Map<String, int[]> postings = new HashMap<>(tmp.size() * 2);
        for (var e : tmp.entrySet()) postings.put(e.getKey(), e.getValue().trieParOccurrences());
        return new IndexInverse(postings, fichiers.size());
    }

    /** Livres contenant exactement le mot (en minuscules) : [id1, k1, id2, k2, ...], vide si aucun. */
    public int[] chercher(String mot) {
        int[] p = postings.get(mot.toLowerCase());
        return p != null ? p : new int[0];
    }

    public String[] vocabulaire() { return vocabulaire; }
    public int nbLivres()         { return nbLivres; }
    public int nbMots()           { return postings.size(); }

    public long nbEntrees() {
        long n = 0;
        for (int[] p : postings.values()) n += p.length / 2;
        return n;
    }

    // ------------------------------------------------------------------ liste d'entiers sans boxing

    private static final class IntListe {
        int[] t = new int[4];
        int n = 0;
        void ajouter(int id, int k) {
            if (n + 2 > t.length) t = Arrays.copyOf(t, t.length * 2);
            t[n++] = id;
            t[n++] = k;
        }
        int[] trieParOccurrences() {
            int m = n / 2;
            Integer[] ordre = new Integer[m];
            for (int i = 0; i < m; i++) ordre[i] = i;
            Arrays.sort(ordre, (a, b) -> t[2 * b + 1] != t[2 * a + 1]
                    ? Integer.compare(t[2 * b + 1], t[2 * a + 1])
                    : Integer.compare(t[2 * a], t[2 * b]));
            int[] res = new int[n];
            for (int i = 0; i < m; i++) { res[2 * i] = t[2 * ordre[i]]; res[2 * i + 1] = t[2 * ordre[i] + 1]; }
            return res;
        }
    }

    // ------------------------------------------------------------------ test

    public static void main(String[] args) throws IOException {
        Path dossier = Paths.get(args.length > 0 ? args[0] : "index");
        Path meta = Paths.get(args.length > 1 ? args[1] : "books/metadata.csv");

        Map<Integer, String> titres = new HashMap<>();
        if (Files.exists(meta))
            for (String l : Files.readAllLines(meta, StandardCharsets.UTF_8).subList(1, Files.readAllLines(meta, StandardCharsets.UTF_8).size())) {
                String[] c = l.split(";");
                if (c.length >= 2) titres.put(Integer.parseInt(c[0]), c[1]);
            }

        long t0 = System.nanoTime();
        IndexInverse idx = charger(dossier);
        double sec = (System.nanoTime() - t0) / 1e9;
        long memo = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) >> 20;
        System.out.printf("Index inverse construit en %.1f s : %d livres, %d mots distincts, %d entrees (mot, livre), ~%d Mo%n",
                sec, idx.nbLivres(), idx.nbMots(), idx.nbEntrees(), memo);

        String[] mots = args.length > 2 ? Arrays.copyOfRange(args, 2, args.length) : new String[]{"babylon", "sargon", "elizabeth"};
        for (String mot : mots) {
            long t1 = System.nanoTime();
            int[] res = idx.chercher(mot);
            double us = (System.nanoTime() - t1) / 1e3;
            System.out.printf("%n\"%s\" : %d livres (recherche en %.0f microsecondes)%n", mot, res.length / 2, us);
            for (int i = 0; i < Math.min(5, res.length / 2); i++)
                System.out.printf("   %6d  %5d fois  %s%n", res[2 * i], res[2 * i + 1], titres.getOrDefault(res[2 * i], ""));
        }
    }
}
