package fr.su.daar;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import fr.su.daar.moteur.Bibliotheque;
import fr.su.daar.moteur.Bibliotheque.Livre;
import fr.su.daar.moteur.Moteur;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Serveur web de l'application (API REST + fichiers du frontend), avec le serveur HTTP integre au JDK
 * (com.sun.net.httpserver) : aucune dependance externe, un pool de threads sert plusieurs clients en parallele.
 *
 * API (toutes les reponses sont en JSON, sauf /text) :
 *   GET  /api/stats
 *   GET  /api/top?tri=pagerank&limite=10   livres les mieux classes (accueil)
 *   GET  /api/search?q=sargon[&mode=exact|contient][&tri=occurrences|frequence|closeness|betweenness|pagerank|clics][&limite=50]
 *   GET  /api/advanced-search?regex=S(a|g|r)%2Bon[&cible=index|texte][&tri=...][&limite=50]
 *   GET  /api/books/{id}             details du livre + centralite + voisins dans le graphe de Jaccard
 *   GET  /api/books/{id}/text        texte du livre (text/plain)
 *   POST /api/books/{id}/click       enregistre une consultation
 *   GET  /                           frontend (fichiers du dossier web/)
 *
 * Lancement :  java -Xmx2g -cp build fr.su.daar.Serveur [dossierDonnees] [port]
 *   dossierDonnees : dossier qui contient books/, index/, jaccard/   (defaut : ..)
 *   port           : 8080 par defaut. Le serveur ecoute sur toutes les interfaces (0.0.0.0),
 *                    donc il est joignable depuis les autres machines du meme reseau.
 */
public class Serveur {

    private final Bibliotheque bib;
    private final Moteur moteur;
    private final Path web;

    Serveur(Bibliotheque bib, Path web) {
        this.bib = bib;
        this.moteur = new Moteur(bib);
        this.web = web;
    }

    public static void main(String[] args) throws IOException {
        Path donnees = Paths.get(args.length > 0 ? args[0] : "..").toAbsolutePath().normalize();
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 8080;
        Path web = Paths.get("web").toAbsolutePath();

        System.out.println("Chargement des donnees depuis " + donnees + " ...");
        Serveur s = new Serveur(new Bibliotheque(donnees), web);

        HttpServer http;
        try {
            http = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        } catch (java.net.BindException e) {
            System.err.println("\nERREUR : le port " + port + " est deja utilise.");
            System.err.println("  - Un autre serveur est sans doute deja lance (autre fenetre lancer.bat) : fermez-la.");
            System.err.println("  - Ou lancez sur un autre port :  lancer.bat 8081");
            System.exit(1);
            return;
        }
        http.createContext("/api/", s::api);
        http.createContext("/", s::fichier);
        http.setExecutor(Executors.newFixedThreadPool(Math.max(8, Runtime.getRuntime().availableProcessors() * 2)));
        http.start();

        System.out.println("\nServeur demarre. Ouvrir dans un navigateur :");
        System.out.println("   sur cette machine     : http://localhost:" + port + "/");
        for (String ip : adressesLocales())
            System.out.println("   depuis le reseau local : http://" + ip + ":" + port + "/");
        System.out.println("(Ctrl+C pour arreter)");
    }

    // ------------------------------------------------------------------ routage de l'API

