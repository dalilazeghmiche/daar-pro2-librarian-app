package fr.su.daar.regex;

// Repris du projet 1 (clone de egrep), sans modification de l'algorithme.

/**
 * Algorithme de Boyer-Moore (alternative de KMP).
 *
 * Comme KMP, il ne sert que si la RegEx est un FACTEUR (concatenation).
 * Difference essentielle avec KMP :
 *   - KMP lit le texte de gauche a droite et ne saute jamais de caractere : O(n) toujours.
 *   - Boyer-Moore compare le facteur au texte de DROITE A GAUCHE, et en cas d'echec
 *     decale le facteur de plusieurs positions d'un coup. Sur un texte en langue naturelle,
 *     beaucoup de caracteres ne sont jamais lus : ~ n/m comparaisons dans le meilleur cas.
 *
 * Deux tables precalculees (version de Charras & Lecroq, "Handbook of Exact String Matching" 
 * 
 * https://www.researchgate.net/publication/220693416_Handbook_of_Exact_String_Matching_Algorithms) :
 *
 *  1. Mauvais caractere (bad character), taille 256 :
 *        bad[c] = distance entre la DERNIERE occurrence de c dans le facteur
 *                 (sans compter la derniere lettre) et la fin du facteur ; m si c est absent.
 *     Ex. "Sargon" (m = 6) : bad['o'] = 1, bad['g'] = 2, bad['S'] = 5, bad['x'] = 6.
 *
 *  2. Bon suffixe (good suffix), taille m :
 *        good[i] = decalage sur si le suffixe f[i+1..m[ a deja ete reconnu et que f[i]
 *                  ne correspond pas : on aligne une autre occurrence de ce suffixe
 *                  (ou le plus long prefixe du facteur qui en est aussi un suffixe).
 *
 * A chaque echec en position i du facteur : decalage = max(good[i], bad[c] - (m - 1 - i)).
 *
 * Complexite : precalcul O(m + 256) ; recherche O(n * m) dans le pire cas theorique
 * pour cette version simple, mais sous-lineaire en pratique sur du texte.
 */

public class BoyerMoore {
	private final byte[] factor;
    private final int[] bad;
    private final int[] good;

    public BoyerMoore(byte[] factor) {
        if (factor.length == 0) throw new IllegalArgumentException("facteur vide");
        this.factor = factor;
        this.bad = badCharacter(factor);
        this.good = goodSuffix(factor);
    }

    // =====================================================================
    //  Precalculs
    // =====================================================================

    /** Table du mauvais caractere. */
    public static int[] badCharacter(byte[] f) {
        int m = f.length;
        int[] bad = new int[256];
        java.util.Arrays.fill(bad, m);
        for (int i = 0; i < m - 1; i++)
            bad[f[i] & 0xFF] = m - 1 - i;
        return bad;
    }

    /**
     * suff[i] = longueur du plus long suffixe du facteur qui se termine en position i
     * (ex. "abcab" : suff[1] = 2 car "ab" finit en 1 et est un suffixe).
     */
    static int[] suffixes(byte[] f) {
        int m = f.length;
        int[] suff = new int[m];
        suff[m - 1] = m;
        int g = m - 1, fpos = m - 1;
        for (int i = m - 2; i >= 0; i--) {
            if (i > g && suff[i + m - 1 - fpos] < i - g) {
                suff[i] = suff[i + m - 1 - fpos];
            } else {
                if (i < g) g = i;
                fpos = i;
                while (g >= 0 && f[g] == f[g + m - 1 - fpos]) g--;
                suff[i] = fpos - g;
            }
        }
        return suff;
    }

    /** Table du bon suffixe. */
    public static int[] goodSuffix(byte[] f) {
        int m = f.length;
        int[] suff = suffixes(f);
        int[] good = new int[m];
        java.util.Arrays.fill(good, m);
        // Cas 2 : seul un prefixe du facteur est aussi un suffixe
        int j = 0;
        for (int i = m - 1; i >= 0; i--)
            if (suff[i] == i + 1)
                for (; j < m - 1 - i; j++)
                    if (good[j] == m) good[j] = m - 1 - i;
        // Cas 1 : le suffixe reconnu reapparait ailleurs dans le facteur
        for (int i = 0; i <= m - 2; i++)
            good[m - 1 - suff[i]] = m - 1 - i;
        return good;
    }

    // =====================================================================
    //  Recherche
    // =====================================================================

    /** Position de la premiere occurrence du facteur dans buf[from..to[, ou -1. */
    public int find(byte[] buf, int from, int to) {
        int m = factor.length;
        int s = from;                                  // position du facteur dans le texte
        while (s <= to - m) {
            int i = m - 1;
            while (i >= 0 && factor[i] == buf[s + i]) i--;   // de droite a gauche
            if (i < 0) return s;                             // trouve
            s += Math.max(good[i], bad[buf[s + i] & 0xFF] - (m - 1 - i));
        }
        return -1;
    }

    /** Vrai si buf[from..to[ contient le facteur. */
    public boolean matches(byte[] buf, int from, int to) {
        return find(buf, from, to) >= 0;
    }

    public boolean matches(String line) {
        byte[] b = line.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        return matches(b, 0, b.length);
    }

    public int length()   { return factor.length; }
    public int[] bad()    { return bad.clone(); }
    public int[] good()   { return good.clone(); }

}
