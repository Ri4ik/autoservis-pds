package sk.autoservis;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.sql.Blob;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Záložka s CRUD operáciami nad ľubovoľnou z 11 tabuliek databázy autoservisu: READ (tabuľka),
 * CREATE / UPDATE cez všeobecný formulár {@link CrudDialog} podľa metadát a DELETE podľa primárneho kľúča.
 */
public class TabulkyPanel extends JPanel {

    /** Pevný zoznam tabuliek – názov sa nikdy nezostavuje z používateľského vstupu. */
    private static final String[] TABULKY = {
            "ZAKAZNIK", "VOZIDLO", "ZAMESTNANEC", "SERVISNA_ZAKAZKA", "SERVISNY_UKON", "VYKONANY_UKON",
            "NAHRADNY_DIEL", "POUZITY_DIEL", "FAKTURA", "PLATBA", "PRILOHA"
    };
    private static final int MAX_RIADKOV = 1000;
    private static final DateTimeFormatter DATUM = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter DATUM_CAS = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final JComboBox<String> vyber = new JComboBox<>(TABULKY);
    private final DefaultTableModel model = new DefaultTableModel() {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable table = new JTable(model);
    private final JLabel status = new JLabel(" ");
    private final JButton btnPriloha = new JButton("Uložiť prílohu do súboru…");
    /** Pôvodné (neformátované) hodnoty riadkov – použijú sa pri úprave a mazaní. */
    private final List<Object[]> surove = new ArrayList<>();
    private String[] nazvyStlpcov = new String[0];

    public TabulkyPanel() {
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JButton btnObnovit = new JButton("Obnoviť");
        btnObnovit.addActionListener(e -> nacitaj());
        vyber.addActionListener(e -> nacitaj());

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        top.add(new JLabel("Tabuľka:"));
        top.add(vyber);
        top.add(btnObnovit);

        JButton btnPridat = new JButton("Pridať");
        JButton btnUpravit = new JButton("Upraviť");
        JButton btnZmazat = new JButton("Zmazať");
        btnPridat.addActionListener(e -> pridat());
        btnUpravit.addActionListener(e -> upravit());
        btnZmazat.addActionListener(e -> zmazat());
        btnPriloha.addActionListener(e -> ulozPrilohu());
        top.add(Box.createHorizontalStrut(16));
        top.add(btnPridat);
        top.add(btnUpravit);
        top.add(btnZmazat);
        top.add(btnPriloha);

        table.setAutoCreateRowSorter(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2 && table.getSelectedRow() >= 0) {
                    upravit();
                }
            }
        });
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);

        add(top, BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);
    }

    /** Zavolá sa po prihlásení (a pri každom prepnutí tabuľky). */
    public void nacitaj() {
        String tabulka = (String) vyber.getSelectedItem();
        if (tabulka == null) {
            return;
        }
        btnPriloha.setVisible("PRILOHA".equals(tabulka));
        String sql = "SELECT * FROM " + tabulka + " ORDER BY 1";
        try (Statement st = Db.get().createStatement()) {
            st.setMaxRows(MAX_RIADKOV);
            try (ResultSet rs = st.executeQuery(sql)) {
                ResultSetMetaData md = rs.getMetaData();
                int pocetStlpcov = md.getColumnCount();
                String[] hlavicka = new String[pocetStlpcov];
                for (int i = 0; i < pocetStlpcov; i++) {
                    hlavicka[i] = md.getColumnLabel(i + 1).toLowerCase();
                }
                List<Object[]> riadky = new ArrayList<>();
                surove.clear();
                nazvyStlpcov = new String[pocetStlpcov];
                for (int i = 0; i < pocetStlpcov; i++) {
                    nazvyStlpcov[i] = md.getColumnName(i + 1);
                }
                while (rs.next()) {
                    Object[] riadok = new Object[pocetStlpcov];
                    Object[] surovy = new Object[pocetStlpcov];
                    for (int i = 0; i < pocetStlpcov; i++) {
                        int typ = md.getColumnType(i + 1);
                        surovy[i] = (typ == java.sql.Types.DATE || typ == java.sql.Types.TIMESTAMP)
                                ? rs.getTimestamp(i + 1) : rs.getObject(i + 1);
                        riadok[i] = naText(surovy[i]);
                    }
                    riadky.add(riadok);
                    surove.add(surovy);
                }
                model.setDataVector(riadky.toArray(new Object[0][]), hlavicka);
                upravSirkyStlpcov();
                status.setText(tabulka + ": " + riadky.size() + " riadkov"
                        + (riadky.size() >= MAX_RIADKOV ? " (zobrazených max. " + MAX_RIADKOV + ")" : ""));
            }
        } catch (SQLException ex) {
            JOptionPane.showMessageDialog(this, Chyby.sprava(ex), "Chyba databázy", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ---------- CRUD ----------

    private String tabulka() {
        return (String) vyber.getSelectedItem();
    }

    /** Pôvodné hodnoty vybraného riadku (stĺpec -> hodnota), alebo null, ak nie je nič vybrané. */
    private java.util.Map<String, Object> vybranyRiadok() {
        int r = table.getSelectedRow();
        if (r < 0) {
            JOptionPane.showMessageDialog(this, "Najprv vyber riadok v tabuľke.", "Upozornenie", JOptionPane.WARNING_MESSAGE);
            return null;
        }
        Object[] surovy = surove.get(table.convertRowIndexToModel(r));
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < nazvyStlpcov.length; i++) {
            m.put(nazvyStlpcov[i], surovy[i]);
        }
        return m;
    }

    private void pridat() {
        try {
            if (CrudDialog.zobraz(this, TabulkaMeta.nacitaj(tabulka()), null)) {
                nacitaj();
                status.setText(status.getText() + "   |   záznam bol pridaný");
            }
        } catch (SQLException ex) {
            JOptionPane.showMessageDialog(this, Chyby.sprava(ex), "Chyba databázy", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void upravit() {
        java.util.Map<String, Object> riadok = vybranyRiadok();
        if (riadok == null) {
            return;
        }
        try {
            if (CrudDialog.zobraz(this, TabulkaMeta.nacitaj(tabulka()), riadok)) {
                nacitaj();
                status.setText(status.getText() + "   |   zmeny boli uložené");
            }
        } catch (SQLException ex) {
            JOptionPane.showMessageDialog(this, Chyby.sprava(ex), "Chyba databázy", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void zmazat() {
        java.util.Map<String, Object> riadok = vybranyRiadok();
        if (riadok == null) {
            return;
        }
        try {
            List<TabulkaMeta.Stlpec> pk = TabulkaMeta.nacitaj(tabulka()).pkStlpce();
            String popis = pk.stream().map(s -> s.nazov.toLowerCase() + " = " + riadok.get(s.nazov)).toList().toString();
            if (JOptionPane.showConfirmDialog(this, "Zmazať záznam " + popis + " z tabuľky " + tabulka() + "?",
                    "Potvrdenie", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
                return;
            }
            String sql = "DELETE FROM " + tabulka() + " WHERE "
                    + String.join(" AND ", pk.stream().map(s -> s.nazov + " = ?").toList());
            try (java.sql.PreparedStatement ps = Db.get().prepareStatement(sql)) {
                for (int i = 0; i < pk.size(); i++) {
                    ps.setObject(i + 1, riadok.get(pk.get(i).nazov));
                }
                ps.executeUpdate();
            }
            nacitaj();
            status.setText(status.getText() + "   |   záznam bol zmazaný");
        } catch (SQLException ex) {
            JOptionPane.showMessageDialog(this, Chyby.sprava(ex), "Chyba databázy", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** READ pre BLOB: obsah prílohy sa uloží do súboru. */
    private void ulozPrilohu() {
        java.util.Map<String, Object> riadok = vybranyRiadok();
        if (riadok == null) {
            return;
        }
        JFileChooser ch = new JFileChooser();
        ch.setSelectedFile(new java.io.File(String.valueOf(riadok.get("NAZOV_SUBORU"))));
        if (ch.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        String sql = "SELECT obsah FROM priloha WHERE id_priloha = ?";
        try (java.sql.PreparedStatement ps = Db.get().prepareStatement(sql)) {
            ps.setObject(1, riadok.get("ID_PRILOHA"));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    java.nio.file.Files.write(ch.getSelectedFile().toPath(), rs.getBytes(1));
                    status.setText("Príloha uložená: " + ch.getSelectedFile().getAbsolutePath());
                }
            }
        } catch (SQLException ex) {
            JOptionPane.showMessageDialog(this, Chyby.sprava(ex), "Chyba databázy", JOptionPane.ERROR_MESSAGE);
        } catch (java.io.IOException ex) {
            JOptionPane.showMessageDialog(this, "Súbor sa nepodarilo uložiť: " + ex.getMessage(), "Chyba", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Dátumy sa zobrazia čitateľne, BLOB (príloha) ako veľkosť v bajtoch. */
    private static Object naText(Object v) throws SQLException {
        if (v == null) {
            return "";
        }
        if (v instanceof Blob b) {
            return "[BLOB, " + b.length() + " B]";
        }
        if (v instanceof java.sql.Array a) {
            // VARRAY / vnorená tabuľka – zobrazí sa len počet prvkov
            Object[] prvky = (Object[]) a.getArray();
            return "[kolekcia, " + prvky.length + " prvkov]";
        }
        if (v instanceof java.sql.Struct st) {
            return st.getSQLTypeName() + java.util.Arrays.toString(st.getAttributes());
        }
        if (v instanceof java.sql.Timestamp t) {
            var ldt = t.toLocalDateTime();
            return (ldt.toLocalTime().toSecondOfDay() == 0 ? DATUM : DATUM_CAS).format(ldt);
        }
        if (v instanceof java.sql.Date d) {
            return DATUM.format(d.toLocalDate());
        }
        if (v instanceof java.time.LocalDateTime ldt) {
            return (ldt.toLocalTime().toSecondOfDay() == 0 ? DATUM : DATUM_CAS).format(ldt);
        }
        return v;
    }

    private void upravSirkyStlpcov() {
        for (int c = 0; c < table.getColumnCount(); c++) {
            int sirka = table.getColumnName(c).length() * 9 + 24;
            for (int r = 0; r < Math.min(table.getRowCount(), 100); r++) {
                Object hodnota = table.getValueAt(r, c);
                if (hodnota != null) {
                    sirka = Math.max(sirka, Math.min(hodnota.toString().length() * 8 + 16, 360));
                }
            }
            table.getColumnModel().getColumn(c).setPreferredWidth(sirka);
        }
    }
}
