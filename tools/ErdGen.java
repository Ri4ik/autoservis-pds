import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.sql.*;
import java.util.*;
import java.util.List;

/** Vygeneruje ER diagram (relačný model) z dátového slovníka Oracle. Usage: ErdGen <out.png> (napr. Autoservis_ERD_v2.png); env DB_URL, DB_USER, DB_PASS */
public class ErdGen {

    record Col(String name, String type, boolean notNull, boolean pk, boolean fk, boolean uq, boolean udt) {}
    record Box(String table, int x, int y, List<Col> cols) {
        int h() { return HEAD + cols.size() * ROW + 8; }
    }

    static final int W = 480, HEAD = 28, ROW = 19, SCALE = 2, G = 170;
    static final Color HEAD_BG = new Color(0xD6, 0xE4, 0xF5), BORDER = new Color(0x3A, 0x5A, 0x85), UDT_C = new Color(0x8A, 0x3F, 0x9E);
    static final Map<String, int[]> POS = new LinkedHashMap<>();
    static int col(int i) { return 40 + i * (W + G); }
    static {
        POS.put("ZAKAZNIK", new int[]{col(0), 100});
        POS.put("VOZIDLO", new int[]{col(1), 100});
        POS.put("SERVISNA_ZAKAZKA", new int[]{col(2), 100});
        POS.put("ZAMESTNANEC", new int[]{col(3), 100});
        POS.put("VYKONANY_UKON", new int[]{col(3), 290});
        POS.put("SERVISNY_UKON", new int[]{col(4), 290});
        POS.put("POUZITY_DIEL", new int[]{col(2), 480});
        POS.put("NAHRADNY_DIEL", new int[]{col(2), 700});
        POS.put("FAKTURA", new int[]{col(1), 480});
        POS.put("PLATBA", new int[]{col(0), 480});
        POS.put("PRILOHA", new int[]{col(1), 700});
        POS.put("SERVISNA_POLOZKA_ZAKAZKY", new int[]{col(3), 590});
    }

    public static void main(String[] a) throws Exception {
        Map<String, Box> boxes = new LinkedHashMap<>();
        Map<String, String> typy = new LinkedHashMap<>();
        try (Connection c = DriverManager.getConnection(System.getenv("DB_URL"), System.getenv("DB_USER"), System.getenv("DB_PASS"))) {
            for (var e : POS.entrySet()) {
                boxes.put(e.getKey(), new Box(e.getKey(), e.getValue()[0], e.getValue()[1], stlpce(c, e.getKey())));
            }
            for (String t : new String[]{"T_STAV_KM", "T_HISTORIA_KM", "T_BOD_KONTROLY", "T_KONTROLNY_ZOZNAM", "T_SERVISNA_POLOZKA"}) {
                typy.put(t, popisTypu(c, t));
            }
        }

        int sirka = col(4) + W + 50, vyska = 1010;
        BufferedImage img = new BufferedImage(sirka * SCALE, vyska * SCALE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.scale(SCALE, SCALE);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, sirka, vyska);

        g.setColor(Color.BLACK);
        g.setFont(new Font("SansSerif", Font.BOLD, 22));
        g.drawString("Autoservis – relačný dátový model (Oracle)", 40, 42);
        g.setFont(new Font("SansSerif", Font.PLAIN, 13));
        g.setColor(Color.DARK_GRAY);
        g.drawString("12 tabuliek, 13 cudzích kľúčov; objektový atribút (T_SERVISNA_POLOZKA), VARRAY (T_HISTORIA_KM), NESTED TABLE (T_KONTROLNY_ZOZNAM). "
                + "Vygenerované z dátového slovníka schémy.", 40, 64);

        // vzťahy: dieta -> rodič, body trasy (prvý bod pri dieťati)
        Box zak = boxes.get("ZAKAZNIK"), voz = boxes.get("VOZIDLO"), sz = boxes.get("SERVISNA_ZAKAZKA"), zam = boxes.get("ZAMESTNANEC"),
                vu = boxes.get("VYKONANY_UKON"), su = boxes.get("SERVISNY_UKON"), pd = boxes.get("POUZITY_DIEL"), nd = boxes.get("NAHRADNY_DIEL"),
                fa = boxes.get("FAKTURA"), pl = boxes.get("PLATBA"), pr = boxes.get("PRILOHA"), spz = boxes.get("SERVISNA_POLOZKA_ZAKAZKY");
        int szb = sz.y + sz.h();
        vztah(g, "FK_VOZIDLO_ZAKAZNIK", false, voz.x, voz.y + 60, zak.x + W, voz.y + 60);
        vztah(g, "FK_ZAKAZKA_VOZIDLO", false, sz.x, sz.y + 60, voz.x + W, sz.y + 60);
        vztah(g, "FK_ZAKAZKA_ZAMESTNANEC", true, sz.x + W, sz.y + 60, zam.x, sz.y + 60);
        vztah(g, "FK_VUKON_ZAKAZKA", false, vu.x, vu.y + 20, sz.x + W, vu.y + 20);
        vztah(g, "FK_VUKON_ZAMESTNANEC", true, vu.x + 230, vu.y, vu.x + 230, zam.y + zam.h());
        vztah(g, "FK_VUKON_UKON", false, vu.x + W, vu.y + 50, su.x, vu.y + 50);
        vztah(g, "FK_PDIEL_ZAKAZKA", false, pd.x + 230, pd.y, pd.x + 230, szb);
        vztah(g, "FK_PDIEL_DIEL", false, pd.x + 230, pd.y + pd.h(), pd.x + 230, nd.y);
        vztah(g, "FK_PLATBA_FAKTURA", false, pl.x + W, pl.y + 50, fa.x, pl.y + 50);
        vztah(g, "FK_FAKTURA_ZAKAZKA (1:1)", false, fa.x + W, fa.y + 50, fa.x + W + 40, fa.y + 50,
                fa.x + W + 40, szb + 34, sz.x + 50, szb + 34, sz.x + 50, szb);
        vztah(g, "FK_PRILOHA_ZAKAZKA", false, pr.x + W, pr.y + 50, pr.x + W + 90, pr.y + 50,
                pr.x + W + 90, szb + 64, sz.x + 120, szb + 64, sz.x + 120, szb);
        vztah(g, "FK_POLOZKA_ZAKAZKA", false, spz.x, spz.y + 40, spz.x - 60, spz.y + 40,
                spz.x - 60, szb + 94, sz.x + W - 60, szb + 94, sz.x + W - 60, szb);

        for (Box b : boxes.values()) {
            kresliBox(g, b);
        }
        kresliTypy(g, typy, col(4), 430);
        kresliLegendu(g, 40, 900);
        g.dispose();
        ImageIO.write(img, "png", new File(a[0]));
        System.out.println("ok " + a[0]);
    }

