package fr.su.daar.regex;

// Repris du projet 1 (clone de egrep), sans modification de l'algorithme.
import java.util.ArrayList;
import java.util.List;

/**
 * Automate fini non deterministe avec epsilon-transitions (etape 2).
 * Construction d'Aho-Ullman (Fig. 10.27 / 10.28), aussi appelee construction de Thompson.
 *
 * Representation :
 *  - un etat est un entier 0, 1, 2, ...
 *  - chaque etat a AU PLUS UNE transition etiquetee (propriete de la construction) :
 *        label.get(q)  = caractere (0..255), DOT, ou NONE
 *        target.get(q) = etat d'arrivee de cette transition
 *  - chaque etat a une liste (eventuellement vide) d'epsilon-transitions : eps.get(q)
 *  - un unique etat initial (start) et un unique etat final (accept).
 */
public class NFA {

    public static final int NONE = -1;   // pas de transition etiquetee
    public static final int DOT  = 256;  // '.' : n'importe quel caractere (0..255)

    private final List<Integer> label  = new ArrayList<>();
    private final List<Integer> target = new ArrayList<>();
    private final List<List<Integer>> eps = new ArrayList<>();

    public int start;
    public int accept;

    /** Construit le NFA d'un arbre syntaxique. */
    public static NFA fromTree(RegexNode tree) {
        NFA nfa = new NFA();
        int[] frag = nfa.build(tree);
        nfa.start = frag[0];
        nfa.accept = frag[1];
        return nfa;
    }

    /**
     * Construction recursive : renvoie un "fragment" {debut, fin}
     * representant l'automate du sous-arbre.
     */
    private int[] build(RegexNode n) {
        switch (n.type) {

            case CHAR: {             // s --c--> f
                int s = newState(), f = newState();
                setTransition(s, n.c, f);
                return new int[]{s, f};
            }

            case DOT: {              // s --.--> f
                int s = newState(), f = newState();
                setTransition(s, DOT, f);
                return new int[]{s, f};
            }

            case CONCAT: {           // [R] --eps--> [S]
                int[] r = build(n.left);
                int[] s = build(n.right);
                addEps(r[1], s[0]);
                return new int[]{r[0], s[1]};
            }

            case ALT: {              //       eps-> [R] -eps
                                     //   s <                > f
                                     //       eps-> [S] -eps
                int s = newState();
                int[] r1 = build(n.left);
                int[] r2 = build(n.right);
                int f = newState();
                addEps(s, r1[0]);
                addEps(s, r2[0]);
                addEps(r1[1], f);
                addEps(r2[1], f);
                return new int[]{s, f};
            }

            case STAR: {             // s -eps-> [R] -eps-> f
                                     // s -eps-> f          (zero fois)
                                     // fin(R) -eps-> debut(R) (recommencer)
                int s = newState();
                int[] r = build(n.left);
                int f = newState();
                addEps(s, r[0]);
                addEps(s, f);
                addEps(r[1], r[0]);
                addEps(r[1], f);
                return new int[]{s, f};
            }

            default:
                throw new IllegalStateException("type inconnu " + n.type);
        }
    }

    // --- Primitives de construction ---

    private int newState() {
        label.add(NONE);
        target.add(NONE);
        eps.add(new ArrayList<>());
        return label.size() - 1;
    }

    private void setTransition(int from, int c, int to) {
        label.set(from, c);
        target.set(from, to);
    }

    private void addEps(int from, int to) {
        eps.get(from).add(to);
    }

    // --- Acces (utilises par la simulation et la determinisation) ---

    public int size()                    { return label.size(); }
    public int label(int q)              { return label.get(q); }
    public int target(int q)             { return target.get(q); }
    public List<Integer> eps(int q)      { return eps.get(q); }

    // --- Affichage ---

    /** Liste lisible de toutes les transitions, pour deboguer. */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("NFA : ").append(size()).append(" etats, initial = q").append(start)
          .append(", final = q").append(accept).append('\n');
        for (int q = 0; q < size(); q++) {
            if (label.get(q) != NONE)
                sb.append("  q").append(q).append(" --").append(labelName(label.get(q)))
                  .append("--> q").append(target.get(q)).append('\n');
            for (int t : eps.get(q))
                sb.append("  q").append(q).append(" --eps--> q").append(t).append('\n');
        }
        return sb.toString();
    }

    /**
     * Export au format Graphviz (DOT) : coller la sortie sur
     * https://dreampuf.github.io/GraphvizOnline pour obtenir une figure pour le rapport.
     */
    public String toDot() {
        StringBuilder sb = new StringBuilder("digraph NFA {\n  rankdir=LR;\n");
        sb.append("  node [shape=circle];\n");
        sb.append("  q").append(accept).append(" [shape=doublecircle];\n");
        sb.append("  init [shape=point];\n  init -> q").append(start).append(";\n");
        for (int q = 0; q < size(); q++) {
            if (label.get(q) != NONE)
                sb.append("  q").append(q).append(" -> q").append(target.get(q))
                  .append(" [label=\"").append(labelName(label.get(q))).append("\"];\n");
            for (int t : eps.get(q))
                sb.append("  q").append(q).append(" -> q").append(t).append(" [label=\"&epsilon;\"];\n");
        }
        return sb.append("}\n").toString();
    }

    private static String labelName(int c) {
        return c == DOT ? "." : String.valueOf((char) c);
    }
}