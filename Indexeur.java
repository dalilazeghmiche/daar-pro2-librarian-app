import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * DAAR Projet 2 - Etape 2 : table d'index de chaque livre (cours 3, "weighted naive table").
 *
 * Pour chaque livre D, on calcule l'ensemble des paires (w, k) : le mot w apparait k fois dans D.
 *   - un mot = suite maximale de lettres [A-Za-z] (le texte est decoupe par [^A-Za-z]) ;
 *   - les mots sont mis en minuscules ("The" et "the" sont le meme mot) ;
 *   - les mots de 1 et 2 lettres sont retires (comme suggere en cours).
 *
 * Usage :
 *   java Indexeur.java                 -> lit books/, ecrit index/
 *   java Indexeur.java books index
 *
 * Sorties :
 *   index/<id>.idx      une ligne "mot<TAB>occurrences" par mot distinct, triee par occurrences decroissantes
 *   index/stats.csv     id;nbMotsIndexes;nbMotsDistincts       (pour le rapport et les tests)
 *
 * Complexite : O(n) par livre (n = taille du texte), grace a la table de hachage mot -> compteur.
 * Les livres sont traites en parallele (un livre par coeur).
 */
public class Indexeur {

    static final int LONGUEUR_MIN = 3;

    public static void main(String[] args) throws IOException {
        Path livres = Paths.get(args.length > 0 ? args[0] : "books");
        Path sortie = Paths.get(args.length > 1 ? args[1] : "index");
        Files.createDirectories(sortie);

        List<Path> fichiers;
        try (Stream<Path> s = Files.list(livres)) {
            fichiers = s.filter(p -> p.getFileName().toString().matches("\\d+\\.txt")).sorted().toList();
        }
        System.out.println(fichiers.size() + " livres a indexer...");

        long t0 = System.nanoTime();
        Map<Integer, int[]> stats = new ConcurrentHashMap<>();
        fichiers.parallelStream().forEach(f -> {
            try {
                int id = Integer.parseInt(f.getFileName().toString().replace(".txt", ""));
                byte[] texte = Files.readAllBytes(f);
                Map<String, Integer> index = indexer(texte);
                int total = ecrire(index, sortie.resolve(id + ".idx"));
                stats.put(id, new int[]{total, index.size()});
            } catch (IOException e) {
                System.err.println("erreur sur " + f + " : " + e.getMessage());
            }
        });
        double secondes = (System.nanoTime() - t0) / 1e9;

        try (BufferedWriter w = Files.newBufferedWriter(sortie.resolve("stats.csv"), StandardCharsets.UTF_8)) {
            w.write("id;nbMotsIndexes;nbMotsDistincts\n");
            for (var e : stats.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList())
                w.write(e.getKey() + ";" + e.getValue()[0] + ";" + e.getValue()[1] + "\n");
        }
        System.out.printf("Termine : %d livres indexes en %.1f s -> %s%n", stats.size(), secondes, sortie.toAbsolutePath());
    }

    /**
     * Table d'index d'un texte : mot -> nombre d'occurrences.
     * Parcours unique du texte, octet par octet : une lettre ASCII prolonge le mot courant,
     * tout autre octet (y compris les octets UTF-8 non ASCII) le termine.
     */
    static Map<String, Integer> indexer(byte[] texte) {
        Map<String, Integer> index = new HashMap<>();
        StringBuilder mot = new StringBuilder();
        for (int i = 0; i <= texte.length; i++) {
            int c = i < texte.length ? texte[i] : ' ';
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                mot.append((char) (c | 0x20));                 // minuscule
            } else if (mot.length() > 0) {
                if (mot.length() >= LONGUEUR_MIN) index.merge(mot.toString(), 1, Integer::sum);
                mot.setLength(0);
            }
        }
        return index;
    }

    /** Ecrit l'index trie par occurrences decroissantes ; renvoie le nombre total de mots indexes. */
    static int ecrire(Map<String, Integer> index, Path fichier) throws IOException {
        List<Map.Entry<String, Integer>> entrees = new ArrayList<>(index.entrySet());
        entrees.sort(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
                .thenComparing(Map.Entry.comparingByKey()));
        int total = 0;
        try (BufferedWriter w = Files.newBufferedWriter(fichier, StandardCharsets.UTF_8)) {
            for (var e : entrees) {
                w.write(e.getKey());
                w.write('\t');
                w.write(Integer.toString(e.getValue()));
                w.write('\n');
                total += e.getValue();
            }
        }
        return total;
    }
}