    private void api(HttpExchange ex) throws IOException {
        long t0 = System.nanoTime();
        String chemin = ex.getRequestURI().getPath();
        String methode = ex.getRequestMethod();
        Map<String, String> p = parametres(ex.getRequestURI().getRawQuery());
        int code = 200;
        try {
            if (methode.equals("OPTIONS")) { envoyer(ex, 204, "text/plain", new byte[0]); return; }
            String[] parts = chemin.replaceAll("/+$", "").split("/");          // "", "api", ...
            if (parts.length == 3 && parts[2].equals("stats")) {
                envoyerJson(ex, 200, stats());
            } else if (parts.length == 3 && parts[2].equals("top")) {
                Json j = new Json().objet().cle("tri").val(p.getOrDefault("tri", "pagerank")).cle("livres").tableau();
                for (Moteur.Resultat r : moteur.top(p.getOrDefault("tri", "pagerank"), limite(p))) { livre(j, r.livre()); j.fin(); }
                envoyerJson(ex, 200, j.fin().fin().toString());
            } else if (parts.length == 3 && parts[2].equals("search")) {
                Moteur.Reponse r = moteur.rechercher(obligatoire(p, "q"), "contient".equals(p.get("mode")),
                        p.getOrDefault("tri", "pertinence"), limite(p));
                envoyerJson(ex, 200, reponse(r));
            } else if (parts.length == 3 && parts[2].equals("advanced-search")) {
                Moteur.Reponse r = moteur.rechercherRegex(obligatoire(p, "regex"), "texte".equals(p.get("cible")),
                        p.getOrDefault("tri", "pertinence"), limite(p));
                envoyerJson(ex, 200, reponse(r));
            } else if (parts.length >= 4 && parts[2].equals("books")) {
                int id = Integer.parseInt(parts[3]);
                Livre lv = bib.livre(id);
                if (lv == null) { envoyerJson(ex, code = 404, erreur("livre " + id + " introuvable")); }
                else if (parts.length == 4) envoyerJson(ex, 200, detail(lv));
                else if (parts.length == 5 && parts[4].equals("text"))
                    envoyer(ex, 200, "text/plain; charset=utf-8", Files.readAllBytes(bib.fichierTexte(id)));
                else if (parts.length == 5 && parts[4].equals("click") && methode.equals("POST"))
                    envoyerJson(ex, 200, new Json().objet().cle("id").val(id).cle("clics").val(bib.cliquer(id)).fin().toString());
                else envoyerJson(ex, code = 404, erreur("route inconnue : " + methode + " " + chemin));
            } else {
                envoyerJson(ex, code = 404, erreur("route inconnue : " + methode + " " + chemin));
            }
        } catch (IllegalArgumentException e) {                    // RegEx invalide, parametre manquant, tri inconnu...
            envoyerJson(ex, code = 400, erreur(e.getMessage()));
        } catch (Exception e) {
            e.printStackTrace();
            envoyerJson(ex, code = 500, erreur(e.toString()));
        } finally {
            System.out.printf("%s %s %s -> %d (%.1f ms) [%s]%n", ex.getRemoteAddress().getAddress().getHostAddress(),
                    methode, ex.getRequestURI(), code, (System.nanoTime() - t0) / 1e6, Thread.currentThread().getName());
        }
    }

    // ------------------------------------------------------------------ JSON des reponses

    private String reponse(Moteur.Reponse r) {
        Json j = new Json().objet()
                .cle("requete").val(r.requete())
                .cle("type").val(r.type())
                .cle("moteur").val(r.moteur())
                .cle("tempsMs").val(Math.round(r.tempsMs() * 100) / 100.0)
                .cle("nbMotsReconnus").val(r.nbMotsReconnus())
                .cle("motsReconnus").tableau();
        for (String m : r.motsReconnus()) j.val(m);
        j.fin().cle("nbResultats").val(r.nbResultats()).cle("resultats").tableau();
        for (Moteur.Resultat res : r.resultats()) {
            livre(j, res.livre());
            j.cle("occurrences").val(res.occurrences()).fin();
        }
        j.fin().cle("suggestions").tableau();
        for (Moteur.Suggestion s : r.suggestions()) {
            livre(j, s.livre());
            j.cle("distance").val(s.distance()).cle("voisinDe").tableau();
            for (int v : s.voisinDe()) j.val(v);
            j.fin().fin();
        }
        return j.fin().fin().toString();
    }

    /** Ouvre un objet JSON avec les champs d'un livre (l'appelant ajoute ses champs puis ferme). */
    private static void livre(Json j, Livre lv) {
        j.objet().cle("id").val(lv.id).cle("titre").val(lv.titre).cle("auteur").val(lv.auteur)
                .cle("nbMots").val(lv.nbMots).cle("degre").val(lv.degre)
                .cle("closeness").val(lv.closeness).cle("betweenness").val(lv.betweenness)
                .cle("pagerank").val(lv.pagerank).cle("clics").val(lv.clics.get())
                .cle("couverture").val("https://www.gutenberg.org/cache/epub/" + lv.id + "/pg" + lv.id + ".cover.medium.jpg");
    }

