package sk.autoservis;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.math.BigDecimal;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;

/**
 * Servisné položky zákazky – objektový typ T_SERVISNA_POLOZKA uložený ako atribút tabuľky
 * SERVISNA_POLOZKA_ZAKAZKY (skript 08_object_servisna_polozka.sql). Zobrazuje výsledky metód
 * get_cena() a get_cena_po_zlave(), triedenie cez MAP metódu a pridávanie položky cez vlastný konštruktor.
 */
public class PolozkyPanel extends JPanel {

    private static final String SQL_ZOZNAM = """
            SELECT p.id_polozka,
                   p.id_zakazka,
                   p.polozka.nazov               AS nazov,
                   p.polozka.jednotkova_cena     AS jednotkova_cena,
                   p.polozka.mnozstvo            AS mnozstvo,
                   p.polozka.zlava_percent       AS zlava_percent,
                   p.polozka.get_cena()          AS cena,
                   p.polozka.get_cena_po_zlave() AS cena_po_zlave
              FROM servisna_polozka_zakazky p
             WHERE (? IS NULL OR p.id_zakazka = ?)
             ORDER BY %s""";

    /** Vlastný konštruktor nastaví množstvo 1 a zľavu 0 %; potom sa upravia atribúty a položka sa vloží. */
    private static final String SQL_PRIDAT = """
            DECLARE
              v_polozka t_servisna_polozka;
            BEGIN
              SELECT t_servisna_polozka(u.id_ukon, u.nazov, u.standardna_cena)
                INTO v_polozka
                FROM servisny_ukon u
               WHERE u.id_ukon = ?;
              v_polozka.mnozstvo      := ?;
              v_polozka.zlava_percent := ?;
              INSERT INTO servisna_polozka_zakazky (id_zakazka, polozka) VALUES (?, v_polozka);
            END;""";

    private static final String[] TRIEDENIE_POPIS = {
            "MAP metóda – cena po zľave vzostupne (ORDER BY p.polozka)",
            "MAP metóda – cena po zľave zostupne (ORDER BY p.polozka DESC)",
            "ID položky"
    };
    private static final String[] TRIEDENIE_SQL = {"p.polozka, p.id_polozka", "p.polozka DESC, p.id_polozka", "p.id_polozka"};

