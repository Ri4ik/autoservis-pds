package sk.autoservis;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Zistí, či je v schéme objektový typ T_ZAKAZNIK, a vie ho nainštalovať z pribaleného 05_object_type.sql. */
public final class TypeInstaller {

    private static final String RESOURCE = "/05_object_type.sql";

    private TypeInstaller() {
    }

    /** True, ak existuje platný typ aj telo typu. */
    public static boolean jeNainstalovany() throws SQLException {
        String sql = "SELECT COUNT(*) FROM user_objects WHERE object_name = 'T_ZAKAZNIK' "
                + "AND object_type IN ('TYPE', 'TYPE BODY') AND status = 'VALID'";
        try (Statement st = Db.get().createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1) == 2;
        }
    }

    public static void nainstaluj() throws SQLException, IOException {
        Connection c = Db.get();
        for (String stmt : rozdelPrikazy(nacitajSkript())) {
            try (Statement st = c.createStatement()) {
                st.execute(stmt);
            }
        }
        if (!jeNainstalovany()) {
            throw new SQLException("Typ T_ZAKAZNIK sa vytvoril s chybami pri kompilácii:\n" + chybyKompilacie());
        }
    }

    private static String chybyKompilacie() throws SQLException {
        StringBuilder sb = new StringBuilder();
        String sql = "SELECT line, text FROM user_errors WHERE name = 'T_ZAKAZNIK' ORDER BY type, sequence";
        try (PreparedStatement ps = Db.get().prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                sb.append("riadok ").append(rs.getInt(1)).append(": ").append(rs.getString(2).trim()).append('\n');
            }
        }
        return sb.length() == 0 ? "(žiadne podrobnosti)" : sb.toString();
    }

    public static String nacitajSkript() throws IOException {
        try (InputStream in = TypeInstaller.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IOException("V aplikácii chýba súbor " + RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Rozdelí SQL*Plus skript na jednotlivé príkazy (oddeľovač je riadok obsahujúci iba "/"),
     * vynechá komentáre a pri príkazoch, ktoré nie sú PL/SQL blok, odstráni záverečnú bodkočiarku.
     */
    static List<String> rozdelPrikazy(String script) {
        List<String> result = new ArrayList<>();
        for (String part : script.split("(?m)^\\s*/\\s*$")) {
            StringBuilder sb = new StringBuilder();
            for (String line : part.split("\\R")) {
                if (!line.trim().startsWith("--")) {
                    sb.append(line).append('\n');
                }
            }
            String stmt = sb.toString().trim();
            if (stmt.isEmpty()) {
                continue;
            }
            boolean plsqlKoniec = Pattern.compile("(?is).*\\bEND\\s*(\\w+\\s*)?;$").matcher(stmt).matches();
            if (stmt.endsWith(";") && !plsqlKoniec) {
                stmt = stmt.substring(0, stmt.length() - 1).trim();
            }
            result.add(stmt);
        }
        return result;
    }
}
