package sk.autoservis;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Clob;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;

/**
 * Generovanie XML alebo JSON dokumentov z relačných dát. Jedna trieda pre obe záložky – líši sa len formát
 * a zoznam scenárov. Scenár môže mať parameter (zákazka alebo vozidlo); pri XML sa nad výsledkom dá vyhodnotiť XPath.
 * SQL dotazy a pohľady sú v skriptoch 09_xml_report_zakazka.sql a 15_exporty_xml_json.sql.
 */
public class DokumentyPanel extends JPanel {

    /** Druh parametra scenára. */
    enum Parameter { ZIADNY, ZAKAZKA, VOZIDLO }

    /** Scenár: názov, popis, parameter, SQL vracajúci jeden CLOB (všetky „?“ dostanú hodnotu parametra), príklady XPath. */
    record Scenar(String nazov, String popis, Parameter parameter, String sql, String[] xpath) {
        @Override
        public String toString() {
            return nazov;
        }
    }

    // ---------------- XML scenáre ----------------

    static final Scenar[] XML = {
            new Scenar("Servisná zákazka – report 12",
                    "Zákazník, vozidlo, dátum prijatia, kontrolný zoznam (NESTED TABLE), úkony, diely a celková cena (pohľad V_XML_ZAKAZKA).",
                    Parameter.ZAKAZKA, """
                    SELECT XMLSERIALIZE(DOCUMENT
                             XMLELEMENT("zakazky",
                               XMLAGG(EXTRACT(x.dokument, '/zakazka') ORDER BY x.id_zakazka))
                             AS CLOB INDENT SIZE = 2)
                      FROM v_xml_zakazka x
                     WHERE (? IS NULL OR x.id_zakazka = ?)""",
                    new String[]{"/zakazky/zakazka/ukony", "/zakazky/zakazka/kontrolny_zoznam/bod[@stav=\"CHYBA\"]",
                            "/zakazky/zakazka/diely/diel",
                            "/zakazky/zakazka[celkova_cena > 100]/celkova_cena",
                            "/zakazky/zakazka/diely/diel[@katalogove_cislo=\"BRAKE-P-01\"]",
                            "/zakazky/zakazka[@stav=\"ODOVZDANA\"]/vozidlo/spz"}),
            new Scenar("Zákazníci a ich vozidlá",
                    "Každý zákazník s kontaktmi a zoznamom vozidiel (XMLELEMENT, XMLFOREST, XMLAGG).",
                    Parameter.ZIADNY, """
                    SELECT XMLSERIALIZE(DOCUMENT
                             XMLELEMENT("zakaznici",
                               XMLAGG(XMLELEMENT("zakaznik", XMLATTRIBUTES(z.id_zakaznik AS "id"),
                                        XMLFOREST(z.meno AS "meno", z.priezvisko AS "priezvisko",
                                                  z.telefon AS "telefon", z.email AS "email"),
                                        (SELECT XMLELEMENT("vozidla", XMLAGG(XMLELEMENT("vozidlo",
                                                  XMLFOREST(v.spz AS "spz", v.znacka AS "znacka", v.model AS "model",
                                                            v.rok_vyroby AS "rok_vyroby"))
                                                  ORDER BY v.id_vozidlo))
                                           FROM vozidlo v WHERE v.id_zakaznik = z.id_zakaznik))
                                      ORDER BY z.id_zakaznik))
                             AS CLOB INDENT SIZE = 2)
                      FROM zakaznik z""",
                    new String[]{"/zakaznici/zakaznik/vozidla/vozidlo/spz", "/zakaznici/zakaznik[@id=\"1\"]",
                            "/zakaznici/zakaznik[vozidla/vozidlo/znacka=\"Skoda\"]/priezvisko"}),
            new Scenar("Servisná história vozidla",
                    "História tachometra (VARRAY) a všetky zákazky vozidla zo SQL kolekcie funkcie HISTORIA_VOZIDLA (report 10) – pohľad V_XML_HISTORIA_VOZIDLA.",
                    Parameter.VOZIDLO, """
                    SELECT XMLSERIALIZE(DOCUMENT dokument AS CLOB INDENT SIZE = 2)
                      FROM v_xml_historia_vozidla
                     WHERE id_vozidlo = ?""",
                    new String[]{"/historia_vozidla/historia_km/zaznam", "/historia_vozidla/zakazky/zakazka",
                            "/historia_vozidla/spolu",
                            "/historia_vozidla/zakazky/zakazka[suma > 100]"}),
            new Scenar("Faktúry, platby a zostatok",
                    "Faktúry s platbami a neuhradeným zostatkom (fakturovaná suma vs. skutočne prijaté platby).",
                    Parameter.ZIADNY, """
                    SELECT XMLSERIALIZE(DOCUMENT
                             XMLELEMENT("faktury",
                               XMLAGG(XMLELEMENT("faktura",
                                        XMLATTRIBUTES(f.cislo_faktury AS "cislo", f.id_zakazka AS "zakazka"),
                                        XMLFOREST(TO_CHAR(f.datum_vystavenia, 'YYYY-MM-DD') AS "vystavena",
                                                  TO_CHAR(f.datum_splatnosti, 'YYYY-MM-DD') AS "splatnost",
                                                  f.celkova_suma AS "suma",
                                                  NVL(pl.zaplatene, 0) AS "zaplatene",
                                                  f.celkova_suma - NVL(pl.zaplatene, 0) AS "zostatok"),
                                        (SELECT XMLELEMENT("platby", XMLAGG(XMLELEMENT("platba", XMLATTRIBUTES(p.sposob AS "sposob"),
                                                  XMLFOREST(TO_CHAR(p.datum_platby, 'YYYY-MM-DD') AS "datum", p.suma AS "suma"))
                                                  ORDER BY p.datum_platby))
                                           FROM platba p WHERE p.id_faktura = f.id_faktura))
                                      ORDER BY f.datum_vystavenia, f.id_faktura))
                             AS CLOB INDENT SIZE = 2)
                      FROM faktura f
                      LEFT JOIN (SELECT id_faktura, SUM(suma) zaplatene FROM platba GROUP BY id_faktura) pl
                             ON pl.id_faktura = f.id_faktura""",
                    new String[]{"/faktury/faktura[zostatok > 0]", "/faktury/faktura/platby/platba[@sposob=\"KARTA\"]",
                            "/faktury/faktura/@cislo"})
    };

