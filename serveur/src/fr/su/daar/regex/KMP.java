package fr.su.daar.regex;

// Repris du projet 1 (clone de egrep), sans modification de l'algorithme.
/**
 * Algorithme de Knuth-Morris-Pratt
 *
 * Utilise quand la RegEx est un FACTEUR, c'est-a-dire une simple suite de lettres
 * (que des concatenations, sans '|', '*', '+' ni '.'), par exemple "Sargon".
 *
 * Deux parties :
 *  1. Precalcul du tableau de retenue C (CarryOver), de taille m+1, convention du cours :
 *        mami     -> [-1, 0,-1, 1, 0]
 *        mamamia  -> [-1, 0,-1, 0,-1, 3, 0, 0]
 *        chicha   -> [-1, 0, 0,-1, 0, 2, 0]
 *        Sargon   -> [-1, 0, 0, 0, 0, 0, 0]
 *     C[i] = position du facteur a laquelle reprendre la comparaison quand F[i] ne
 *     correspond pas au caractere du texte (-1 = abandonner ce caractere, passer au suivant).
 *  2. Recherche : on ne recule JAMAIS dans le texte. Complexite O(n) pour la recherche,
 *     O(m) pour le precalcul.
 */
public class KMP {

    private final byte[] factor;
    private final int[] carry;

    public KMP(byte[] factor) {
        if (factor.length == 0) throw new IllegalArgumentException("facteur vide");
        this.factor = factor;
        this.carry = carryOver(factor);
    }

    /** Tableau de retenue (version "optimisee" de la fonction d'echec, comme dans le cours). */
    public static int[] carryOver(byte[] f) {
        int m = f.length;
        // border[i] = longueur du plus long bord (prefixe = suffixe propre) de f[0..i[
        int[] border = new int[m + 1];
        border[0] = -1;
        int k = -1;
        for (int i = 0; i < m; i++) {
            while (k >= 0 && f[k] != f[i]) k = border[k];
            k++;
            border[i + 1] = k;
        }
        // Optimisation : si f[i] == f[border[i]], reprendre a border[i] echouerait forcement
        // de la meme facon, donc on saute directement plus loin.
        int[] c = new int[m + 1];
        c[0] = -1;
        for (int i = 1; i < m; i++)
            c[i] = (f[i] == f[border[i]]) ? c[border[i]] : border[i];
        c[m] = border[m];
        return c;
    }

    /** Position de la premiere occurrence du facteur dans buf[from..to[, ou -1. */
    public int find(byte[] buf, int from, int to) {
        int m = factor.length;
        int j = 0;                                   // position courante dans le facteur
        for (int i = from; i < to; i++) {
            byte t = buf[i];
            while (j >= 0 && factor[j] != t) j = carry[j];
            j++;
            if (j == m) return i + 1 - m;            // facteur trouve, il finit en i
        }
        return -1;
    }

    /** Vrai si buf[from..to[ contient le facteur. */
    public boolean matches(byte[] buf, int from, int to) {
        return find(buf, from, to) >= 0;
    }

    public int length() { return factor.length; }

    public boolean matches(String line) {
        byte[] b = line.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        return matches(b, 0, b.length);
    }

    public int[] carry() { return carry.clone(); }

    /**
     * Si l'arbre ne contient que des CONCAT et des CHAR, renvoie le facteur (les lettres
     * dans l'ordre) ; sinon null (il faut alors utiliser l'automate).
     */
    public static byte[] literalOf(RegexNode tree) {
        StringBuilder sb = new StringBuilder();
        return collect(tree, sb) ? sb.toString().getBytes(java.nio.charset.StandardCharsets.ISO_8859_1) : null;
    }

    private static boolean collect(RegexNode n, StringBuilder sb) {
        switch (n.type) {
            case CHAR:   sb.append(n.c); return true;
            case CONCAT: return collect(n.left, sb) && collect(n.right, sb);
            default:     return false;   // ALT, STAR, DOT : pas un simple facteur
        }
    }
}