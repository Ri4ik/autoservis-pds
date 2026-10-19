package sk.autoservis;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.sql.CallableStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Servisné zákazky cez PL/SQL procedúry zo skriptu 12_plsql_zakazky.sql:
 * VYTVOR_SERVISNU_ZAKAZKU, ZMEN_STAV_ZAKAZKY (kontrola prechodov stavov, výnimky)
 * a VYPIS_OTVORENE_ZAKAZKY (kurzor, %ROWTYPE, výstup DBMS_OUTPUT).
 */
public class ZakazkyPanel extends JPanel {

    private static final String SQL_ZOZNAM = """
            SELECT z.id_zakazka, v.spz || ' – ' || v.znacka || ' ' || v.model AS vozidlo,
                   za.meno || ' ' || za.priezvisko AS zakaznik,
                   z.datum_prijatia, z.datum_dokoncenia, z.datum_odovzdania, z.stav, z.stav_km
              FROM servisna_zakazka z
              JOIN vozidlo v ON v.id_vozidlo = z.id_vozidlo
              JOIN zakaznik za ON za.id_zakaznik = v.id_zakaznik
             ORDER BY z.id_zakazka DESC""";

    private static final String[] STAVY = {"V_OPRAVE", "DOKONCENA", "ODOVZDANA", "ZRUSENA"};
    private static final DateTimeFormatter DATUM = DateTimeFormatter.ofPattern("d.M.yyyy");
    private static final DateTimeFormatter DATUM_VYPIS = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final DefaultTableModel model = new DefaultTableModel(
            new String[]{"ID", "Vozidlo", "Zákazník", "Prijatá", "Dokončená", "Odovzdaná", "Stav", "Km"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable table = new JTable(model);

    private final JComboBox<PolozkyPanel.Polozka> cbVozidlo = new JComboBox<>();
    private final JComboBox<PolozkyPanel.Polozka> cbMechanik = new JComboBox<>();
    private final JTextField tfDatumPrijatia = new JTextField(9);
    private final JTextField tfPopis = new JTextField(22);
    private final JTextField tfKm = new JTextField(7);

    private final JLabel lbVybrana = new JLabel("(vyber zákazku v tabuľke)");
    private final JComboBox<String> cbStav = new JComboBox<>(STAVY);
    private final JTextField tfDatumZmeny = new JTextField(9);

    private final JTextArea vystup = new JTextArea(9, 60);
    private final JLabel status = new JLabel(" ");

    public ZakazkyPanel() {
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JLabel popis = new JLabel("<html>Zákazky sa vytvárajú a menia výhradne cez PL/SQL procedúry "
                + "<i>VYTVOR_SERVISNU_ZAKAZKU</i> a <i>ZMEN_STAV_ZAKAZKY</i>. Povolené prechody: "
                + "PRIJATA → V_OPRAVE → DOKONCENA → ODOVZDANA, PRIJATA / V_OPRAVE → ZRUSENA. "
                + "Chyby (výnimky z procedúr) sa zobrazia v dialógu aj v spodnom výpise.</html>");

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int r = table.getSelectedRow();
                lbVybrana.setText(r < 0 ? "(vyber zákazku v tabuľke)"
                        : "zákazka " + model.getValueAt(r, 0) + " (" + model.getValueAt(r, 6) + ")");
            }
        });
        table.getColumnModel().getColumn(1).setPreferredWidth(200);

        vystup.setEditable(false);
        vystup.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane vystupScroll = new JScrollPane(vystup);
        vystupScroll.setBorder(BorderFactory.createTitledBorder("Výstup procedúr (DBMS_OUTPUT / chyby)"));

        JPanel formy = new JPanel(new GridLayout(0, 1, 0, 2));
        formy.add(formularNova());
        formy.add(formularZmena());

        JPanel stred = new JPanel(new BorderLayout(4, 4));
        stred.add(new JScrollPane(table), BorderLayout.CENTER);
        stred.add(formy, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, stred, vystupScroll);
        split.setResizeWeight(0.7);

