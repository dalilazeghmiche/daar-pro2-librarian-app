package fr.su.daar.regex;

// Repris du projet 1 (clone de egrep), sans modification de l'algorithme.
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Minimisation d'un DFA (etape 5) : methode naive d'Aho-Ullman p. 555
 * (algorithme de Moore, par raffinements successifs de partition).
 *
 * Idee : deux etats sont EQUIVALENTS s'ils reconnaissent les memes suffixes.
 *
 *   1. partition initiale : { etats finaux } et { etats non finaux }
 *   2. on raffine : deux etats d'un meme groupe restent ensemble seulement si,
 *      pour CHAQUE caractere c, leurs transitions menent dans le meme groupe.
 *      Concretement, on calcule pour chaque etat une "signature" :
 *          (groupe(s), groupe(trans[s][0]), ..., groupe(trans[s][255]))
 *      et deux etats avec la meme signature sont dans le meme nouveau groupe.
 *   3. on recommence jusqu'a ce que le nombre de groupes ne change plus.
 *   4. chaque groupe devient un etat du DFA minimal.
 *
 * Une transition absente (-1, etat mort implicite) est traitee comme un groupe a part.
 *
 * Complexite : au plus n tours, chacun en O(n * |Sigma|), donc O(n^2 * |Sigma|)
 * avec n = nombre d'etats et |Sigma| = 256. (Hopcroft fait O(n * |Sigma| * log n).)
 */
public class Minimizer {

    /** Nombre de tours de raffinement du dernier appel (pour le rapport). */
    public static int lastRounds = 0;

    public static DFA minimize(DFA dfa) {
        int n = dfa.size();

        // 1. Partition initiale : finaux = 1, non finaux = 0
        int[] group = new int[n];
        int nbGroups = 0;
        boolean hasFinal = false, hasNonFinal = false;
        for (int s = 0; s < n; s++) {
            group[s] = dfa.accept[s] ? 1 : 0;
            if (dfa.accept[s]) hasFinal = true; else hasNonFinal = true;
        }
        nbGroups = (hasFinal ? 1 : 0) + (hasNonFinal ? 1 : 0);

        // 2-3. Raffinement jusqu'a stabilite
        lastRounds = 0;
        while (true) {
            lastRounds++;
            Map<Signature, Integer> ids = new HashMap<>();
            int[] newGroup = new int[n];
            for (int s = 0; s < n; s++) {
                int[] sig = new int[257];
                sig[0] = group[s];
                for (int c = 0; c < 256; c++) {
                    int t = dfa.trans[s][c];
                    sig[c + 1] = (t < 0) ? -1 : group[t];   // -1 = etat mort
                }
                Signature key = new Signature(sig);
                Integer id = ids.get(key);
                if (id == null) { id = ids.size(); ids.put(key, id); }
                newGroup[s] = id;
            }
            int newCount = ids.size();
            group = newGroup;
            if (newCount == nbGroups) break;   // plus aucun groupe n'a ete coupe
            nbGroups = newCount;
        }

        // 4. Construction du DFA minimal : un etat par groupe
        //    (on renumerote pour que l'etat initial soit 0)
        int[] renum = new int[nbGroups];
        Arrays.fill(renum, -1);
        int next = 0;
        renum[group[dfa.start]] = next++;
        for (int s = 0; s < n; s++)
            if (renum[group[s]] < 0) renum[group[s]] = next++;

        int[][] trans = new int[nbGroups][];
        boolean[] accept = new boolean[nbGroups];
        for (int s = 0; s < n; s++) {
            int g = renum[group[s]];
            if (trans[g] != null) continue;            // un representant par groupe suffit
            trans[g] = new int[256];
            for (int c = 0; c < 256; c++) {
                int t = dfa.trans[s][c];
                trans[g][c] = (t < 0) ? -1 : renum[group[t]];
            }
            accept[g] = dfa.accept[s];
        }
        return new DFA(trans, accept, 0, dfa.searchMode);
    }

    /** Tableau d'entiers utilisable comme cle de HashMap. */
    private static final class Signature {
        final int[] v;
        final int hash;
        Signature(int[] v) { this.v = v; this.hash = Arrays.hashCode(v); }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object o) {
            return o instanceof Signature && Arrays.equals(v, ((Signature) o).v);
        }
    }
}