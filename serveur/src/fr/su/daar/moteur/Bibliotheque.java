package fr.su.daar.moteur;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Couche data cote serveur : charge en memoire, au demarrage, tout ce qui a ete precalcule hors ligne.
 *
 *   books/metadata.csv       id;titre;auteur;nbMots
 *   index/*.idx              tables d'index           -> IndexInverse (mot -> livres)
 *   jaccard/graphe.csv       aretes du graphe de Jaccard (theta = 0.70)
 *   jaccard/centralite.csv   degre, closeness, betweenness, pagerank de chaque livre
 *   clics.csv                nombre de consultations de chaque livre (cree et mis a jour par le serveur)
 *
 * Le texte des livres (books/<id>.txt) n'est PAS garde en memoire (~850 Mo) : il est lu sur disque a la demande.
 */
public class Bibliotheque {

    /** Informations sur un livre (immuables, sauf le compteur de clics). */
    public static final class Livre {
        public final int id;
        public final String titre, auteur;
        public final int nbMots;
        public int degre;
        public double closeness, betweenness, pagerank;
        public final AtomicInteger clics = new AtomicInteger();
        Livre(int id, String titre, String auteur, int nbMots) {
            this.id = id; this.titre = titre; this.auteur = auteur; this.nbMots = nbMots;
        }
    }

    /** Arete du graphe de Jaccard, vue depuis un livre : le voisin et la distance de Jaccard. */
    public record Voisin(int id, double distance) {}

    public final Path dossier;
    private final Map<Integer, Livre> livres = new HashMap<>();
    private final Map<Integer, List<Voisin>> voisins = new HashMap<>();
    private final IndexInverse index;
    private final Path fichierClics;

    public Bibliotheque(Path dossier) throws IOException {
        this.dossier = dossier;
        long t0 = System.nanoTime();

        // 1. metadonnees
        List<String> lignes = Files.readAllLines(dossier.resolve("books/metadata.csv"), StandardCharsets.UTF_8);
        for (String l : lignes.subList(1, lignes.size())) {
            String[] c = l.split(";", -1);
            if (c.length < 4) continue;
            int id = Integer.parseInt(c[0]);
            if (Files.exists(dossier.resolve("index/" + id + ".idx")))
                livres.put(id, new Livre(id, c[1], c[2], Integer.parseInt(c[3].trim())));
        }

        // 2. index inverse
        index = IndexInverse.charger(dossier.resolve("index"));

        // 3. graphe de Jaccard (listes d'adjacence, voisins tries du plus proche au plus loin)
        lignes = Files.readAllLines(dossier.resolve("jaccard/graphe.csv"), StandardCharsets.UTF_8);
        for (String l : lignes.subList(1, lignes.size())) {
            String[] c = l.split(";");
            int a = Integer.parseInt(c[0]), b = Integer.parseInt(c[1]);
            double d = Double.parseDouble(c[2]);
            voisins.computeIfAbsent(a, k -> new ArrayList<>()).add(new Voisin(b, d));
            voisins.computeIfAbsent(b, k -> new ArrayList<>()).add(new Voisin(a, d));
        }
        for (List<Voisin> v : voisins.values()) v.sort((x, y) -> Double.compare(x.distance(), y.distance()));

        // 4. centralite
        lignes = Files.readAllLines(dossier.resolve("jaccard/centralite.csv"), StandardCharsets.UTF_8);
        for (String l : lignes.subList(1, lignes.size())) {
            String[] c = l.split(";");
            Livre lv = livres.get(Integer.parseInt(c[0]));
            if (lv == null) continue;
            lv.degre = Integer.parseInt(c[1]);
            lv.closeness = Double.parseDouble(c[2]);
            lv.betweenness = Double.parseDouble(c[3]);
            lv.pagerank = Double.parseDouble(c[4]);
        }

        // 5. clics deja enregistres (une ligne "id" par clic)
        fichierClics = dossier.resolve("clics.csv");
        if (Files.exists(fichierClics))
            for (String l : Files.readAllLines(fichierClics, StandardCharsets.UTF_8)) {
                if (l.isBlank()) continue;
                Livre lv = livres.get(Integer.parseInt(l.trim()));
                if (lv != null) lv.clics.incrementAndGet();
            }

        System.out.printf("Bibliotheque chargee en %.1f s : %d livres, %d mots distincts, %d livres dans le graphe%n",
                (System.nanoTime() - t0) / 1e9, livres.size(), index.nbMots(), voisins.size());
    }

    public Livre livre(int id)               { return livres.get(id); }
    public Collection<Livre> livres()        { return livres.values(); }
    public IndexInverse index()              { return index; }
    public List<Voisin> voisins(int id)      { return voisins.getOrDefault(id, List.of()); }
    public Path fichierTexte(int id)         { return dossier.resolve("books/" + id + ".txt"); }

    /** Enregistre une consultation (fonctionnalite implicite : les livres les plus cliques). */
    public int cliquer(int id) throws IOException {
        Livre lv = livres.get(id);
        if (lv == null) return -1;
        synchronized (this) {
            try (BufferedWriter w = Files.newBufferedWriter(fichierClics, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                w.write(id + "\n");
            }
        }
        return lv.clics.incrementAndGet();
    }

    /** Nombre de clics de chaque livre deja consulte. */
    public Map<Integer, Integer> clics() {
        Map<Integer, Integer> m = new ConcurrentHashMap<>();
        for (Livre l : livres.values()) if (l.clics.get() > 0) m.put(l.id, l.clics.get());
        return m;
    }
}
