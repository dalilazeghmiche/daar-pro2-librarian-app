package fr.su.daar.regex;

// Repris du projet 1 (clone de egrep), sans modification de l'algorithme.
/**
 * Parser descendant recursif : RegEx (String) -> arbre syntaxique (RegexNode).
 *
 * Grammaire (de la priorite la plus faible a la plus forte) :
 *
 *   alt    ::= concat ( '|' concat )*
 *   concat ::= repeat repeat*
 *   repeat ::= atom ( '*' | '+' )*
 *   atom   ::= lettre | '.' | '(' alt ')' | '\' caractere
 *
 * Donc : '*' et '+' lient plus fort que la concatenation,
 *        qui lie plus fort que '|'.
 * Les operateurs binaires sont associatifs a gauche : abc = (ab)c.
 */
public class RegexParser {

    private final String regex;
    private int pos = 0;

    private RegexParser(String regex) {
        this.regex = regex;
    }

    /** Point d'entree : leve IllegalArgumentException si la RegEx est mal formee. */
    public static RegexNode parse(String regex) {
        if (regex == null || regex.isEmpty())
            throw new IllegalArgumentException("RegEx vide");
        RegexParser p = new RegexParser(regex);
        RegexNode tree = p.parseAlt();
        if (p.pos < regex.length())   // il reste des caracteres : ex. ')' en trop
            throw p.error("caractere inattendu '" + regex.charAt(p.pos) + "'");
        return tree;
    }

    // alt ::= concat ( '|' concat )*
    private RegexNode parseAlt() {
        RegexNode left = parseConcat();
        while (peek() == '|') {
            pos++;
            RegexNode right = parseConcat();
            left = RegexNode.alt(left, right);
        }
        return left;
    }

    // concat ::= repeat repeat*
    private RegexNode parseConcat() {
        if (atEndOfConcat())
            throw error("expression vide (ex. 'a|', '()' ou '|a')");
        RegexNode left = parseRepeat();
        while (!atEndOfConcat()) {
            RegexNode right = parseRepeat();
            left = RegexNode.concat(left, right);
        }
        return left;
    }

    // repeat ::= atom ( '*' | '+' )*
    private RegexNode parseRepeat() {
        RegexNode node = parseAtom();
        while (peek() == '*' || peek() == '+') {
            char op = regex.charAt(pos++);
            if (op == '*') {
                node = RegexNode.star(node);
            } else {                         // X+  devient  X . X*
                node = RegexNode.concat(node, RegexNode.star(node.copy()));
            }
        }
        return node;
    }

    // atom ::= lettre | '.' | '(' alt ')' | '\' caractere
    private RegexNode parseAtom() {
        char c = regex.charAt(pos);
        switch (c) {
            case '(': {
                pos++;
                RegexNode inside = parseAlt();
                if (peek() != ')')
                    throw error("parenthese fermante manquante");
                pos++;
                return inside;
            }
            case '.':
                pos++;
                return RegexNode.dot();
            case '\\':                        // echappement : \. \* \( ...
                if (pos + 1 >= regex.length())
                    throw error("'\\' en fin de RegEx");
                pos += 2;
                return RegexNode.letter(regex.charAt(pos - 1));
            case '*': case '+':
                throw error("'" + c + "' sans rien a repeter");
            case ')': case '|':
                throw error("caractere inattendu '" + c + "'");  // normalement deja filtre
            case '?': case '[': case '{': case '^': case '$':
                throw error("operateur ERE '" + c + "' non supporte (hors perimetre du projet) ; "
                        + "ecrire \\" + c + " pour chercher le caractere '" + c + "'");
            default:
                pos++;
                return RegexNode.letter(c);
        }
    }

    // --- Utilitaires ---

    /** Caractere courant, ou '\0' si on est a la fin. */
    private char peek() {
        return pos < regex.length() ? regex.charAt(pos) : '\0';
    }

    /** Une concatenation s'arrete en fin de chaine, sur '|' ou sur ')'. */
    private boolean atEndOfConcat() {
        return pos >= regex.length() || peek() == '|' || peek() == ')';
    }

    private IllegalArgumentException error(String msg) {
        return new IllegalArgumentException(
                "Erreur de syntaxe a la position " + pos + " : " + msg + "\n  " + regex
                + "\n  " + " ".repeat(pos) + "^");
    }
}