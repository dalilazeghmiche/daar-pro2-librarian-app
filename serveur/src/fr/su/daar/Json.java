package fr.su.daar;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

/**
 * Ecriture JSON minimale (sans bibliotheque externe) : objets, tableaux, chaines, nombres, booleens.
 * Utilisation :  new Json().objet().cle("id").val(11).cle("titre").val("Alice").fin().toString()
 */
final class Json {

    private final StringBuilder sb = new StringBuilder();
    private final Deque<Character> ouverts = new ArrayDeque<>();   // '}' ou ']' a ecrire a chaque fin()
    private boolean virgule = false;                               // faut-il une virgule avant la prochaine valeur ?

    Json objet()   { sep(); sb.append('{'); ouverts.push('}'); virgule = false; return this; }
    Json tableau() { sep(); sb.append('['); ouverts.push(']'); virgule = false; return this; }
    Json fin()     { sb.append(ouverts.pop()); virgule = true; return this; }

    Json cle(String k) { sep(); chaine(k); sb.append(':'); virgule = false; return this; }

    Json val(String s)  { sep(); if (s == null) sb.append("null"); else chaine(s); virgule = true; return this; }
    Json val(long n)    { sep(); sb.append(n); virgule = true; return this; }
    Json val(double x)  { sep(); sb.append(Double.isFinite(x) ? String.valueOf(x) : "null"); virgule = true; return this; }
    Json val(boolean b) { sep(); sb.append(b); virgule = true; return this; }

    Json map(Map<?, ?> m) {
        objet();
        for (var e : m.entrySet()) {
            cle(String.valueOf(e.getKey()));
            if (e.getValue() instanceof Number n) val(n.longValue()); else val(String.valueOf(e.getValue()));
        }
        return fin();
    }

    private void sep() { if (virgule) sb.append(','); virgule = false; }

    private void chaine(String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> { if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c); }
            }
        }
        sb.append('"');
    }

    @Override public String toString() {
        if (!ouverts.isEmpty()) throw new IllegalStateException("JSON non ferme");
        return sb.toString();
    }
}
