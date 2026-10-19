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
import java.time.format.DateTimeFormatter;

/**
 * Report 10 – servisná história vozidla (skript 11_report10_historia_vozidla.sql).
 * Tabuľka sa plní z kolekcie, ktorú vracia funkcia HISTORIA_VOZIDLA (SELECT ... FROM TABLE(...)),
 * textový výpis je výstup PL/SQL procedúry VYPIS_HISTORIU_VOZIDLA (RECORD + kolekcie, DBMS_OUTPUT).
 */
public class HistoriaPanel extends JPanel {

    private static final String SQL_HISTORIA = """
            SELECT h.id_zakazka, h.datum_prijatia, h.stav, h.stav_km, h.mechanik, h.ukony,
                   h.suma_ukony, h.suma_diely, h.suma_spolu
              FROM TABLE(historia_vozidla(?)) h""";

    private static final DateTimeFormatter DATUM = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final DefaultTableModel model = new DefaultTableModel(
            new String[]{"Zákazka", "Prijatá", "Stav", "Km", "Mechanik", "Úkony", "Úkony €", "Diely €", "Spolu €"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable table = new JTable(model);
    private final JComboBox<PolozkyPanel.Polozka> cbVozidlo = new JComboBox<>();
    private final JTextArea vypis = new JTextArea(12, 60);
    private final JLabel status = new JLabel(" ");
    private boolean nacitavam;

    public HistoriaPanel() {
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        cbVozidlo.addActionListener(e -> {
            if (!nacitavam) {
                zobrazHistoriu();
            }
        });
        JButton btnZobrazit = new JButton("Zobraziť históriu");
        btnZobrazit.addActionListener(e -> zobrazHistoriu());

        JPanel vyber = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        vyber.add(new JLabel("Vozidlo:"));
        vyber.add(cbVozidlo);
        vyber.add(btnZobrazit);

        JPanel top = new JPanel(new BorderLayout(4, 4));
        top.add(new JLabel("<html><b>Report 10:</b> servisná história vozidla. Tabuľka je kolekcia <i>T_HISTORIA_TAB</i> "
                + "z funkcie <i>HISTORIA_VOZIDLA</i>, dolný výpis je výstup procedúry <i>VYPIS_HISTORIU_VOZIDLA</i> "
                + "(PL/SQL RECORD, vnorená tabuľka, asociatívne pole, BULK COLLECT).</html>"), BorderLayout.NORTH);
        top.add(vyber, BorderLayout.CENTER);

        table.getColumnModel().getColumn(5).setPreferredWidth(260);
        vypis.setEditable(false);
        vypis.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane vypisScroll = new JScrollPane(vypis);
        vypisScroll.setBorder(BorderFactory.createTitledBorder("EXEC vypis_historiu_vozidla(id) – DBMS_OUTPUT"));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(table), vypisScroll);
        split.setResizeWeight(0.45);

        add(top, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);
    }

    /** Naplní zoznam vozidiel; volá sa pri otvorení záložky. */
    public void nacitaj() {
        try {
            if (!jeNainstalovane()) {
                status.setText("V schéme chýba funkcia HISTORIA_VOZIDLA alebo procedúra VYPIS_HISTORIU_VOZIDLA.");
                JOptionPane.showMessageDialog(this, "Funkcia HISTORIA_VOZIDLA ešte nie je v tejto schéme vytvorená.\n"
                        + "Spustite v SQL Developeri skript 11_report10_historia_vozidla.sql (F5).",
                        "Report 10", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            nacitavam = true;
            Object vybrane = cbVozidlo.getSelectedItem();
            cbVozidlo.removeAllItems();
            String sql = """
                    SELECT v.id_vozidlo, v.spz || ' – ' || v.znacka || ' ' || v.model || ' (' || za.priezvisko || ')'
                      FROM vozidlo v JOIN zakaznik za ON za.id_zakaznik = v.id_zakaznik
                     ORDER BY v.id_vozidlo""";
            try (Statement st = Db.get().createStatement(); ResultSet rs = st.executeQuery(sql)) {
                while (rs.next()) {
                    cbVozidlo.addItem(new PolozkyPanel.Polozka(rs.getLong(1), rs.getString(2)));
                }
            }
            if (vybrane != null) {
                cbVozidlo.setSelectedItem(vybrane);
            }
        } catch (SQLException ex) {
            chyba(ex);
            return;
        } finally {
            nacitavam = false;
        }
        zobrazHistoriu();
    }

    private void zobrazHistoriu() {
        PolozkyPanel.Polozka vozidlo = (PolozkyPanel.Polozka) cbVozidlo.getSelectedItem();
        if (vozidlo == null) {
            return;
        }
        model.setRowCount(0);
        try (PreparedStatement ps = Db.get().prepareStatement(SQL_HISTORIA)) {
            ps.setLong(1, vozidlo.id());
            BigDecimal spolu = BigDecimal.ZERO;
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    model.addRow(new Object[]{
                            rs.getLong("id_zakazka"),
                            DATUM.format(rs.getDate("datum_prijatia").toLocalDate()),
                            rs.getString("stav"),
                            rs.getObject("stav_km"),
                            rs.getString("mechanik"),
                            rs.getString("ukony"),
                            rs.getBigDecimal("suma_ukony"),
                            rs.getBigDecimal("suma_diely"),
                            rs.getBigDecimal("suma_spolu")});
                    spolu = spolu.add(rs.getBigDecimal("suma_spolu"));
                }
            }
            status.setText("Zákaziek: " + model.getRowCount() + "   |   spolu za servis: " + spolu + " €");
            vypis.setText(vystupProcedury(vozidlo.id()));
            vypis.setCaretPosition(0);
        } catch (SQLException ex) {
            chyba(ex);
        }
    }

    /** Zavolá procedúru a vráti jej DBMS_OUTPUT. */
    private static String vystupProcedury(long idVozidla) throws SQLException {
        return Db.zachytVystup(c -> {
            try (CallableStatement cs = c.prepareCall("BEGIN vypis_historiu_vozidla(?); END;")) {
                cs.setLong(1, idVozidla);
                cs.execute();
            }
        });
    }

    private static boolean jeNainstalovane() throws SQLException {
        String sql = "SELECT COUNT(*) FROM user_objects WHERE object_name IN ('HISTORIA_VOZIDLA', 'VYPIS_HISTORIU_VOZIDLA')";
        try (Statement st = Db.get().createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1) == 2;
        }
    }

    private void chyba(SQLException ex) {
        JOptionPane.showMessageDialog(this, Chyby.sprava(ex), "Chyba databázy", JOptionPane.ERROR_MESSAGE);
    }
}
