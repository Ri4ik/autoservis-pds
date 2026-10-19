package sk.autoservis;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Kolekcia NESTED TABLE v modeli: SERVISNA_ZAKAZKA.kontrolny_zoznam (t_kontrolny_zoznam = TABLE OF t_bod_kontroly).
 * CRUD nad prvkami vnorenej tabuľky priamo v SQL: INSERT INTO TABLE(...), UPDATE TABLE(...), DELETE FROM TABLE(...).
 */
public class KontrolnyZoznamPanel extends JPanel {

    private static final String VNORENA = "TABLE(SELECT kontrolny_zoznam FROM servisna_zakazka WHERE id_zakazka = ?)";
    private static final String SQL_CITAJ = """
            SELECT k.polozka, k.stav, k.poznamka
              FROM servisna_zakazka z, TABLE(z.kontrolny_zoznam) k
             WHERE z.id_zakazka = ?""";
    private static final String SQL_INIT = "UPDATE servisna_zakazka SET kontrolny_zoznam = t_kontrolny_zoznam() "
            + "WHERE id_zakazka = ? AND kontrolny_zoznam IS NULL";
    private static final String SQL_PRIDAJ = "INSERT INTO " + VNORENA + " VALUES (t_bod_kontroly(?, ?, ?))";
    private static final String SQL_ZMEN = "UPDATE " + VNORENA + " k SET k.polozka = ?, k.stav = ?, k.poznamka = ? WHERE k.polozka = ?";
    private static final String SQL_ZMAZ = "DELETE FROM " + VNORENA + " k WHERE k.polozka = ?";
    private static final String SQL_STANDARD = "UPDATE servisna_zakazka SET kontrolny_zoznam = standardny_kontrolny_zoznam() WHERE id_zakazka = ?";

    private final DefaultTableModel model = new DefaultTableModel(new String[]{"Bod kontroly", "Stav", "Poznámka"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable table = new JTable(model);
    private final JComboBox<PolozkyPanel.Polozka> cbZakazka = new JComboBox<>();
    private final JTextField tfPolozka = new JTextField(16);
    private final JComboBox<String> cbStav = new JComboBox<>(new String[]{"OK", "CHYBA", "NEKONTROLOVANE"});
    private final JTextField tfPoznamka = new JTextField(22);
    private final JTextArea sqlArea = new JTextArea(6, 60);
    private final JLabel status = new JLabel(" ");
    private boolean nacitavam;

    public KontrolnyZoznamPanel() {
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        cbZakazka.addActionListener(e -> {
            if (!nacitavam) {
                zobraz();
            }
        });
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getSelectionModel().addListSelectionListener(e -> {
            int r = table.getSelectedRow();
            if (!e.getValueIsAdjusting() && r >= 0) {
                tfPolozka.setText((String) model.getValueAt(r, 0));
                cbStav.setSelectedItem(model.getValueAt(r, 1));
                Object p = model.getValueAt(r, 2);
                tfPoznamka.setText(p == null ? "" : p.toString());
            }
        });

        JPanel vyber = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        vyber.add(new JLabel("Zákazka:"));
        vyber.add(cbZakazka);
        JPanel top = new JPanel(new GridLayout(0, 1, 0, 4));
        top.add(new JLabel("<html>Stĺpec <b>SERVISNA_ZAKAZKA.kontrolny_zoznam</b> je <b>NESTED TABLE</b> bodov kontroly pri prijatí "
                + "vozidla (bod, stav, poznámka). Prvky sa menia priamo cez <i>INSERT INTO / UPDATE / DELETE FROM TABLE(...)</i>.</html>"));
        top.add(vyber);

        JButton btnPridat = new JButton("Pridať bod");
        JButton btnZmenit = new JButton("Uložiť zmenu");
        JButton btnZmazat = new JButton("Zmazať bod");
        JButton btnStandard = new JButton("Nastaviť štandardný zoznam");
        btnPridat.addActionListener(e -> pridaj());
        btnZmenit.addActionListener(e -> zmen());
        btnZmazat.addActionListener(e -> zmaz());
        btnStandard.addActionListener(e -> standard());
        JPanel form = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2));
        form.setBorder(BorderFactory.createTitledBorder("Bod kontroly (vyber riadok na úpravu)"));
        form.add(new JLabel("Bod:"));
        form.add(tfPolozka);
        form.add(new JLabel("Stav:"));
        form.add(cbStav);
        form.add(new JLabel("Poznámka:"));
        form.add(tfPoznamka);
        form.add(btnPridat);
        form.add(btnZmenit);
        form.add(btnZmazat);
        form.add(btnStandard);

        sqlArea.setEditable(false);
        sqlArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        sqlArea.setText(SQL_CITAJ);
        JScrollPane sqlScroll = new JScrollPane(sqlArea);
        sqlScroll.setBorder(BorderFactory.createTitledBorder("Použitý SQL príkaz"));

        JPanel bottom = new JPanel(new BorderLayout(4, 4));
        bottom.add(form, BorderLayout.NORTH);
        bottom.add(sqlScroll, BorderLayout.CENTER);
        bottom.add(status, BorderLayout.SOUTH);

        add(top, BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);
    }