    static List<Col> stlpce(Connection c, String t) throws SQLException {
        Set<String> pk = new HashSet<>(), fk = new HashSet<>(), uq = new HashSet<>();
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT k.constraint_type, cc.column_name FROM user_constraints k
                  JOIN user_cons_columns cc ON cc.constraint_name = k.constraint_name
                 WHERE k.table_name = ? AND k.constraint_type IN ('P','R','U')""")) {
            ps.setString(1, t);
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) {
                    (switch (r.getString(1)) { case "P" -> pk; case "R" -> fk; default -> uq; }).add(r.getString(2));
                }
            }
        }
        List<Col> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT column_name, data_type, data_length, data_precision, data_scale, char_length, nullable, data_type_owner
                  FROM user_tab_columns WHERE table_name = ? ORDER BY column_id""")) {
            ps.setString(1, t);
            try (ResultSet r = ps.executeQuery()) {
                while (r.next()) {
                    String n = r.getString(1), dt = r.getString(2);
                    String typ = switch (dt) {
                        case "NUMBER" -> r.getObject(4) == null ? "NUMBER" : "NUMBER(" + r.getInt(4) + (r.getInt(5) > 0 ? "," + r.getInt(5) : "") + ")";
                        case "VARCHAR2", "CHAR" -> dt + "(" + r.getInt(6) + ")";
                        default -> dt;
                    };
                    boolean udt = r.getString(8) != null;
                    if (udt) {
                        typ = dt + switch (dt) {
                            case "T_HISTORIA_KM" -> "  «VARRAY»";
                            case "T_KONTROLNY_ZOZNAM" -> "  «NESTED TABLE»";
                            default -> "  «OBJECT»";
                        };
                    }
                    out.add(new Col(n, typ, "N".equals(r.getString(7)), pk.contains(n), fk.contains(n), uq.contains(n), udt));
                }
            }
        }
        return out;
    }

    static String popisTypu(Connection c, String t) throws SQLException {
        StringBuilder sb = new StringBuilder();
        try (PreparedStatement ps = c.prepareStatement("SELECT typecode FROM user_types WHERE type_name = ?")) {
            ps.setString(1, t);
            try (ResultSet r = ps.executeQuery()) {
                if (!r.next()) return "(neexistuje)";
                if (r.getString(1).equals("COLLECTION")) {
                    try (PreparedStatement p2 = c.prepareStatement("SELECT coll_type, upper_bound, elem_type_name FROM user_coll_types WHERE type_name = ?")) {
                        p2.setString(1, t);
                        try (ResultSet r2 = p2.executeQuery()) {
                            r2.next();
                            return (r2.getString(1).equals("TABLE") ? "TABLE OF " : "VARRAY(" + r2.getInt(2) + ") OF ") + r2.getString(3);
                        }
                    }
                }
            }
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT attr_name, attr_type_name, length, precision FROM user_type_attrs WHERE type_name = ? ORDER BY attr_no")) {
            ps.setString(1, t);
            try (ResultSet r = ps.executeQuery()) {
                List<String> at = new ArrayList<>();
                while (r.next()) at.add(r.getString(1).toLowerCase() + " " + r.getString(2));
                sb.append("OBJECT (").append(String.join(", ", at)).append(")");
            }
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT method_name FROM user_type_methods WHERE type_name = ? ORDER BY method_no")) {
            ps.setString(1, t);
            try (ResultSet r = ps.executeQuery()) {
                List<String> m = new ArrayList<>();
                while (r.next()) if (!m.contains(r.getString(1))) m.add(r.getString(1).toLowerCase());
                if (!m.isEmpty()) sb.append("\nmetódy: ").append(String.join(", ", m));
            }
        }
        return sb.toString();
    }

    static void kresliBox(Graphics2D g, Box b) {
        int h = b.h();
        g.setColor(new Color(0, 0, 0, 30));
        g.fillRoundRect(b.x + 4, b.y + 4, W, h, 10, 10);
        g.setColor(Color.WHITE);
        g.fillRoundRect(b.x, b.y, W, h, 10, 10);
        g.setColor(HEAD_BG);
        g.fillRoundRect(b.x, b.y, W, HEAD, 10, 10);
        g.fillRect(b.x, b.y + HEAD - 10, W, 10);
        g.setColor(BORDER);
        g.setStroke(new BasicStroke(1.4f));
        g.drawRoundRect(b.x, b.y, W, h, 10, 10);
        g.drawLine(b.x, b.y + HEAD, b.x + W, b.y + HEAD);
        g.setFont(new Font("SansSerif", Font.BOLD, 14));
        g.setColor(BORDER.darker());
        g.drawString(b.table(), b.x + 10, b.y + 19);
        int y = b.y + HEAD + 15;
        for (Col c : b.cols()) {
            String znak = (c.pk() ? "PK " : "") + (c.fk() ? "FK " : "") + (c.uq() && !c.pk() ? "U " : "");
            g.setFont(new Font("SansSerif", Font.BOLD, 11));
            g.setColor(c.pk() ? new Color(0xB0, 0x6A, 0x00) : new Color(0x2E, 0x7D, 0x32));
            g.drawString(znak.trim(), b.x + 8, y);
            g.setFont(new Font("Monospaced", c.pk() ? Font.BOLD : Font.PLAIN, 12));
            g.setColor(c.udt() ? UDT_C : Color.BLACK);
            g.drawString((c.notNull() ? "* " : "  ") + c.name().toLowerCase(), b.x + 62, y);
            g.setFont(new Font("Monospaced", Font.PLAIN, 11));
            g.setColor(c.udt() ? UDT_C : Color.GRAY);
            g.drawString(c.type(), b.x + 240, y);
            y += ROW;
        }
    }

    /** Čiara vzťahu: pri dieťati „vrania noha“ (N), pri rodičovi zvislá čiarka (1); prerušovaná = voliteľný FK. */
    static void vztah(Graphics2D g, String nazov, boolean volitelny, int... b) {
        g.setColor(new Color(0x55, 0x55, 0x55));
        g.setStroke(volitelny ? new BasicStroke(1.4f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[]{6, 4}, 0) : new BasicStroke(1.4f));
        Path2D p = new Path2D.Double();
        p.moveTo(b[0], b[1]);
        for (int i = 2; i < b.length; i += 2) p.lineTo(b[i], b[i + 1]);
        g.draw(p);
        g.setStroke(new BasicStroke(1.4f));
        // vrania noha pri dieťati
        double dx = b[2] - b[0], dy = b[3] - b[1], d = Math.hypot(dx, dy); dx /= d; dy /= d;
        double px = -dy, py = dx;
        double bx = b[0] + dx * 14, by = b[1] + dy * 14;
        g.draw(new Line2D.Double(bx, by, b[0] + px * 8, b[1] + py * 8));
        g.draw(new Line2D.Double(bx, by, b[0] - px * 8, b[1] - py * 8));
        g.draw(new Line2D.Double(bx, by, b[0], b[1]));
        // čiarka pri rodičovi
        int n = b.length;
        double ex = b[n - 2], ey = b[n - 1], fx = b[n - 4] - ex, fy = b[n - 3] - ey, f = Math.hypot(fx, fy); fx /= f; fy /= f;
        double cx = ex + fx * 10, cy = ey + fy * 10;
        g.draw(new Line2D.Double(cx - fy * 8, cy + fx * 8, cx + fy * 8, cy - fx * 8));
        // názov vzťahu – na najdlhšom úseku
        int k = 0; double naj = -1;
        for (int i = 0; i + 3 < b.length; i += 2) {
            double dl = Math.hypot(b[i + 2] - b[i], b[i + 3] - b[i + 1]);
            if (dl > naj) { naj = dl; k = i; }
        }
        g.setFont(new Font("SansSerif", Font.ITALIC, 10));
        g.setColor(new Color(0x77, 0x77, 0x77));
        double mx = (b[k] + b[k + 2]) / 2.0, my = (b[k + 1] + b[k + 3]) / 2.0;
        boolean zvisly = Math.abs(b[k + 2] - b[k]) < Math.abs(b[k + 3] - b[k + 1]);
        int sw = g.getFontMetrics().stringWidth(nazov);
        if (zvisly && naj < sw + 40) {
            // krátky zvislý úsek: vodorovný text vedľa čiary
            g.drawString(nazov, (float) mx + 8, (float) my + 4);
        } else if (zvisly) {
            // zvislý úsek: text otočený pozdĺž čiary, aby sa zmestil do úzkej medzery
            AffineTransform t = g.getTransform();
            g.translate(mx - 4, my + sw / 2.0);
            g.rotate(-Math.PI / 2);
            g.drawString(nazov, 0, 0);
            g.setTransform(t);
        }
        else {
            g.drawString(nazov, (float) (mx - sw / 2.0), (float) my - 5);
        }
    }

    static void kresliTypy(Graphics2D g, Map<String, String> typy, int x, int y) {
        int w = 390;
        List<String[]> riadky = new ArrayList<>();
        for (var e : typy.entrySet()) {
            riadky.add(new String[]{e.getKey(), null});
            for (String r : zalom(e.getValue(), 52)) riadky.add(new String[]{null, r});
        }
        int h = 34 + riadky.size() * 16 + 8;
        g.setColor(new Color(0xF7, 0xF0, 0xFA));
        g.fillRoundRect(x, y, w, h, 10, 10);
        g.setColor(UDT_C);
        g.setStroke(new BasicStroke(1.4f));
        g.drawRoundRect(x, y, w, h, 10, 10);
        g.setFont(new Font("SansSerif", Font.BOLD, 14));
        g.drawString("Objektové typy a kolekcie", x + 10, y + 20);
        int yy = y + 40;
        for (String[] r : riadky) {
            if (r[0] != null) {
                g.setFont(new Font("Monospaced", Font.BOLD, 12));
                g.setColor(UDT_C);
                g.drawString(r[0], x + 10, yy);
            } else {
                g.setFont(new Font("Monospaced", Font.PLAIN, 11));
                g.setColor(Color.DARK_GRAY);
                g.drawString(r[1], x + 22, yy);
            }
            yy += 16;
        }
    }

    static List<String> zalom(String text, int max) {
        List<String> out = new ArrayList<>();
        for (String odstavec : text.split("\n")) {
            StringBuilder riadok = new StringBuilder();
            for (String slovo : odstavec.split(" ")) {
                if (riadok.length() + slovo.length() + 1 > max && riadok.length() > 0) {
                    out.add(riadok.toString());
                    riadok = new StringBuilder("  ");
                }
                riadok.append(riadok.length() > 0 && !riadok.toString().equals("  ") ? " " : "").append(slovo);
            }
            out.add(riadok.toString());
        }
        return out;
    }

    static void kresliLegendu(Graphics2D g, int x, int y) {
        g.setFont(new Font("SansSerif", Font.BOLD, 12));
        g.setColor(Color.BLACK);
        g.drawString("Legenda:", x, y);
        g.setFont(new Font("SansSerif", Font.PLAIN, 12));
        g.setColor(Color.DARK_GRAY);
        g.drawString("PK – primárny kľúč   FK – cudzí kľúč   U – unikátny   * – NOT NULL   fialová – objektový typ / kolekcia", x, y + 20);
        g.drawString("Vzťah: vrania noha = strana N (dieťa), čiarka = strana 1 (rodič); plná čiara = povinný FK, prerušovaná = voliteľný FK (NULL).", x, y + 40);
        g.drawString("Nested table T_KONTROLNY_ZOZNAM je uložená v tabuľke SERVISNA_ZAKAZKA_KONTROLA_NT (CHECK stav IN OK / CHYBA / NEKONTROLOVANE).", x, y + 60);
    }
}
