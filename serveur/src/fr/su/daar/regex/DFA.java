package fr.su.daar.regex;

// Repris du projet 1 (clone de egrep), sans modification de l'algorithme.
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Automate fini deterministe (etape 4), construit par la methode des sous-ensembles
 * (Aho-Ullman p. 547-552).
 *
 * Un etat du DFA = un ENSEMBLE d'etats du NFA (deja ferme par epsilon).
 *
 *   etat initial  = eps-fermeture({start du NFA})
 *   transition(E, c) = eps-fermeture( { cible(q) : q dans E, label(q) == c ou '.' } )
 *   E est final   <=> E contient l'etat final du NFA
 *
 * Deux modes de construction :
 *  - mode "exact"  : DFA qui reconnait exactement R (celui du cours, Fig. 10.35) ;
 *  - mode "search" : DFA qui reconnait .*R. On ajoute eps-fermeture(start) a chaque
 *                    transition (meme astuce que NFAMatcher) : la recherche d'une
 *                    sous-chaine se fait alors en UN passage, 1 acces table par caractere.
 *
 * Table de transitions : trans[etat][c] pour c = 0..255, -1 = pas de transition (etat mort).
 */
public class DFA {

    /** Au-dela, on considere que le DFA "explose" (on pourra revenir au NFA). */
    public static final int MAX_STATES = 20_000;

    public final int[][] trans;
    public final boolean[] accept;
    public final int start;
    public final boolean searchMode;

    public DFA(int[][] trans, boolean[] accept, int start, boolean searchMode) {
        this.trans = trans;
        this.accept = accept;
        this.start = start;
        this.searchMode = searchMode;
    }

    public int size() { return trans.length; }

    // =====================================================================
    //  Methode des sous-ensembles
    // =====================================================================

    public static DFA fromNFA(NFA nfa, boolean searchMode) {
        Map<BitSet, Integer> ids = new HashMap<>();     // ensemble d'etats NFA -> numero DFA
        List<BitSet> sets = new ArrayList<>();
        List<int[]> rows = new ArrayList<>();
        ArrayDeque<Integer> todo = new ArrayDeque<>();

        BitSet startClosure = closure(nfa, single(nfa.start));
        ids.put(startClosure, 0);
        sets.add(startClosure);
        todo.add(0);

        while (!todo.isEmpty()) {
            int d = todo.poll();
            BitSet set = sets.get(d);
            int[] row = new int[256];

            for (int c = 0; c < 256; c++) {
                // move(set, c) : etats atteints en lisant c
                BitSet moved = new BitSet(nfa.size());
                for (int q = set.nextSetBit(0); q >= 0; q = set.nextSetBit(q + 1)) {
                    int l = nfa.label(q);
                    if (l == c || l == NFA.DOT) moved.set(nfa.target(q));
                }
                BitSet next = closure(nfa, moved);
                if (searchMode) next.or(startClosure);  // nouvelle tentative a chaque position

                if (next.isEmpty()) { row[c] = -1; continue; }   // etat mort (mode exact)

                Integer id = ids.get(next);
                if (id == null) {                       // nouvel ensemble : nouvel etat DFA
                    id = sets.size();
                    if (id >= MAX_STATES)
                        throw new IllegalStateException("DFA trop gros (> " + MAX_STATES + " etats)");
                    ids.put(next, id);
                    sets.add(next);
                    todo.add(id);
                }
                row[c] = id;
            }
            while (rows.size() <= d) rows.add(null);
            rows.set(d, row);
        }

        int n = sets.size();
        int[][] trans = rows.toArray(new int[n][]);
        boolean[] accept = new boolean[n];
        for (int d = 0; d < n; d++) accept[d] = sets.get(d).get(nfa.accept);
        return new DFA(trans, accept, 0, searchMode);
    }

    /** eps-fermeture d'un ensemble d'etats (parcours en profondeur). */
    static BitSet closure(NFA nfa, BitSet states) {
        BitSet result = (BitSet) states.clone();
        ArrayDeque<Integer> stack = new ArrayDeque<>();
        for (int q = states.nextSetBit(0); q >= 0; q = states.nextSetBit(q + 1)) stack.push(q);
        while (!stack.isEmpty()) {
            int q = stack.pop();
            for (int t : nfa.eps(q)) {
                if (!result.get(t)) { result.set(t); stack.push(t); }
            }
        }
        return result;
    }

    private static BitSet single(int q) {
        BitSet b = new BitSet();
        b.set(q);
        return b;
    }

    // =====================================================================
    //  Recherche dans une ligne
    // =====================================================================

    /**
     * Vrai si buf[from..to[ contient une sous-chaine reconnue.
     * En mode search : un acces a la table par caractere, O(n).
     */
    public boolean matches(byte[] buf, int from, int to) {
        if (!searchMode)
            throw new IllegalStateException("la recherche de sous-chaine utilise le DFA en mode search");
        int s = start;
        if (accept[s]) return true;
        for (int i = from; i < to; i++) {
            s = trans[s][buf[i] & 0xFF];
            if (accept[s]) return true;
        }
        return false;
    }

    public boolean matches(String line) {
        byte[] b = line.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        return matches(b, 0, b.length);
    }

    /** Vrai si le mot ENTIER est reconnu (utile pour tester le mode exact). */
    public boolean accepts(String word) {
        int s = start;
        for (int i = 0; i < word.length(); i++) {
            s = trans[s][word.charAt(i) & 0xFF];
            if (s < 0) return false;
        }
        return accept[s];
    }

    
    /**
     * Surlignage : fin de la plus LONGUE sous-chaine reconnue qui COMMENCE en position i
     * (DFA en mode exact). Renvoie -1 si aucune ; i si seul le mot vide est reconnu.
     * On s'arrete des que l'on tombe dans l'etat mort (-1).
     */
    
    public int longestMatchEnd(byte[] buf, int i, int to) {
        if (searchMode)
            throw new IllegalStateException("le surlignage utilise le DFA en mode exact");
        int s = start;
        int last = accept[s] ? i : -1;
        for (int k = i; k < to; k++) {
            s = trans[s][buf[k] & 0xFF];
            if (s < 0) break;
            if (accept[s]) last = k + 1;
        }
        return last;
    }
    
    // =====================================================================
    //  Affichage
    // =====================================================================

    /** Transitions regroupees par etat cible ; les gros groupes sont resumes. */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("DFA (").append(searchMode ? "search" : "exact").append(") : ")
          .append(size()).append(" etats, initial = D").append(start).append(", finaux =");
        for (int d = 0; d < size(); d++) if (accept[d]) sb.append(" D").append(d);
        sb.append('\n');
        for (int d = 0; d < size(); d++) {
            TreeMap<Integer, StringBuilder> byTarget = new TreeMap<>();
            TreeMap<Integer, Integer> count = new TreeMap<>();
            for (int c = 0; c < 256; c++) {
                int t = trans[d][c];
                if (t < 0) continue;
                count.merge(t, 1, Integer::sum);
                if (c > 32 && c < 127) byTarget.computeIfAbsent(t, k -> new StringBuilder()).append((char) c);
            }
            for (int t : count.keySet()) {
                int k = count.get(t);
                String chars = k > 20 ? "[" + k + " caracteres]"
                                      : byTarget.getOrDefault(t, new StringBuilder("?")).toString();
                sb.append("  D").append(d).append(" --").append(chars).append("--> D").append(t).append('\n');
            }
        }
        return sb.toString();
    }
}