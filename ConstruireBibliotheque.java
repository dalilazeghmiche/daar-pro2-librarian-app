import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * DAAR Projet 2 - Construction de la bibliotheque (couche data).
 *
 * Telecharge des livres du Projet Gutenberg (https://www.gutenberg.org) au format texte brut,
 * retire l'en-tete et la licence Gutenberg, et ne garde que les livres d'au moins MIN_MOTS mots
 * (un mot = suite maximale de lettres [A-Za-z], meme decoupage que la table d'index du cours 3).
 *
 * Source de la liste des livres : le catalogue officiel Gutenberg
 *   https://www.gutenberg.org/cache/epub/feeds/pg_catalog.csv
 * On garde les entrees de Type "Text" et de langue "en".
 *
 * Usage (Java 11+, aucune compilation necessaire) :
 *   java ConstruireBibliotheque.java                 -> 1700 livres dans ./books
 *   java ConstruireBibliotheque.java 1700 books      -> nombre cible et dossier de sortie
 *
 * Le script est REPRENABLE : si on l'interrompt (Ctrl+C) et qu'on le relance, les livres deja
 * presents sont conserves et comptes, ceux deja refuses ne sont pas retelecharges.
 *
 * Sorties :
 *   books/<id>.txt         texte du livre (sans en-tete/licence Gutenberg)
 *   books/metadata.csv     id;titre;auteur;nbMots      (pour le frontend et le rapport)
 *   books/refuses.txt      ids refuses (trop courts, introuvables, ...) avec la raison
 */
public class ConstruireBibliotheque {

    static final int MIN_MOTS = 10_000;
    static final long PAUSE_MS = 1_000;   // politesse envers le serveur Gutenberg
    static String CATALOGUE = "https://www.gutenberg.org/cache/epub/feeds/pg_catalog.csv";
    static String BASE = "https://www.gutenberg.org/cache/epub/%d/pg%d.txt";

    /** Livres des cours/TME, mis en tete pour qu'ils soient dans la bibliotheque. */
    static final int[] PRIORITAIRES = {49345, 56667, 11, 1342, 84, 2701, 1661, 98, 76, 345};

    static final Pattern MOT = Pattern.compile("[A-Za-z]+");
    static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20)).build();

    record Livre(int id, String titre, String auteur) {}

    public static void main(String[] args) throws Exception {
        int cible = args.length > 0 ? Integer.parseInt(args[0]) : 1700;
        Path dossier = Paths.get(args.length > 1 ? args[1] : "books");
        // options de test : URL du catalogue et gabarit d'URL des livres
        if (args.length > 2) CATALOGUE = args[2];
        if (args.length > 3) BASE = args[3];

        Files.createDirectories(dossier);
        Path meta = dossier.resolve("metadata.csv");
        Path refuses = dossier.resolve("refuses.txt");
        if (!Files.exists(meta)) Files.writeString(meta, "id;titre;auteur;nbMots\n");
        if (!Files.exists(refuses)) Files.createFile(refuses);

        Set<Integer> dejaGardes = new LinkedHashSet<>();
        List<String> lignesMeta = Files.readAllLines(meta, StandardCharsets.UTF_8);
        for (String l : lignesMeta.subList(1, lignesMeta.size()))
            if (!l.isBlank()) dejaGardes.add(Integer.parseInt(l.split(";", 2)[0]));
        Set<Integer> dejaRefuses = new LinkedHashSet<>();
        for (String l : Files.readAllLines(refuses, StandardCharsets.UTF_8))
            if (!l.isBlank()) dejaRefuses.add(Integer.parseInt(l.split(" ", 2)[0]));

        System.out.println("Telechargement du catalogue Gutenberg...");
        List<Livre> catalogue = lireCatalogue(telecharger(CATALOGUE));
        System.out.println(catalogue.size() + " livres anglais au format texte dans le catalogue.");

        // ordre : livres prioritaires d'abord, puis ordre du catalogue
        List<Livre> ordre = new ArrayList<>();
        for (int p : PRIORITAIRES)
            for (Livre l : catalogue) if (l.id() == p) ordre.add(l);
        for (Livre l : catalogue) if (!ordre.contains(l)) ordre.add(l);

        int gardes = dejaGardes.size();
        System.out.println(gardes + " livres deja presents, objectif " + cible + ".");
        for (Livre l : ordre) {
            if (gardes >= cible) break;
            if (dejaGardes.contains(l.id()) || dejaRefuses.contains(l.id())) continue;
            try {
                String brut = new String(telecharger(String.format(BASE, l.id(), l.id())), StandardCharsets.UTF_8);
                String texte = retirerEnteteGutenberg(brut);
                int mots = compterMots(texte);
                if (mots < MIN_MOTS) {
                    ajouter(refuses, l.id() + " trop court (" + mots + " mots)\n");
                    System.out.printf("  refuse  %6d  %6d mots  %s%n", l.id(), mots, l.titre());
                } else {
                    Files.writeString(dossier.resolve(l.id() + ".txt"), texte, StandardCharsets.UTF_8);
                    ajouter(meta, l.id() + ";" + nettoyer(l.titre()) + ";" + nettoyer(l.auteur()) + ";" + mots + "\n");
                    gardes++;
                    System.out.printf("[%4d/%d] %6d  %7d mots  %s%n", gardes, cible, l.id(), mots, l.titre());
                }
            } catch (IOException e) {
                // 404/410 : definitif, on ne reessaiera pas. Coupure reseau : on reessaiera a la relance.
                if (String.valueOf(e.getMessage()).startsWith("HTTP 4"))
                    ajouter(refuses, l.id() + " erreur " + e.getMessage() + "\n");
                System.out.printf("  erreur  %6d  %s%n", l.id(), e.getMessage());
            }
            Thread.sleep(PAUSE_MS);
        }
        System.out.println("Termine : " + gardes + " livres dans " + dossier.toAbsolutePath());
    }

    // ------------------------------------------------------------------ reseau

    static byte[] telecharger(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "DAAR-projet2-bibliotheque (etudiant Sorbonne Universite)")
                .timeout(Duration.ofSeconds(60)).GET().build();
        HttpResponse<InputStream> rep = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = rep.body()) {
            if (rep.statusCode() != 200) throw new IOException("HTTP " + rep.statusCode());
            return in.readAllBytes();
        }
    }

    // ------------------------------------------------------------------ catalogue

    /** Colonnes : Text#,Type,Issued,Title,Language,Authors,Subjects,LoCC,Bookshelves */
    static List<Livre> lireCatalogue(byte[] csv) {
        List<Livre> res = new ArrayList<>();
        List<List<String>> lignes = parserCsv(new String(csv, StandardCharsets.UTF_8));
        for (int i = 1; i < lignes.size(); i++) {           // ligne 0 = en-tete
            List<String> c = lignes.get(i);
            if (c.size() < 6) continue;
            if (!c.get(1).equals("Text")) continue;
            if (!c.get(4).trim().equals("en")) continue;     // une seule langue : anglais
            try {
                res.add(new Livre(Integer.parseInt(c.get(0).trim()), c.get(3), c.get(5)));
            } catch (NumberFormatException ignore) { }
        }
        return res;
    }

    /** Petit parseur CSV (RFC 4180) : guillemets, "" echappes, retours a la ligne dans un champ. */
    static List<List<String>> parserCsv(String s) {
        List<List<String>> lignes = new ArrayList<>();
        List<String> ligne = new ArrayList<>();
        StringBuilder champ = new StringBuilder();
        boolean entreGuillemets = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (entreGuillemets) {
                if (ch == '"') {
                    if (i + 1 < s.length() && s.charAt(i + 1) == '"') { champ.append('"'); i++; }
                    else entreGuillemets = false;
                } else champ.append(ch);
            } else if (ch == '"') entreGuillemets = true;
            else if (ch == ',') { ligne.add(champ.toString()); champ.setLength(0); }
            else if (ch == '\n') { ligne.add(champ.toString()); champ.setLength(0); lignes.add(ligne); ligne = new ArrayList<>(); }
            else if (ch != '\r') champ.append(ch);
        }
        if (champ.length() > 0 || !ligne.isEmpty()) { ligne.add(champ.toString()); lignes.add(ligne); }
        return lignes;
    }

    // ------------------------------------------------------------------ texte

    /** Garde le texte entre "*** START OF ..." et "*** END OF ..." (marqueurs Gutenberg). */
    static String retirerEnteteGutenberg(String t) {
        int debut = t.indexOf("*** START OF");
        if (debut >= 0) {
            int finLigne = t.indexOf('\n', debut);
            debut = finLigne >= 0 ? finLigne + 1 : debut;
        } else debut = 0;
        int fin = t.indexOf("*** END OF", debut);
        if (fin < 0) fin = t.length();
        return t.substring(debut, fin).strip() + "\n";
    }

    static int compterMots(String t) {
        int n = 0;
        var m = MOT.matcher(t);
        while (m.find()) n++;
        return n;
    }

    static String nettoyer(String s) {
        return s.replace(';', ',').replace('\n', ' ').replace('\r', ' ').trim();
    }

    static void ajouter(Path p, String s) throws IOException {
        Files.writeString(p, s, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }
}
