package fr.su.daar.regex;

// Repris du projet 1 (clone de egrep), sans modification de l'algorithme.
/**
 * Recherche d'un motif dans une ligne par simulation du NFA (etape 3).
 *
 * Principe : au lieu d'etre dans UN etat, on maintient l'ENSEMBLE des etats
 * ou le NFA peut se trouver apres avoir lu les caracteres deja vus.
 *
 *   1. ensemble courant = eps-fermeture({start})
 *   2. pour chaque caractere c de la ligne :
 *        suivant = eps-fermeture( etats atteints en lisant c )
 *        + eps-fermeture({start})   <- on (re)commence une tentative a chaque position
 *   3. des que l'ensemble contient l'etat final : la ligne contient le motif.
 *
 * Le point 2 ("relancer depuis start a chaque position") revient a chercher .*R :
 * egrep cherche une SOUS-CHAINE, pas la ligne entiere. Un seul passage suffit.
 * Complexite : O(n * m) par ligne, n = longueur de la ligne, m = nombre d'etats du NFA.
 */
public class NFAMatcher {

    // Copie du NFA dans des tableaux simples (beaucoup plus rapide que des List<Integer>)
    private final int[] label;
    private final int[] target;
    private final int[][] eps;
    private final int start, accept;

    // Deux ensembles d'etats reutilises (pas d'allocation pendant la recherche)
    private int[] cur, next;          // listes d'etats
    private int curSize, nextSize;
    private final int[] mark;         // mark[q] == stamp  <=>  q est deja dans l'ensemble en cours
    private int stamp = 0;
    private final int[] stack;        // pile pour l'eps-fermeture

    public NFAMatcher(NFA nfa) {
        int n = nfa.size();
        label = new int[n];
        target = new int[n];
        eps = new int[n][];
        for (int q = 0; q < n; q++) {
            label[q] = nfa.label(q);
            target[q] = nfa.target(q);
            eps[q] = nfa.eps(q).stream().mapToInt(Integer::intValue).toArray();
        }
        start = nfa.start;
        accept = nfa.accept;
        cur = new int[n];
        next = new int[n];
        mark = new int[n];
        stack = new int[n];
    }

    /** Vrai si buf[from..to[ (une ligne, sans le '\n') contient une sous-chaine reconnue. */
    public boolean matches(byte[] buf, int from, int to) {
        // Ensemble initial : eps-fermeture de start
        newSet();
        addWithClosure(start);
        swap();
        if (contains(cur, curSize, accept)) return true;   // le motif accepte le mot vide (ex. a*)

        for (int i = from; i < to; i++) {
            int c = buf[i] & 0xFF;                           // octet -> 0..255
            newSet();
            for (int k = 0; k < curSize; k++) {
                int q = cur[k];
                int l = label[q];
                if (l == c || l == NFA.DOT)
                    addWithClosure(target[q]);
            }
            addWithClosure(start);                           // nouvelle tentative a la position i+1
            swap();
            if (mark[accept] == stamp) return true;          // etat final atteint
        }
        return false;
    }

    /** Version pratique pour les tests. */
    public boolean matches(String line) {
        byte[] b = line.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        return matches(b, 0, b.length);
    }

    // --- eps-fermeture : ajoute q et tout ce qui est accessible par des eps-transitions ---

    private void addWithClosure(int q) {
        if (mark[q] == stamp) return;
        int sp = 0;
        stack[sp++] = q;
        mark[q] = stamp;
        while (sp > 0) {
            int p = stack[--sp];
            next[nextSize++] = p;
            for (int t : eps[p]) {
                if (mark[t] != stamp) {
                    mark[t] = stamp;
                    stack[sp++] = t;
                }
            }
        }
    }

    /** Commence un nouvel ensemble vide (le changement de "stamp" vide mark en O(1)). */
    private void newSet() {
        stamp++;
        nextSize = 0;
    }

    /** L'ensemble "next" devient l'ensemble courant. */
    private void swap() {
        int[] tmp = cur; cur = next; next = tmp;
        curSize = nextSize;
    }

    private static boolean contains(int[] set, int size, int q) {
        for (int i = 0; i < size; i++) if (set[i] == q) return true;
        return false;
    }
    
    
    /**
     * Surlignage : fin de la plus LONGUE sous-chaine reconnue qui commence en position i,
     * -1 si aucune. Meme simulation que matches(), mais SANS relancer depuis start
     * (on cherche une correspondance ancree en i), et on continue apres l'etat final
     * pour trouver la plus longue.
     */
    
    public int longestMatchEnd(byte[] buf, int i, int to) {
        newSet();
        addWithClosure(start);
        swap();
        int last = contains(cur, curSize, accept) ? i : -1;
        for (int k = i; k < to; k++) {
            int c = buf[k] & 0xFF;
            newSet();
            for (int j = 0; j < curSize; j++) {
                int q = cur[j];
                int l = label[q];
                if (l == c || l == NFA.DOT) addWithClosure(target[q]);
            }
            swap();
            if (curSize == 0) break;                         // plus aucun etat actif
            if (mark[accept] == stamp) last = k + 1;
        }
        return last;
    }
}