    // ---------------- JSON scenáre ----------------

    static final Scenar[] JSON = {
            new Scenar("Servisná zákazka – report 12",
                    "Rovnaký obsah ako XML report zákazky vrátane kontrolného zoznamu, ako JSON (pohľad V_JSON_ZAKAZKA, JSON_OBJECT / JSON_ARRAYAGG).",
                    Parameter.ZAKAZKA, """
                    SELECT JSON_SERIALIZE(
                             JSON_OBJECT('zakazky' VALUE
                               JSON_ARRAYAGG(x.dokument FORMAT JSON ORDER BY x.id_zakazka RETURNING CLOB)
                               RETURNING CLOB)
                             RETURNING CLOB PRETTY)
                      FROM v_json_zakazka x
                     WHERE (? IS NULL OR x.id_zakazka = ?)""", null),
            new Scenar("Zákazníci a ich vozidlá",
                    "Zákazníci s vnoreným poľom vozidiel (pôvodný JSON výstup aplikácie).",
                    Parameter.ZIADNY, """
                    SELECT JSON_SERIALIZE(
                             JSON_OBJECT('zakaznici' VALUE
                               JSON_ARRAYAGG(JSON_OBJECT('id' VALUE z.id_zakaznik, 'meno' VALUE z.meno,
                                                         'priezvisko' VALUE z.priezvisko, 'telefon' VALUE z.telefon,
                                                         'email' VALUE z.email,
                                                         'vozidla' VALUE NVL((SELECT JSON_ARRAYAGG(
                                                                                JSON_OBJECT('spz' VALUE v.spz, 'znacka' VALUE v.znacka,
                                                                                            'model' VALUE v.model,
                                                                                            'rok_vyroby' VALUE v.rok_vyroby)
                                                                                ORDER BY v.id_vozidlo RETURNING CLOB)
                                                                              FROM vozidlo v
                                                                             WHERE v.id_zakaznik = z.id_zakaznik), '[]') FORMAT JSON
                                                         RETURNING CLOB)
                                             ORDER BY z.id_zakaznik RETURNING CLOB)
                               RETURNING CLOB)
                             RETURNING CLOB PRETTY)
                      FROM zakaznik z""", null),
            new Scenar("Servisná história vozidla",
                    "História tachometra (VARRAY) a zákazky vozidla zo SQL kolekcie funkcie HISTORIA_VOZIDLA (report 10) – pohľad V_JSON_HISTORIA_VOZIDLA.",
                    Parameter.VOZIDLO, """
                    SELECT JSON_SERIALIZE(dokument RETURNING CLOB PRETTY)
                      FROM v_json_historia_vozidla
                     WHERE id_vozidlo = ?""", null),
            new Scenar("Faktúry, platby a zostatok",
                    "Faktúry s poľom platieb a neuhradeným zostatkom.",
                    Parameter.ZIADNY, """
                    SELECT JSON_SERIALIZE(
                             JSON_OBJECT('faktury' VALUE
                               JSON_ARRAYAGG(JSON_OBJECT('cislo' VALUE f.cislo_faktury, 'zakazka' VALUE f.id_zakazka,
                                                         'vystavena' VALUE TO_CHAR(f.datum_vystavenia, 'YYYY-MM-DD'),
                                                         'splatnost' VALUE TO_CHAR(f.datum_splatnosti, 'YYYY-MM-DD'),
                                                         'suma' VALUE f.celkova_suma,
                                                         'zaplatene' VALUE NVL(pl.zaplatene, 0),
                                                         'zostatok' VALUE f.celkova_suma - NVL(pl.zaplatene, 0),
                                                         'platby' VALUE NVL((SELECT JSON_ARRAYAGG(
                                                                               JSON_OBJECT('datum' VALUE TO_CHAR(p.datum_platby, 'YYYY-MM-DD'),
                                                                                           'suma' VALUE p.suma, 'sposob' VALUE p.sposob)
                                                                               ORDER BY p.datum_platby RETURNING CLOB)
                                                                             FROM platba p
                                                                            WHERE p.id_faktura = f.id_faktura), '[]') FORMAT JSON
                                                         RETURNING CLOB)
                                             ORDER BY f.datum_vystavenia, f.id_faktura RETURNING CLOB)
                               RETURNING CLOB)
                             RETURNING CLOB PRETTY)
                      FROM faktura f
                      LEFT JOIN (SELECT id_faktura, SUM(suma) zaplatene FROM platba GROUP BY id_faktura) pl
                             ON pl.id_faktura = f.id_faktura""", null)
    };

