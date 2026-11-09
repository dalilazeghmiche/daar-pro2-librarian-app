package fr.su.daar.moteur;

import fr.su.daar.regex.DFA;
import fr.su.daar.regex.KMP;
import fr.su.daar.regex.Minimizer;
import fr.su.daar.regex.NFA;
import fr.su.daar.regex.NFAMatcher;
import fr.su.daar.regex.RegexNode;
import fr.su.daar.regex.RegexParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Couche serveur : les 4 fonctionnalites demandees par l'enonce.
 *
 *  1. Recherche (explicite)          : mot-cle S -> livres dont la table d'index contient S.
 *       - mode "exact"    : le mot S lui-meme             -> un acces a l'index inverse, O(1)
 *       - mode "contient" : tout mot de l'index contenant S comme facteur (KMP du projet 1 sur le vocabulaire)
 *  2. Recherche avancee (explicite)  : RegEx -> livres dont la table d'index contient un mot reconnu par la RegEx
 *       (automate du projet 1 : arbre -> NFA -> DFA -> DFA minimal, puis DFA.accepts(mot) sur chaque mot du vocabulaire),
 *       ou, cible "texte", livres dont le TEXTE contient une sous-chaine reconnue (comme egrep, beaucoup plus lent).
 *  3. Classement (implicite)         : tri des resultats par occurrences, frequence, closeness, betweenness, pagerank, clics.
 *  4. Suggestion (implicite)         : voisins, dans le graphe de Jaccard, des 3 livres les plus pertinents,
 *       classes par nombre de "graines" auxquelles ils sont relies, puis par clics, puis par distance de Jaccard.
 */
public class Moteur {

    public static final List<String> TRIS = List.of("pertinence", "occurrences", "frequence", "closeness", "betweenness", "pagerank", "clics");

    /** Un livre trouve, avec son score pour la requete. */
    public record Resultat(Bibliotheque.Livre livre, long occurrences) {}

    /** Un livre suggere : voisin de quels livres les plus pertinents, et a quelle distance (la plus petite). */
    public record Suggestion(Bibliotheque.Livre livre, List<Integer> voisinDe, double distance) {}

    /** Reponse complete a une recherche. */
    public record Reponse(String requete, String type, List<String> motsReconnus, int nbMotsReconnus,
                          List<Resultat> resultats, int nbResultats, List<Suggestion> suggestions,
                          String moteur, double tempsMs) {}

    private final Bibliotheque bib;

    public Moteur(Bibliotheque bib) { this.bib = bib; }

    // =====================================================================
    //  1. Recherche par mot-cle
    // =====================================================================

    public Reponse rechercher(String s, boolean contient, String tri, int limite) {
        long t0 = System.nanoTime();
        String mot = s.trim().toLowerCase();
        if (mot.isEmpty() || !mot.chars().allMatch(c -> (c >= 'a' && c <= 'z')))
            throw new IllegalArgumentException("le mot-cle doit etre une suite de lettres a-z");
        List<String> mots;
        String moteur;
        if (!contient) {
            mots = bib.index().chercher(mot).length > 0 ? List.of(mot) : List.of();
            moteur = "index inverse (table de hachage)";
        } else {
            KMP kmp = new KMP(mot.getBytes(StandardCharsets.ISO_8859_1));
            mots = filtrerVocabulaire(w -> kmp.matches(w));
            moteur = "KMP sur les " + bib.index().vocabulaire().length + " mots de l'index";
        }
        return reponse(s, contient ? "mot-cle (contient)" : "mot-cle", mots, fusionner(mots), tri, limite, moteur, t0);
    }

    // =====================================================================
    //  2. Recherche avancee par RegEx
    // =====================================================================

    public Reponse rechercherRegex(String regex, boolean surTexte, String tri, int limite) throws IOException {
        long t0 = System.nanoTime();
        if (regex.isBlank()) throw new IllegalArgumentException("RegEx vide");
        return surTexte ? regexTexte(regex, tri, limite, t0) : regexIndex(regex, tri, limite, t0);
    }