    private String detail(Livre lv) {
        Json j = new Json();
        livre(j, lv);
        j.cle("voisins").tableau();
        for (Bibliotheque.Voisin v : bib.voisins(lv.id).subList(0, Math.min(20, bib.voisins(lv.id).size()))) {
            Livre lw = bib.livre(v.id());
            if (lw == null) continue;
            livre(j, lw);
            j.cle("distance").val(v.distance()).fin();
        }
        return j.fin().fin().toString();
    }

    private String stats() {
        long mots = 0;
        for (Livre l : bib.livres()) mots += l.nbMots;
        Json j = new Json().objet()
                .cle("nbLivres").val(bib.livres().size())
                .cle("nbMotsTotal").val(mots)
                .cle("nbMotsDistincts").val(bib.index().nbMots())
                .cle("tris").tableau();
        for (String t : Moteur.TRIS) j.val(t);
        return j.fin().cle("clics").map(bib.clics()).fin().toString();
    }

    private static String erreur(String message) {
        return new Json().objet().cle("erreur").val(message).fin().toString();
    }

    // ------------------------------------------------------------------ fichiers statiques (frontend)

    private void fichier(HttpExchange ex) throws IOException {
        String chemin = URLDecoder.decode(ex.getRequestURI().getPath(), StandardCharsets.UTF_8);
        if (chemin.endsWith("/")) chemin += "index.html";
        Path f = web.resolve(chemin.substring(1)).normalize();
        if (!f.startsWith(web) || !Files.isRegularFile(f)) {         // pas de sortie du dossier web/
            envoyer(ex, 404, "text/plain; charset=utf-8", "404 - fichier introuvable".getBytes(StandardCharsets.UTF_8));
            return;
        }
        String nom = f.getFileName().toString();
        String type = nom.endsWith(".html") ? "text/html; charset=utf-8"
                : nom.endsWith(".js") ? "application/javascript; charset=utf-8"
                : nom.endsWith(".css") ? "text/css; charset=utf-8"
                : nom.endsWith(".svg") ? "image/svg+xml"
                : nom.endsWith(".png") ? "image/png" : "application/octet-stream";
        envoyer(ex, 200, type, Files.readAllBytes(f));
    }

    // ------------------------------------------------------------------ outils HTTP

    private static void envoyerJson(HttpExchange ex, int code, String json) throws IOException {
        envoyer(ex, code, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
    }

    private static void envoyer(HttpExchange ex, int code, String type, byte[] corps) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");          // frontend servi ailleurs (dev)
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        ex.sendResponseHeaders(code, corps.length == 0 ? -1 : corps.length);
        if (corps.length > 0) try (OutputStream os = ex.getResponseBody()) { os.write(corps); }
        ex.close();
    }

    private static Map<String, String> parametres(String requete) {
        Map<String, String> p = new HashMap<>();
        if (requete == null) return p;
        for (String kv : requete.split("&")) {
            int eg = kv.indexOf('=');
            if (eg < 0) continue;
            p.put(URLDecoder.decode(kv.substring(0, eg), StandardCharsets.UTF_8),
                  URLDecoder.decode(kv.substring(eg + 1), StandardCharsets.UTF_8));
        }
        return p;
    }

    private static String obligatoire(Map<String, String> p, String nom) {
        String v = p.get(nom);
        if (v == null || v.isBlank()) throw new IllegalArgumentException("parametre manquant : " + nom);
        return v;
    }

    private static int limite(Map<String, String> p) {
        int l = Integer.parseInt(p.getOrDefault("limite", "50"));
        return Math.max(1, Math.min(l, 2000));
    }

    private static List<String> adressesLocales() {
        try {
            return Collections.list(NetworkInterface.getNetworkInterfaces()).stream()
                    .filter(i -> { try { return i.isUp() && !i.isLoopback() && !i.isVirtual(); } catch (Exception e) { return false; } })
                    .flatMap(i -> Collections.list(i.getInetAddresses()).stream())
                    .filter(a -> a instanceof Inet4Address)
                    .map(a -> a.getHostAddress()).toList();
        } catch (Exception e) {
            return List.of();
        }
    }
}