    private final boolean jeXml;
    private final String pripona;
    private final JComboBox<Scenar> cbScenar;
    private final JLabel lbPopis = new JLabel(" ");
    private final JLabel lbParameter = new JLabel();
    private final JComboBox<PolozkyPanel.Polozka> cbParameter = new JComboBox<>();
    private final JComboBox<String> cbXpath = new JComboBox<>();
    private final JTextArea output = new JTextArea();
    private final JTextArea sqlArea = new JTextArea(8, 60);
    private final JLabel status = new JLabel(" ");
    private String dokument = "";
    private boolean nacitavam;

    public DokumentyPanel(boolean jeXml) {
        this.jeXml = jeXml;
        this.pripona = jeXml ? "xml" : "json";
        this.cbScenar = new JComboBox<>(jeXml ? XML : JSON);
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JButton btnGenerovat = new JButton("Generovať");
        JButton btnUlozit = new JButton("Uložiť do súboru…");
        btnGenerovat.addActionListener(e -> generuj());
        btnUlozit.addActionListener(e -> uloz());
        cbScenar.addActionListener(e -> {
            if (!nacitavam) {
                zmenaScenara();
            }
        });

        JPanel riadok1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        riadok1.add(new JLabel("Scenár:"));
        riadok1.add(cbScenar);
        riadok1.add(lbParameter);
        riadok1.add(cbParameter);
        riadok1.add(btnGenerovat);
        riadok1.add(btnUlozit);

        JPanel top = new JPanel(new GridLayout(0, 1, 0, 4));
        top.add(riadok1);
        top.add(lbPopis);
        if (jeXml) {
            JButton btnXpath = new JButton("Vyhodnotiť XPath nad dokumentom");
            btnXpath.addActionListener(e -> vyhodnotXpath());
            cbXpath.setEditable(true);
            cbXpath.setPrototypeDisplayValue("/zakazky/zakazka/diely/diel[@katalogove_cislo=\"BRAKE-P-01\"]");
            JPanel riadok2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            riadok2.add(new JLabel("XPath (EXTRACT):"));
            riadok2.add(cbXpath);
            riadok2.add(btnXpath);
            top.add(riadok2);
        }

        output.setEditable(false);
        output.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        sqlArea.setEditable(false);
        sqlArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane sqlScroll = new JScrollPane(sqlArea);
        sqlScroll.setBorder(BorderFactory.createTitledBorder("Použitý SQL dopyt"));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(output), sqlScroll);
        split.setResizeWeight(0.72);