        add(popis, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);
    }

    private JComponent formularNova() {
        JButton btn = new JButton("Vytvoriť zákazku");
        btn.addActionListener(e -> vytvor());
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2));
        p.setBorder(BorderFactory.createTitledBorder("Nová zákazka – VYTVOR_SERVISNU_ZAKAZKU"));
        p.add(new JLabel("Vozidlo:"));
        p.add(cbVozidlo);
        p.add(new JLabel("Mechanik:"));
        p.add(cbMechanik);
        p.add(new JLabel("Prijatá:"));
        p.add(tfDatumPrijatia);
        p.add(new JLabel("Popis:"));
        p.add(tfPopis);
        p.add(new JLabel("Km:"));
        p.add(tfKm);
        p.add(btn);
        return p;
    }

    private JComponent formularZmena() {
        JButton btnZmen = new JButton("Zmeniť stav");
        btnZmen.addActionListener(e -> zmenStav());
        JButton btnOtvorene = new JButton("Výpis otvorených zákaziek (kurzor)");
        btnOtvorene.addActionListener(e -> vypisOtvorene());
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2));
        p.setBorder(BorderFactory.createTitledBorder("Zmena stavu – ZMEN_STAV_ZAKAZKY  |  VYPIS_OTVORENE_ZAKAZKY"));
        p.add(lbVybrana);
        p.add(new JLabel("Nový stav:"));
        p.add(cbStav);
        p.add(new JLabel("Dátum:"));
        p.add(tfDatumZmeny);
        p.add(btnZmen);
        p.add(Box.createHorizontalStrut(20));
        p.add(btnOtvorene);
        return p;
    }

    /** Volá sa pri otvorení záložky. */
    public void nacitaj() {
        try {
            if (!jeNainstalovane()) {
                status.setText("V schéme chýbajú procedúry zo skriptu 12_plsql_zakazky.sql.");
                JOptionPane.showMessageDialog(this, "PL/SQL procedúry ešte nie sú v tejto schéme vytvorené.\n"
                        + "Spustite v SQL Developeri skript 12_plsql_zakazky.sql (F5).",
                        "Zákazky (PL/SQL)", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            String dnes = LocalDate.now().format(DATUM);
            if (tfDatumPrijatia.getText().isBlank()) {
                tfDatumPrijatia.setText(dnes);
            }
            if (tfDatumZmeny.getText().isBlank()) {
                tfDatumZmeny.setText(dnes);
            }
            naplnCiselnik(cbVozidlo, """
                    SELECT v.id_vozidlo, v.spz || ' – ' || v.znacka || ' ' || v.model
                      FROM vozidlo v ORDER BY v.id_vozidlo""", false);
            naplnCiselnik(cbMechanik, """
                    SELECT id_zamestnanec, meno || ' ' || priezvisko || ' (' || pozicia || ')'
                      FROM zamestnanec WHERE aktivny = 'A' ORDER BY priezvisko""", true);
            nacitajZakazky();
        } catch (SQLException ex) {
            chyba(ex);
        }
    }

    private void nacitajZakazky() throws SQLException {
        model.setRowCount(0);
        try (Statement st = Db.get().createStatement(); ResultSet rs = st.executeQuery(SQL_ZOZNAM)) {
            while (rs.next()) {
                model.addRow(new Object[]{
                        rs.getLong("id_zakazka"), rs.getString("vozidlo"), rs.getString("zakaznik"),
                        datum(rs.getDate("datum_prijatia")), datum(rs.getDate("datum_dokoncenia")),
                        datum(rs.getDate("datum_odovzdania")), rs.getString("stav"), rs.getObject("stav_km")});
            }
        }
        status.setText("Zákaziek: " + model.getRowCount());
    }

    private void vytvor() {
        PolozkyPanel.Polozka vozidlo = (PolozkyPanel.Polozka) cbVozidlo.getSelectedItem();
        PolozkyPanel.Polozka mechanik = (PolozkyPanel.Polozka) cbMechanik.getSelectedItem();
        if (vozidlo == null) {
            return;
        }
        try {
            LocalDate prijatie = parsujDatum(tfDatumPrijatia.getText());
            Long km = tfKm.getText().isBlank() ? null : Long.parseLong(tfKm.getText().trim());
            try (CallableStatement cs = Db.get().prepareCall("BEGIN vytvor_servisnu_zakazku(?, ?, ?, ?, ?, ?); END;")) {
                cs.setLong(1, vozidlo.id());
                cs.setDate(2, java.sql.Date.valueOf(prijatie));
                cs.setString(3, tfPopis.getText());
                if (mechanik == null || mechanik.id() == null) {
                    cs.setNull(4, Types.NUMERIC);
                } else {
                    cs.setLong(4, mechanik.id());
                }
                if (km == null) {
                    cs.setNull(5, Types.NUMERIC);
                } else {
                    cs.setLong(5, km);
                }
                cs.registerOutParameter(6, Types.NUMERIC);
                cs.execute();
                long id = cs.getLong(6);
                vystup.setText("EXEC vytvor_servisnu_zakazku(" + vozidlo.id() + ", DATE '" + prijatie + "', '"
                        + tfPopis.getText() + "', " + (mechanik == null ? null : mechanik.id()) + ", " + km
                        + ", :id)\n→ vytvorená zákazka " + id + " v stave PRIJATA");
                tfPopis.setText("");
                tfKm.setText("");
                nacitajZakazky();
                vyberZakazku(id);
            }
        } catch (NumberFormatException ex) {
            upozorni("Stav tachometra musí byť celé číslo.");
        } catch (DateTimeParseException ex) {
            upozorni("Dátum zadaj v tvare deň.mesiac.rok, napr. " + LocalDate.now().format(DATUM));
        } catch (SQLException ex) {
            vystup.setText("EXEC vytvor_servisnu_zakazku(...)\n→ " + Chyby.sprava(ex));
            chyba(ex);
        }
    }

    private void zmenStav() {
        int r = table.getSelectedRow();
        if (r < 0) {
            upozorni("Najprv vyber zákazku v tabuľke.");
            return;
        }
        long id = (Long) model.getValueAt(r, 0);
        String stav = (String) cbStav.getSelectedItem();
        try {
            LocalDate datum = parsujDatum(tfDatumZmeny.getText());
            try (CallableStatement cs = Db.get().prepareCall("BEGIN zmen_stav_zakazky(?, ?, ?); END;")) {
                cs.setLong(1, id);
                cs.setString(2, stav);
                cs.setDate(3, java.sql.Date.valueOf(datum));
                cs.execute();
            }
            vystup.setText("EXEC zmen_stav_zakazky(" + id + ", '" + stav + "', DATE '" + datum + "')\n→ stav zmenený na " + stav);
            nacitajZakazky();
            vyberZakazku(id);
        } catch (DateTimeParseException ex) {
            upozorni("Dátum zadaj v tvare deň.mesiac.rok, napr. " + LocalDate.now().format(DATUM));
        } catch (SQLException ex) {
            vystup.setText("EXEC zmen_stav_zakazky(" + id + ", '" + stav + "', ...)\n→ " + Chyby.sprava(ex));
            chyba(ex);
        }
    }

    private void vypisOtvorene() {
        try {
            String text = Db.zachytVystup(c -> {
                try (CallableStatement cs = c.prepareCall("BEGIN vypis_otvorene_zakazky; END;")) {
                    cs.execute();
                }
            });
            vystup.setText("EXEC vypis_otvorene_zakazky\n\n" + text);
            vystup.setCaretPosition(0);
        } catch (SQLException ex) {
            chyba(ex);
        }
    }

    private void vyberZakazku(long id) {
        for (int i = 0; i < model.getRowCount(); i++) {
            if (model.getValueAt(i, 0).equals(id)) {
                table.setRowSelectionInterval(i, i);
                table.scrollRectToVisible(table.getCellRect(i, 0, true));
                return;
            }
        }
    }

    private static LocalDate parsujDatum(String text) {
        return LocalDate.parse(text.trim(), DATUM);
    }

    private static String datum(java.sql.Date d) {
        return d == null ? "" : DATUM_VYPIS.format(d.toLocalDate());
    }

    private static void naplnCiselnik(JComboBox<PolozkyPanel.Polozka> cb, String sql, boolean prazdna) throws SQLException {
        Object vybrana = cb.getSelectedItem();
        cb.removeAllItems();
        if (prazdna) {
            cb.addItem(new PolozkyPanel.Polozka(null, "— bez mechanika —"));
        }
        try (Statement st = Db.get().createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                cb.addItem(new PolozkyPanel.Polozka(rs.getLong(1), rs.getString(2)));
            }
        }
        if (vybrana != null) {
            cb.setSelectedItem(vybrana);
        }
    }

    private static boolean jeNainstalovane() throws SQLException {
        String sql = "SELECT COUNT(*) FROM user_objects WHERE object_type = 'PROCEDURE' "
                + "AND object_name IN ('VYTVOR_SERVISNU_ZAKAZKU', 'ZMEN_STAV_ZAKAZKY', 'VYPIS_OTVORENE_ZAKAZKY')";
        try (Statement st = Db.get().createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1) == 3;
        }
    }

    private void upozorni(String text) {
        JOptionPane.showMessageDialog(this, text, "Upozornenie", JOptionPane.WARNING_MESSAGE);
    }

    private void chyba(SQLException ex) {
        JOptionPane.showMessageDialog(this, Chyby.sprava(ex), "Chyba databázy", JOptionPane.ERROR_MESSAGE);
    }
}
