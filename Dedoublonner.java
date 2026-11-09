import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * DAAR Projet 2 - Retrait des doublons detectes par la distance de Jaccard.
 *
 * Le Projet Gutenberg publie parfois le meme texte sous deux numeros (ex. Bible 10 et 30,
 * Moby-Dick 15 et 2701). Deux livres a distance de Jaccard < SEUIL sont consideres comme un doublon :
 * on garde le plus petit numero et on deplace l'autre (livre + index) dans le dossier doublons/.
 * Rien n'est supprime : on peut tout remettre en place a la main.
 *
 * Usage :  java Dedoublonner.java            (seuil 0.05)
 *          java Dedoublonner.java 0.05
 * Puis relancer :  java Jaccard.java   puis   java Jaccard.java 0.70
 *
 * Sortie : doublons/doublons.csv  (idGarde;idRetire;distance)  -> tableau pour le rapport
 */
public class Dedoublonner {

    public static void main(String[] args) throws IOException {
        double seuil = args.length > 0 ? Double.parseDouble(args[0].replace(',', '.')) : 0.05;
        Path books = Paths.get("books"), index = Paths.get("index"), jac = Paths.get("jaccard");
        Path doublons = Paths.get("doublons");
        Files.createDirectories(doublons);

        int[] ids = Files.readAllLines(jac.resolve("ids.txt")).stream()
                .filter(s -> !s.isBlank()).mapToInt(Integer::parseInt).toArray();
        int n = ids.length;
        float[][] d = new float[n][n];
        try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(
                Files.newInputStream(jac.resolve("distances.bin")), 1 << 16))) {
            for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) d[i][j] = in.readFloat();
        }

        // ordre croissant des ids : pour chaque paire proche, on retire le plus grand numero
        Integer[] ordre = new Integer[n];
        for (int i = 0; i < n; i++) ordre[i] = i;
        java.util.Arrays.sort(ordre, (a, b) -> Integer.compare(ids[a], ids[b]));

        Set<Integer> retires = new HashSet<>();
        List<String> rapport = new ArrayList<>();
        for (int a = 0; a < n; a++) {
            int i = ordre[a];
            if (retires.contains(ids[i])) continue;
            for (int b = a + 1; b < n; b++) {
                int j = ordre[b];
                if (!retires.contains(ids[j]) && d[i][j] < seuil) {
                    retires.add(ids[j]);
                    rapport.add(ids[i] + ";" + ids[j] + ";" + String.format(Locale.ROOT, "%.4f", Math.abs(d[i][j])));
                }
            }
        }

        for (int id : retires) {
            deplacer(books.resolve(id + ".txt"), doublons.resolve(id + ".txt"));
            deplacer(index.resolve(id + ".idx"), doublons.resolve(id + ".idx"));
        }
        // metadata.csv sans les livres retires
        Path meta = books.resolve("metadata.csv");
        List<String> lignes = Files.readAllLines(meta, StandardCharsets.UTF_8);
        List<String> gardees = new ArrayList<>();
        gardees.add(lignes.get(0));
        for (String l : lignes.subList(1, lignes.size()))
            if (!l.isBlank() && !retires.contains(Integer.parseInt(l.split(";", 2)[0]))) gardees.add(l);
        Files.write(meta, gardees, StandardCharsets.UTF_8);

        rapport.add(0, "idGarde;idRetire;distance");
        Files.write(doublons.resolve("doublons.csv"), rapport, StandardCharsets.UTF_8);
        System.out.printf("%d doublons retires (distance < %.2f), deplaces dans %s%n", retires.size(), seuil, doublons.toAbsolutePath());
        System.out.printf("Bibliotheque : %d livres (minimum demande : 1664)%n", gardees.size() - 1);
        System.out.println("Relancez maintenant :  java Jaccard.java   puis   java Jaccard.java 0.70");
    }

    static void deplacer(Path de, Path vers) throws IOException {
        if (Files.exists(de)) Files.move(de, vers, StandardCopyOption.REPLACE_EXISTING);
    }
}