        add(top, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);
        zmenaScenara();
    }

    private Scenar scenar() {
        return (Scenar) cbScenar.getSelectedItem();
    }

    /** Volá sa pri otvorení záložky – obnoví zoznam parametrov (zákazky / vozidlá sa mohli zmeniť). */
    public void nacitaj() {
        zmenaScenara();
    }

    private void zmenaScenara() {
        Scenar s = scenar();
        lbPopis.setText("<html>" + s.popis() + "</html>");
        sqlArea.setText(s.sql());
        sqlArea.setCaretPosition(0);
        lbParameter.setVisible(s.parameter() != Parameter.ZIADNY);
        cbParameter.setVisible(s.parameter() != Parameter.ZIADNY);
        cbXpath.removeAllItems();
        if (s.xpath() != null) {
            for (String x : s.xpath()) {
                cbXpath.addItem(x);
            }
        }
        if (s.parameter() == Parameter.ZIADNY || !Db.jePripojeny()) {
            return;
        }
        String sql;
        if (s.parameter() == Parameter.ZAKAZKA) {
            lbParameter.setText("Zákazka:");
            sql = """
                    SELECT z.id_zakazka, z.id_zakazka || ' – ' || za.priezvisko || ', ' || v.spz || ' (' || z.stav || ')'
                      FROM servisna_zakazka z
                      JOIN vozidlo v ON v.id_vozidlo = z.id_vozidlo
                      JOIN zakaznik za ON za.id_zakaznik = v.id_zakaznik
                     ORDER BY z.id_zakazka""";
        } else {
            lbParameter.setText("Vozidlo:");
            sql = """
                    SELECT v.id_vozidlo, v.spz || ' – ' || v.znacka || ' ' || v.model || ' (' || za.priezvisko || ')'
                      FROM vozidlo v JOIN zakaznik za ON za.id_zakaznik = v.id_zakaznik
                     ORDER BY v.id_vozidlo""";
        }
        nacitavam = true;
        try (Statement st = Db.get().createStatement(); ResultSet rs = st.executeQuery(sql)) {
            Object vybrany = cbParameter.getSelectedItem();
            cbParameter.removeAllItems();
            if (s.parameter() == Parameter.ZAKAZKA) {
                cbParameter.addItem(new PolozkyPanel.Polozka(null, "všetky zákazky"));
            }
            while (rs.next()) {
                cbParameter.addItem(new PolozkyPanel.Polozka(rs.getLong(1), rs.getString(2)));
            }
            if (vybrany != null) {
                cbParameter.setSelectedItem(vybrany);
            }
        } catch (SQLException ex) {
            chyba(ex);
        } finally {
            nacitavam = false;
        }
    }

    private void generuj() {
        Scenar s = scenar();
        PolozkyPanel.Polozka p = (PolozkyPanel.Polozka) cbParameter.getSelectedItem();
        Long id = s.parameter() == Parameter.ZIADNY || p == null ? null : p.id();
        try (PreparedStatement ps = Db.get().prepareStatement(s.sql())) {
            int pocetParametrov = (int) s.sql().chars().filter(c -> c == '?').count();
            for (int i = 1; i <= pocetParametrov; i++) {
                if (id == null) {
                    ps.setNull(i, Types.NUMERIC);
                } else {
                    ps.setLong(i, id);
                }
            }
            try (ResultSet rs = ps.executeQuery()) {
                String text = rs.next() ? citaj(rs.getCharacterStream(1)) : null;
                dokument = text == null ? "" : text;
                output.setText(text == null ? "(žiadne údaje)" : text);
                output.setCaretPosition(0);
                status.setText(s.nazov() + " – dokument vygenerovaný, dĺžka " + dokument.length() + " znakov.");
            }
            sqlArea.setText(s.sql());
            sqlArea.setCaretPosition(0);
        } catch (SQLException ex) {
            chyba(ex);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Chyba čítania", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** XPath sa vyhodnotí v databáze funkciou EXTRACT nad práve vygenerovaným dokumentom. */
    private void vyhodnotXpath() {
        if (dokument.isBlank()) {
            JOptionPane.showMessageDialog(this, "Najprv vygeneruj dokument.", "XPath", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        Object vybrany = cbXpath.getEditor().getItem();
        String xpath = vybrany == null ? "" : vybrany.toString().trim();
        if (xpath.isEmpty()) {
            return;
        }
        String sql = "SELECT XMLSERIALIZE(CONTENT EXTRACT(XMLTYPE(?), ?) AS CLOB INDENT SIZE = 2) FROM dual";
        sqlArea.setText("SELECT XMLSERIALIZE(CONTENT EXTRACT(XMLTYPE(:dokument), '" + xpath + "') AS CLOB INDENT SIZE = 2)\n  FROM dual");
        try (PreparedStatement ps = Db.get().prepareStatement(sql)) {
            Clob clob = Db.get().createClob();
            clob.setString(1, dokument);
            ps.setClob(1, clob);
            ps.setString(2, xpath);
            try (ResultSet rs = ps.executeQuery()) {
                String text = rs.next() ? citaj(rs.getCharacterStream(1)) : null;
                output.setText(text == null || text.isBlank() ? "(XPath nenašiel žiadny uzol)" : text);
                output.setCaretPosition(0);
                status.setText("XPath " + xpath + " vyhodnotený (tlačidlo Generovať zobrazí znova celý dokument).");
            } finally {
                clob.free();
            }
        } catch (SQLException ex) {
            chyba(ex);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Chyba čítania", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static String citaj(Reader r) throws IOException {
        if (r == null) {
            return null;
        }
        try (r) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[8192];
            int n;
            while ((n = r.read(buf)) > 0) {
                sb.append(buf, 0, n);
            }
            return sb.toString();
        }
    }

    private void uloz() {
        if (output.getText().isBlank()) {
            JOptionPane.showMessageDialog(this, "Najprv vygeneruj dokument.", "Ukladanie", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File("export." + pripona));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        try {
            Files.writeString(chooser.getSelectedFile().toPath(), output.getText(), StandardCharsets.UTF_8);
            status.setText("Uložené: " + chooser.getSelectedFile().getAbsolutePath());
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Súbor sa nepodarilo uložiť: " + ex.getMessage(), "Chyba", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void chyba(SQLException ex) {
        String sprava = ex.getErrorCode() == 942 || ex.getErrorCode() == 904
                ? "Chýba pohľad alebo funkcia pre tento scenár.\nSpustite v SQL Developeri skripty "
                  + (jeXml ? "09_xml_report_zakazka.sql, " : "") + "11_report10_historia_vozidla.sql a 15_exporty_xml_json.sql (F5)."
                : Chyby.sprava(ex);
        JOptionPane.showMessageDialog(this, sprava, jeXml ? "XML" : "JSON", JOptionPane.ERROR_MESSAGE);
    }
}
