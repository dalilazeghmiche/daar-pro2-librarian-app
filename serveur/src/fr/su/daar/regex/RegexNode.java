package fr.su.daar.regex;

// Repris du projet 1 (clone de egrep), sans modification de l'algorithme.
/**
 * Noeud de l'arbre syntaxique d'une RegEx (etape 1, Aho-Ullman Fig. 10.29).
 *
 * Operateurs supportes : lettre, '.', concatenation, '|', '*'.
 * Le '+' est reecrit au parsing : X+ = X X*  (pas de type dedie).
 */

public class RegexNode{
	public enum Type {CHAR, DOT, CONCAT, ALT, STAR}
	
	public final Type type;
	public final char c; //utilise seulement si type == CHAR
	public final RegexNode left; //fils gauche ( ou fils unique pour STAR)
	public final RegexNode right; //fils droit (CONCAT, ALT), sinon null
	
	  private RegexNode(Type type, char c, RegexNode left, RegexNode right) {
	        this.type = type;
	        this.c = c;
	        this.left = left;
	        this.right = right;
	    }

	    // --- Constructeurs pratiques ---
	    public static RegexNode letter(char c)                     { return new RegexNode(Type.CHAR, c, null, null); }
	    public static RegexNode dot()                              { return new RegexNode(Type.DOT, '\0', null, null); }
	    public static RegexNode concat(RegexNode a, RegexNode b)   { return new RegexNode(Type.CONCAT, '\0', a, b); }
	    public static RegexNode alt(RegexNode a, RegexNode b)      { return new RegexNode(Type.ALT, '\0', a, b); }
	    public static RegexNode star(RegexNode a)                  { return new RegexNode(Type.STAR, '\0', a, null); }
	  
	    /** Copie profonde (necessaire pour X+ = X X* : on ne partage pas de sous-arbre). */
	    public RegexNode copy() {
	        return new RegexNode(type, c,
	                left == null ? null : left.copy(),
	                right == null ? null : right.copy());
	    }
	    
	    /** Forme totalement parenthesee, ex. a|bc*  ->  (a|(b(c)*)) */
	    @Override
	    public String toString() {
	        switch (type) {
	            case CHAR:   return String.valueOf(c);
	            case DOT:    return ".";
	            case CONCAT: return "(" + left + right + ")";
	            case ALT:    return "(" + left + "|" + right + ")";
	            case STAR:   return "(" + left + ")*";
	            default:     throw new IllegalStateException();
	        }
	    }
	        /** Affichage en arbre indente, pratique pour deboguer et pour le rapport. */
	        public String toTree() {
	            StringBuilder sb = new StringBuilder();
	            toTree(sb, "", true, true);
	            return sb.toString();
	        }
	        
	        private void toTree(StringBuilder sb, String prefix, boolean isLast, boolean isRoot) {
	            sb.append(prefix);
	            if (!isRoot) sb.append(isLast ? "\\-- " : "+-- ");
	            sb.append(label()).append('\n');
	            String childPrefix = isRoot ? "" : prefix + (isLast ? "    " : "|   ");
	            if (left != null && right != null) {
	                left.toTree(sb, childPrefix, false, false);
	                right.toTree(sb, childPrefix, true, false);
	            } else if (left != null) {
	                left.toTree(sb, childPrefix, true, false);
	            }
	        }
	        
	        
	        private String label() {
	            switch (type) {
	                case CHAR:   return "'" + c + "'";
	                case DOT:    return "DOT (n'importe quel caractere)";
	                case CONCAT: return "CONCAT";
	                case ALT:    return "ALT |";
	                case STAR:   return "STAR *";
	                default:     return "?";
	            }
	        }
	        
}