    /** RegEx sur l'index : on garde les mots du vocabulaire ENTIEREMENT reconnus par l'automate. */
    private Reponse regexIndex(String regex, String tri, int limite, long t0) {
        // les mots de l'index sont en minuscules : on met la RegEx en minuscules (lettres seulement)
        RegexNode arbre = RegexParser.parse(regex.toLowerCase());
        NFA nfa = NFA.fromTree(arbre);
        Predicate<String> reconnait;
        String moteur;
        try {
            DFA dfa = Minimizer.minimize(DFA.fromNFA(nfa, false));
            reconnait = dfa::accepts;
            moteur = "DFA minimal (" + dfa.size() + " etats, NFA " + nfa.size() + " etats) sur le vocabulaire";
        } catch (IllegalStateException explosion) {                 // DFA trop gros : simulation du NFA
            NFAMatcher m = new NFAMatcher(nfa);
            reconnait = w -> {
                byte[] b = w.getBytes(StandardCharsets.ISO_8859_1);
                return m.longestMatchEnd(b, 0, b.length) == b.length;
            };
            moteur = "simulation du NFA (" + nfa.size() + " etats, DFA trop gros) sur le vocabulaire";
        }
        List<String> mots = filtrerVocabulaire(reconnait);
        return reponse(regex, "regex (index)", mots, fusionner(mots), tri, limite, moteur, t0);
    }

    /** RegEx sur le texte complet de chaque livre (comme egrep) : occurrences = nombre de lignes reconnues. */
    private Reponse regexTexte(String regex, String tri, int limite, long t0) throws IOException {
        RegexNode arbre = RegexParser.parse(regex);
        NFA nfa = NFA.fromTree(arbre);
        byte[] facteur = KMP.literalOf(arbre);
        String moteur;
        java.util.function.Supplier<LigneMatcher> fabrique;
        if (facteur != null) {                                       // simple facteur : KMP
            fabrique = () -> new KMP(facteur)::matches;
            moteur = "KMP sur le texte complet des livres";
        } else {
            DFA dfa;
            try {
                dfa = Minimizer.minimize(DFA.fromNFA(nfa, true));
                final DFA d = dfa;
                fabrique = () -> d::matches;
                moteur = "DFA minimal en mode recherche (" + dfa.size() + " etats) sur le texte complet";
            } catch (IllegalStateException explosion) {
                fabrique = () -> new NFAMatcher(nfa)::matches;     // NFAMatcher n'est pas partageable entre threads
                moteur = "simulation du NFA sur le texte complet";
            }
        }
        final java.util.function.Supplier<LigneMatcher> f = fabrique;
        Map<Integer, Long> occ = new java.util.concurrent.ConcurrentHashMap<>();
        bib.livres().parallelStream().forEach(lv -> {
            try {
                byte[] buf = Files.readAllBytes(bib.fichierTexte(lv.id));
                LigneMatcher m = f.get();
                long n = 0;
                int debut = 0;
                for (int i = 0; i <= buf.length; i++) {
                    if (i == buf.length || buf[i] == '\n') {
                        int fin = (i > debut && buf[i - 1] == '\r') ? i - 1 : i;
                        if (m.matches(buf, debut, fin)) n++;
                        debut = i + 1;
                    }
                }
                if (n > 0) occ.put(lv.id, n);
            } catch (IOException e) {
                System.err.println("lecture impossible : " + bib.fichierTexte(lv.id));
            }
        });
        return reponse(regex, "regex (texte complet)", List.of(), occ, tri, limite, moteur, t0);
    }

    interface LigneMatcher { boolean matches(byte[] buf, int from, int to); }

    // =====================================================================
    //  Outils communs
    // =====================================================================

    private List<String> filtrerVocabulaire(Predicate<String> p) {
        List<String> res = new ArrayList<>();
        for (String w : bib.index().vocabulaire()) if (p.test(w)) res.add(w);
        return res;
    }

    /** Somme, pour chaque livre, des occurrences des mots reconnus (union des listes de l'index inverse). */
    private Map<Integer, Long> fusionner(List<String> mots) {
        Map<Integer, Long> occ = new HashMap<>();
        for (String w : mots) {
            int[] p = bib.index().chercher(w);
            for (int i = 0; i < p.length; i += 2) occ.merge(p[i], (long) p[i + 1], Long::sum);
        }
        return occ;
    }

    private Reponse reponse(String requete, String type, List<String> mots, Map<Integer, Long> occ,
                            String tri, int limite, String moteur, long t0) {
        List<Resultat> res = new ArrayList<>();
        for (var e : occ.entrySet()) {
            Bibliotheque.Livre lv = bib.livre(e.getKey());
            if (lv != null) res.add(new Resultat(lv, e.getValue()));
        }
        res.sort(comparateur(tri));
        List<Suggestion> sugg = suggerer(res, 3, 10);
        int nb = res.size();
        List<Resultat> page = res.subList(0, Math.min(limite, nb));
        double ms = (System.nanoTime() - t0) / 1e6;
        List<String> motsAffiches = mots.subList(0, Math.min(50, mots.size()));
        return new Reponse(requete, type, motsAffiches, mots.size(), new ArrayList<>(page), nb, sugg, moteur, ms);
    }

