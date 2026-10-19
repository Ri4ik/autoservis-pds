package sk.autoservis;

import java.sql.SQLException;

/** Preklad typických Oracle chýb do zrozumiteľných slovenských správ. */
public final class Chyby {

    private Chyby() {
    }

    public static String sprava(SQLException e) {
        // vlastné chyby z PL/SQL (RAISE_APPLICATION_ERROR -20000 až -20999): zobrazí sa len text správy
        if (e.getErrorCode() >= 20000 && e.getErrorCode() <= 20999) {
            String text = e.getMessage();
            int nl = text.indexOf('\n');
            if (nl > 0) {
                text = text.substring(0, nl);
            }
            return text.replaceFirst("^ORA-(\\d+):\\s*", "Chyba ORA-$1:\n");
        }
        return switch (e.getErrorCode()) {
            case 1 -> "Záznam s rovnakou unikátnou hodnotou (napr. e-mail) už existuje.";
            case 1400 -> "Povinný údaj nie je vyplnený.";
            case 2292 -> "Záznam nemožno zmazať – odkazujú naň iné záznamy (porušenie cudzieho kľúča "
                    + obmedzenie(e) + ").\nNapr. zákazníka s vozidlami alebo zákazku s faktúrou treba najprv odpojiť.";
            case 2291 -> "Odkazovaný záznam neexistuje (porušenie cudzieho kľúča " + obmedzenie(e) + ").";
            case 2290 -> "Údaje porušujú pravidlo " + obmedzenie(e)
                    + ".\nSkontrolujte hodnoty (napr. stav zákazky a dátumy dokončenia / odovzdania, kladné ceny a množstvá).";
            case 1407 -> "Povinný údaj nemožno vymazať (NOT NULL).";
            case 12899 -> "Zadaná hodnota je príliš dlhá pre daný stĺpec.";
            case 942 -> "Tabuľka alebo typ neexistuje. Spustite najprv 01_schema.sql a 02_demo_data.sql.";
            case 1031 -> "Nedostatočné oprávnenia (na vytvorenie typu je potrebné právo CREATE TYPE).";
            case 1017 -> "Nesprávne používateľské meno alebo heslo.";
            default -> "Databázová chyba ORA-" + String.format("%05d", e.getErrorCode()) + ": " + e.getMessage();
        };
    }

    /** Názov obmedzenia zo správy Oracle, napr. "ORA-02290: check constraint (SCHEMA.CK_X) violated" -> CK_X. */
    private static String obmedzenie(SQLException e) {
        var m = java.util.regex.Pattern.compile("\\(\\w+\\.(\\w+)\\)").matcher(String.valueOf(e.getMessage()));
        return m.find() ? m.group(1) : "?";
    }
}
