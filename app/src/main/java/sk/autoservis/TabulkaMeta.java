package sk.autoservis;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Metadáta tabuľky načítané z dátového slovníka Oracle (USER_TAB_COLUMNS, USER_CONSTRAINTS):
 * stĺpce, primárny kľúč, cudzie kľúče a povolené hodnoty z CHECK obmedzení typu "stlpec IN (...)".
 * Používa ich všeobecný CRUD formulár, takže funguje pre všetky tabuľky bez osobitného kódu.
 */
public final class TabulkaMeta {

    /** Jeden stĺpec tabuľky. */
    public static final class Stlpec {
        public final String nazov;
        public final String typ;            // NUMBER, VARCHAR2, CHAR, DATE, BLOB ...
        public final int dlzka;
        public final boolean povinny;
        public final boolean identity;
        public final boolean maDefault;
        /** Používateľom definovaný typ (objekt, VARRAY, vnorená tabuľka) – vo všeobecnom formulári len na čítanie. */
        public final boolean udt;
        public boolean pk;
        public String fkTabulka;             // ak je stĺpec cudzí kľúč: odkazovaná tabuľka
        public String fkStlpec;
        public List<String> povoleneHodnoty; // z CHECK (stlpec IN ('A','B'))

        Stlpec(String nazov, String typ, int dlzka, boolean povinny, boolean identity, boolean maDefault, boolean udt) {
            this.nazov = nazov;
            this.typ = typ;
            this.dlzka = dlzka;
            this.povinny = povinny;
            this.identity = identity;
            this.maDefault = maDefault;
            this.udt = udt;
        }

        public boolean jeDatum() {
            return typ.equals("DATE") || typ.startsWith("TIMESTAMP");
        }

        public boolean jeCislo() {
            return typ.equals("NUMBER") || typ.equals("FLOAT") || typ.equals("INTEGER");
        }

        public boolean jeBlob() {
            return typ.equals("BLOB");
        }
    }

    public final String tabulka;
    public final List<Stlpec> stlpce = new ArrayList<>();

    private TabulkaMeta(String tabulka) {
        this.tabulka = tabulka;
    }

    public List<Stlpec> pkStlpce() {
        return stlpce.stream().filter(s -> s.pk).toList();
    }

    private static final Pattern IN_ZOZNAM = Pattern.compile("(?is)^\\s*\"?(\\w+)\"?\\s+IN\\s*\\((.*)\\)\\s*$");
    private static final Pattern HODNOTA = Pattern.compile("'([^']*)'");

    public static TabulkaMeta nacitaj(String tabulka) throws SQLException {
        TabulkaMeta meta = new TabulkaMeta(tabulka);
        Map<String, Stlpec> podlaNazvu = new LinkedHashMap<>();

        String sqlStlpce = """
                SELECT column_name, data_type, NVL(char_length, data_length), nullable, identity_column,
                       CASE WHEN data_default IS NULL THEN 'N' ELSE 'A' END,
                       CASE WHEN data_type_owner IS NULL THEN 'N' ELSE 'A' END
                  FROM user_tab_columns
                 WHERE table_name = ?
                 ORDER BY column_id""";
        try (PreparedStatement ps = Db.get().prepareStatement(sqlStlpce)) {
            ps.setString(1, tabulka);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Stlpec s = new Stlpec(rs.getString(1), rs.getString(2), rs.getInt(3),
                            "N".equals(rs.getString(4)), "YES".equals(rs.getString(5)), "A".equals(rs.getString(6)),
                            "A".equals(rs.getString(7)));
                    meta.stlpce.add(s);
                    podlaNazvu.put(s.nazov, s);
                }
            }
        }

        // PK a FK (pri FK aj odkazovaná tabuľka a stĺpec)
        String sqlKluce = """
                SELECT c.constraint_type, cc.column_name, rc.table_name, rcc.column_name
                  FROM user_constraints c
                  JOIN user_cons_columns cc ON cc.constraint_name = c.constraint_name
                  LEFT JOIN user_constraints rc ON rc.constraint_name = c.r_constraint_name
                  LEFT JOIN user_cons_columns rcc ON rcc.constraint_name = rc.constraint_name AND rcc.position = cc.position
                 WHERE c.table_name = ? AND c.constraint_type IN ('P', 'R')""";
        try (PreparedStatement ps = Db.get().prepareStatement(sqlKluce)) {
            ps.setString(1, tabulka);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Stlpec s = podlaNazvu.get(rs.getString(2));
                    if (s == null) {
                        continue;
                    }
                    if ("P".equals(rs.getString(1))) {
                        s.pk = true;
                    } else {
                        s.fkTabulka = rs.getString(3);
                        s.fkStlpec = rs.getString(4);
                    }
                }
            }
        }

        // CHECK obmedzenia v tvare "stlpec IN ('A', 'B', ...)" -> rozbaľovací zoznam
        String sqlCheck = "SELECT search_condition_vc FROM user_constraints WHERE table_name = ? AND constraint_type = 'C'";
        try (PreparedStatement ps = Db.get().prepareStatement(sqlCheck)) {
            ps.setString(1, tabulka);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String podmienka = rs.getString(1);
                    if (podmienka == null) {
                        continue;
                    }
                    Matcher m = IN_ZOZNAM.matcher(podmienka);
                    if (!m.matches()) {
                        continue;
                    }
                    Stlpec s = podlaNazvu.get(m.group(1).toUpperCase());
                    if (s == null) {
                        continue;
                    }
                    List<String> hodnoty = new ArrayList<>();
                    Matcher h = HODNOTA.matcher(m.group(2));
                    while (h.find()) {
                        hodnoty.add(h.group(1));
                    }
                    if (!hodnoty.isEmpty()) {
                        s.povoleneHodnoty = hodnoty;
                    }
                }
            }
        }
        return meta;
    }

    /**
     * SQL výraz s čitateľným popisom riadku odkazovanej tabuľky (pre rozbaľovací zoznam cudzieho kľúča).
     * Pre známe tabuľky je popis ručne zvolený, inak sa použijú prvé dva textové stĺpce.
     */
    public static String popisRiadku(String tabulka) throws SQLException {
        return switch (tabulka) {
            case "ZAKAZNIK" -> "meno || ' ' || priezvisko";
            case "VOZIDLO" -> "spz || ' – ' || znacka || ' ' || model";
            case "ZAMESTNANEC" -> "meno || ' ' || priezvisko || ' (' || pozicia || ')'";
            case "SERVISNA_ZAKAZKA" -> "TO_CHAR(datum_prijatia, 'DD.MM.YYYY') || ' – ' || stav || ' – ' || SUBSTR(popis_problemu, 1, 40)";
            case "SERVISNY_UKON", "NAHRADNY_DIEL" -> "nazov";
            case "FAKTURA" -> "cislo_faktury || ' (' || celkova_suma || ' €)'";
            default -> {
                List<String> texty = new ArrayList<>();
                for (Stlpec s : nacitaj(tabulka).stlpce) {
                    if ((s.typ.startsWith("VARCHAR") || s.typ.equals("CHAR")) && texty.size() < 2) {
                        texty.add(s.nazov);
                    }
                }
                yield texty.isEmpty() ? "''" : String.join(" || ' ' || ", texty);
            }
        };
    }
}