    /** Toute la bibliotheque classee selon un critere (page d'accueil : livres les plus centraux, les plus lus...). */
    public List<Resultat> top(String tri, int limite) {
        List<Resultat> res = new ArrayList<>();
        for (Bibliotheque.Livre lv : bib.livres()) res.add(new Resultat(lv, 0));
        res.sort(comparateur(tri));
        return new ArrayList<>(res.subList(0, Math.min(limite, res.size())));
    }

    // =====================================================================
    //  3. Classement
    // =====================================================================

    /**
     * Pertinence = frequence du mot dans le livre x (1 + closeness du livre).
     * La frequence (occurrences / taille) mesure a quel point le livre parle du mot recherche ;
     * la closeness (entre 0 et 1) favorise un peu les livres centraux de la bibliotheque, sans jamais
     * faire passer devant un livre qui contient beaucoup moins le mot (bonus au plus x2).
     */
    static double pertinence(Resultat r) {
        double frequence = (double) r.occurrences() / Math.max(1, r.livre().nbMots);
        return frequence * (1 + r.livre().closeness);
    }

    static Comparator<Resultat> comparateur(String tri) {
        Comparator<Resultat> parOcc = Comparator.comparingLong(Resultat::occurrences).reversed();
        Comparator<Resultat> c = switch (tri == null ? "pertinence" : tri) {
            case "pertinence"  -> Comparator.comparingDouble((Resultat r) -> pertinence(r)).reversed();
            case "frequence"   -> Comparator.comparingDouble((Resultat r) -> (double) r.occurrences() / Math.max(1, r.livre().nbMots)).reversed();
            case "closeness"   -> Comparator.comparingDouble((Resultat r) -> r.livre().closeness).reversed();
            case "betweenness" -> Comparator.comparingDouble((Resultat r) -> r.livre().betweenness).reversed();
            case "pagerank"    -> Comparator.comparingDouble((Resultat r) -> r.livre().pagerank).reversed();
            case "clics"       -> Comparator.comparingInt((Resultat r) -> r.livre().clics.get()).reversed();
            case "occurrences" -> parOcc;
            default -> throw new IllegalArgumentException("tri inconnu : " + tri + " (possibles : " + TRIS + ")");
        };
        return c.thenComparing(parOcc).thenComparingInt(r -> r.livre().id);
    }

    // =====================================================================
    //  4. Suggestions
    // =====================================================================

    /**
     * Voisins dans le graphe de Jaccard des {@code graines} premiers resultats (les plus pertinents),
     * hors ces graines. Classement : relies au plus de graines, puis les plus cliques, puis les plus proches.
     */
    public List<Suggestion> suggerer(List<Resultat> resultats, int graines, int combien) {
        List<Integer> top = new ArrayList<>();
        for (int i = 0; i < Math.min(graines, resultats.size()); i++) top.add(resultats.get(i).livre().id);
        Map<Integer, List<Integer>> lienAvec = new LinkedHashMap<>();
        Map<Integer, Double> meilleureDistance = new HashMap<>();
        for (int g : top)
            for (Bibliotheque.Voisin v : bib.voisins(g)) {
                if (top.contains(v.id())) continue;
                lienAvec.computeIfAbsent(v.id(), k -> new ArrayList<>()).add(g);
                meilleureDistance.merge(v.id(), v.distance(), Math::min);
            }
        List<Suggestion> s = new ArrayList<>();
        for (var e : lienAvec.entrySet()) {
            Bibliotheque.Livre lv = bib.livre(e.getKey());
            if (lv != null) s.add(new Suggestion(lv, e.getValue(), meilleureDistance.get(e.getKey())));
        }
        Comparator<Suggestion> ordre = Comparator.comparingInt((Suggestion x) -> x.voisinDe().size()).reversed()
                .thenComparing(Comparator.comparingInt((Suggestion x) -> x.livre().clics.get()).reversed())
                .thenComparingDouble(Suggestion::distance);
        s.sort(ordre);

        // Repli : pas assez de voisins directs (graines dans une petite composante) -> voisins a distance 2,
        // places apres les voisins directs. "voisinDe" donne alors le voisin direct par lequel on passe.
        if (s.size() < combien) {
            List<Suggestion> niveau2 = new ArrayList<>();
            java.util.Set<Integer> deja = new java.util.HashSet<>(top);
            for (Suggestion x : s) deja.add(x.livre().id);
            for (Suggestion x : new ArrayList<>(s))
                for (Bibliotheque.Voisin v : bib.voisins(x.livre().id))
                    if (deja.add(v.id()) && bib.livre(v.id()) != null)
                        niveau2.add(new Suggestion(bib.livre(v.id()), List.of(x.livre().id), v.distance()));
            niveau2.sort(ordre);
            s.addAll(niveau2);
        }
        return new ArrayList<>(s.subList(0, Math.min(combien, s.size())));
    }
}