    private final DefaultTableModel model = new DefaultTableModel(
            new String[]{"ID", "Zákazka", "Názov", "Jedn. cena", "Množstvo", "Zľava %", "get_cena()", "get_cena_po_zlave()"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable table = new JTable(model);
    private final JComboBox<Polozka> cbFilter = new JComboBox<>();
    private final JComboBox<String> cbTriedenie = new JComboBox<>(TRIEDENIE_POPIS);
    private final JComboBox<Polozka> cbZakazka = new JComboBox<>();
    private final JComboBox<Polozka> cbUkon = new JComboBox<>();
    private final JSpinner spMnozstvo = new JSpinner(new SpinnerNumberModel(1.0, 0.5, 1000.0, 0.5));
    private final JSpinner spZlava = new JSpinner(new SpinnerNumberModel(0.0, 0.0, 100.0, 5.0));
    private final JTextArea sqlArea = new JTextArea(7, 60);
    private final JLabel status = new JLabel(" ");
    private boolean nacitavam;

    public PolozkyPanel() {
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JButton btnNacitat = new JButton("Načítať");
        btnNacitat.addActionListener(e -> nacitaj());
        cbFilter.addActionListener(e -> {
            if (!nacitavam) {
                nacitajPolozky();
            }
        });
        cbTriedenie.addActionListener(e -> {
            if (!nacitavam) {
                nacitajPolozky();
            }
        });

        JPanel filtre = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        filtre.add(new JLabel("Zákazka:"));
        filtre.add(cbFilter);
        filtre.add(new JLabel("Triedenie:"));
        filtre.add(cbTriedenie);
        filtre.add(btnNacitat);

        JLabel popis = new JLabel("<html>Položky sú objekty typu <b>T_SERVISNA_POLOZKA</b> uložené v stĺpci tabuľky "
                + "SERVISNA_POLOZKA_ZAKAZKY. Ceny počítajú metódy <i>get_cena()</i> a <i>get_cena_po_zlave()</i>, "
                + "triedenie objektov zabezpečuje <i>MAP MEMBER FUNCTION</i>.</html>");
        JPanel top = new JPanel(new BorderLayout(4, 4));
        top.add(popis, BorderLayout.NORTH);
        top.add(filtre, BorderLayout.CENTER);

        table.setAutoCreateRowSorter(false);
        table.getColumnModel().getColumn(2).setPreferredWidth(220);

        sqlArea.setEditable(false);
        sqlArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane sqlScroll = new JScrollPane(sqlArea);
        sqlScroll.setBorder(BorderFactory.createTitledBorder("Použitý SQL dopyt"));

        JPanel bottom = new JPanel(new BorderLayout(4, 4));
        bottom.add(vytvorFormular(), BorderLayout.NORTH);
        bottom.add(sqlScroll, BorderLayout.CENTER);
        bottom.add(status, BorderLayout.SOUTH);

        add(top, BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);
    }

    private JComponent vytvorFormular() {
        JButton btnPridat = new JButton("Pridať položku (konštruktor)");
        btnPridat.addActionListener(e -> pridat());
        JButton btnZmazat = new JButton("Zmazať vybranú");
        btnZmazat.addActionListener(e -> zmazat());

        JPanel form = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        form.setBorder(BorderFactory.createTitledBorder("Nová položka – vlastný konštruktor t_servisna_polozka(id_ukon, nazov, cena)"));
        form.add(new JLabel("Zákazka:"));
        form.add(cbZakazka);
        form.add(new JLabel("Úkon:"));
        form.add(cbUkon);
        form.add(new JLabel("Množstvo:"));
        form.add(spMnozstvo);
        form.add(new JLabel("Zľava %:"));
        form.add(spZlava);
        form.add(btnPridat);
        form.add(btnZmazat);
        return form;
    }

    /** Načíta číselníky (zákazky, úkony) aj samotné položky. Volá sa pri otvorení záložky. */
    public void nacitaj() {
        try {
            if (!jeNainstalovane()) {
                status.setText("V schéme chýba typ T_SERVISNA_POLOZKA alebo tabuľka SERVISNA_POLOZKA_ZAKAZKY.");
                JOptionPane.showMessageDialog(this,
                        "Objektový typ T_SERVISNA_POLOZKA ešte nie je v tejto schéme vytvorený.\n"
                                + "Spustite v SQL Developeri skript 08_object_servisna_polozka.sql (F5).",
                        "Objektový typ", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            nacitavam = true;
            Object vybrany = cbFilter.getSelectedItem();
            cbFilter.removeAllItems();
            cbZakazka.removeAllItems();
            cbFilter.addItem(new Polozka(null, "všetky"));
            for (Polozka z : nacitajCiselnik("""
                    SELECT z.id_zakazka, z.id_zakazka || ' – ' || za.priezvisko || ', ' || v.spz || ' (' || z.stav || ')'
                      FROM servisna_zakazka z
                      JOIN vozidlo v ON v.id_vozidlo = z.id_vozidlo
                      JOIN zakaznik za ON za.id_zakaznik = v.id_zakaznik
                     ORDER BY z.id_zakazka""")) {
                cbFilter.addItem(z);
                cbZakazka.addItem(z);
            }
            if (vybrany != null) {
                cbFilter.setSelectedItem(vybrany);
            }
            cbUkon.removeAllItems();
            for (Polozka u : nacitajCiselnik(
                    "SELECT id_ukon, nazov || ' (' || standardna_cena || ' €)' FROM servisny_ukon ORDER BY nazov")) {
                cbUkon.addItem(u);
            }
        } catch (SQLException ex) {
            chyba(ex);
            return;
        } finally {
            nacitavam = false;
        }
        nacitajPolozky();
    }

    private void nacitajPolozky() {
        Polozka filter = (Polozka) cbFilter.getSelectedItem();
        Long idZakazky = filter == null ? null : filter.id();
        String sql = SQL_ZOZNAM.formatted(TRIEDENIE_SQL[Math.max(0, cbTriedenie.getSelectedIndex())]);
        sqlArea.setText(sql);
        sqlArea.setCaretPosition(0);
        model.setRowCount(0);
        try (PreparedStatement ps = Db.get().prepareStatement(sql)) {
            for (int i = 1; i <= 2; i++) {
                if (idZakazky == null) {
                    ps.setNull(i, Types.NUMERIC);
                } else {
                    ps.setLong(i, idZakazky);
                }
            }
            BigDecimal spolu = BigDecimal.ZERO;
            BigDecimal spoluPoZlave = BigDecimal.ZERO;
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    model.addRow(new Object[]{
                            rs.getLong("id_polozka"), rs.getLong("id_zakazka"), rs.getString("nazov"),
                            rs.getBigDecimal("jednotkova_cena"), rs.getBigDecimal("mnozstvo"),
                            rs.getBigDecimal("zlava_percent"), rs.getBigDecimal("cena"), rs.getBigDecimal("cena_po_zlave")});
                    spolu = spolu.add(rs.getBigDecimal("cena"));
                    spoluPoZlave = spoluPoZlave.add(rs.getBigDecimal("cena_po_zlave"));
                }
            }
            status.setText("Položiek: " + model.getRowCount() + "   |   spolu bez zľavy: " + spolu
                    + " €   |   po zľave: " + spoluPoZlave + " €");
        } catch (SQLException ex) {
            chyba(ex);
        }
    }

    private void pridat() {
        Polozka zakazka = (Polozka) cbZakazka.getSelectedItem();
        Polozka ukon = (Polozka) cbUkon.getSelectedItem();
        if (zakazka == null || ukon == null) {
            JOptionPane.showMessageDialog(this, "Vyber zákazku aj servisný úkon.", "Upozornenie", JOptionPane.WARNING_MESSAGE);
            return;
        }
        try (CallableStatement cs = Db.get().prepareCall(SQL_PRIDAT)) {
            cs.setLong(1, ukon.id());
            cs.setBigDecimal(2, BigDecimal.valueOf((Double) spMnozstvo.getValue()));
            cs.setBigDecimal(3, BigDecimal.valueOf((Double) spZlava.getValue()));
            cs.setLong(4, zakazka.id());
            cs.execute();
            sqlArea.setText(SQL_PRIDAT);
            nacitajPolozky();
            status.setText("Položka bola pridaná do zákazky " + zakazka.id() + ". " + status.getText());
        } catch (SQLException ex) {
            chyba(ex);
        }
    }

    private void zmazat() {
        int row = table.getSelectedRow();
        if (row < 0) {
            JOptionPane.showMessageDialog(this, "Najprv vyber položku v tabuľke.", "Upozornenie", JOptionPane.WARNING_MESSAGE);
            return;
        }
        Object id = model.getValueAt(row, 0);
        if (JOptionPane.showConfirmDialog(this, "Zmazať položku " + id + " (" + model.getValueAt(row, 2) + ")?",
                "Potvrdenie", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
            return;
        }
        try (PreparedStatement ps = Db.get().prepareStatement("DELETE FROM servisna_polozka_zakazky WHERE id_polozka = ?")) {
            ps.setObject(1, id);
            ps.executeUpdate();
            nacitajPolozky();
        } catch (SQLException ex) {
            chyba(ex);
        }
    }

    private static boolean jeNainstalovane() throws SQLException {
        String sql = """
                SELECT (SELECT COUNT(*) FROM user_objects
                         WHERE object_name = 'T_SERVISNA_POLOZKA' AND object_type IN ('TYPE', 'TYPE BODY'))
                     + (SELECT COUNT(*) FROM user_tables WHERE table_name = 'SERVISNA_POLOZKA_ZAKAZKY')
                  FROM dual""";
        try (Statement st = Db.get().createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1) == 3;
        }
    }

    private static java.util.List<Polozka> nacitajCiselnik(String sql) throws SQLException {
        java.util.List<Polozka> list = new java.util.ArrayList<>();
        try (Statement st = Db.get().createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                list.add(new Polozka(rs.getLong(1), rs.getString(2)));
            }
        }
        return list;
    }

    private void chyba(SQLException ex) {
        JOptionPane.showMessageDialog(this, Chyby.sprava(ex), "Chyba databázy", JOptionPane.ERROR_MESSAGE);
    }

    /** Položka rozbaľovacieho zoznamu: ID + text. */
    record Polozka(Long id, String text) {
        @Override
        public String toString() {
            return text;
        }
    }
}