    public void nacitaj() {
        nacitavam = true;
        try (Statement st = Db.get().createStatement(); ResultSet rs = st.executeQuery("""
                SELECT z.id_zakazka, z.id_zakazka || ' – ' || v.spz || ' – ' || z.stav || ' – ' || SUBSTR(z.popis_problemu, 1, 40)
                  FROM servisna_zakazka z JOIN vozidlo v ON v.id_vozidlo = z.id_vozidlo
                 ORDER BY z.id_zakazka""")) {
            Object vybrana = cbZakazka.getSelectedItem();
            cbZakazka.removeAllItems();
            while (rs.next()) {
                cbZakazka.addItem(new PolozkyPanel.Polozka(rs.getLong(1), rs.getString(2)));
            }
            if (vybrana != null) {
                cbZakazka.setSelectedItem(vybrana);
            }
        } catch (SQLException ex) {
            chyba(ex);
        } finally {
            nacitavam = false;
        }
        zobraz();
    }

    private Long zakazka() {
        PolozkyPanel.Polozka p = (PolozkyPanel.Polozka) cbZakazka.getSelectedItem();
        return p == null ? null : p.id();
    }

    private void zobraz() {
        Long id = zakazka();
        if (id == null) {
            return;
        }
        model.setRowCount(0);
        int chyby = 0;
        try (PreparedStatement ps = Db.get().prepareStatement(SQL_CITAJ)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    model.addRow(new Object[]{rs.getString(1), rs.getString(2), rs.getString(3)});
                    if ("CHYBA".equals(rs.getString(2))) {
                        chyby++;
                    }
                }
            }
            status.setText(model.getRowCount() == 0 ? "Zákazka nemá kontrolný zoznam (vnorená tabuľka je prázdna alebo NULL)."
                    : "Bodov: " + model.getRowCount() + ", z toho chýb: " + chyby);
        } catch (SQLException ex) {
            chyba(ex);
        }
    }

    private void pridaj() {
        Long id = zakazka();
        String polozka = tfPolozka.getText().trim();
        if (id == null || polozka.isEmpty()) {
            upozorni("Vyplň názov bodu kontroly.");
            return;
        }
        for (int i = 0; i < model.getRowCount(); i++) {
            if (polozka.equalsIgnoreCase((String) model.getValueAt(i, 0))) {
                upozorni("Bod „" + polozka + "“ už v zozname je – vyber ho a použi Uložiť zmenu.");
                return;
            }
        }
        try {
            try (PreparedStatement ps = Db.get().prepareStatement(SQL_INIT)) {   // NULL kolekciu najprv inicializovať
                ps.setLong(1, id);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = Db.get().prepareStatement(SQL_PRIDAJ)) {
                ps.setLong(1, id);
                ps.setString(2, polozka);
                ps.setString(3, (String) cbStav.getSelectedItem());
                ps.setString(4, tfPoznamka.getText().isBlank() ? null : tfPoznamka.getText().trim());
                ps.executeUpdate();
            }
            sqlArea.setText(SQL_INIT + ";\n" + SQL_PRIDAJ);
            zobraz();
        } catch (SQLException ex) {
            chyba(ex);
        }
    }

    private void zmen() {
        int r = table.getSelectedRow();
        Long id = zakazka();
        if (r < 0 || id == null) {
            upozorni("Najprv vyber bod v tabuľke.");
            return;
        }
        String povodna = (String) model.getValueAt(r, 0);
        try (PreparedStatement ps = Db.get().prepareStatement(SQL_ZMEN)) {
            ps.setLong(1, id);
            ps.setString(2, tfPolozka.getText().trim().isEmpty() ? povodna : tfPolozka.getText().trim());
            ps.setString(3, (String) cbStav.getSelectedItem());
            ps.setString(4, tfPoznamka.getText().isBlank() ? null : tfPoznamka.getText().trim());
            ps.setString(5, povodna);
            ps.executeUpdate();
            sqlArea.setText(SQL_ZMEN);
            zobraz();
        } catch (SQLException ex) {
            chyba(ex);
        }
    }

    private void zmaz() {
        int r = table.getSelectedRow();
        Long id = zakazka();
        if (r < 0 || id == null) {
            upozorni("Najprv vyber bod v tabuľke.");
            return;
        }
        try (PreparedStatement ps = Db.get().prepareStatement(SQL_ZMAZ)) {
            ps.setLong(1, id);
            ps.setString(2, (String) model.getValueAt(r, 0));
            ps.executeUpdate();
            sqlArea.setText(SQL_ZMAZ);
            zobraz();
        } catch (SQLException ex) {
            chyba(ex);
        }
    }

    private void standard() {
        Long id = zakazka();
        if (id == null || JOptionPane.showConfirmDialog(this, "Nahradiť kontrolný zoznam zákazky " + id
                + " štandardným zoznamom (všetky body NEKONTROLOVANE)?", "Potvrdenie", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
            return;
        }
        try (PreparedStatement ps = Db.get().prepareStatement(SQL_STANDARD)) {
            ps.setLong(1, id);
            ps.executeUpdate();
            sqlArea.setText(SQL_STANDARD);
            zobraz();
        } catch (SQLException ex) {
            chyba(ex);
        }
    }

    private void upozorni(String text) {
        JOptionPane.showMessageDialog(this, text, "Upozornenie", JOptionPane.WARNING_MESSAGE);
    }

    private void chyba(SQLException ex) {
        String sprava = ex.getErrorCode() == 904 || ex.getErrorCode() == 6550 || ex.getErrorCode() == 6575
                ? "V schéme chýba stĺpec kontrolny_zoznam alebo funkcia STANDARDNY_KONTROLNY_ZOZNAM.\n"
                  + "Spustite 02a_migracia_kolekcie.sql (staršia schéma) a 12_plsql_zakazky.sql."
                : Chyby.sprava(ex);
        JOptionPane.showMessageDialog(this, sprava, "Kontrolný zoznam (NESTED TABLE)", JOptionPane.ERROR_MESSAGE);
    }
}
