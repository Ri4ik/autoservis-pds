package sk.autoservis;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Kolekcia VARRAY v modeli: VOZIDLO.historia_km (t_historia_km = VARRAY(30) OF t_stav_km).
 * Zobrazenie cez TABLE() s rozdielmi oproti predchádzajúcemu záznamu (LAG) a pridanie záznamu
 * procedúrou PRIDAJ_STAV_KM (kontrola chronológie a rastúceho stavu tachometra).
 */
public class HistoriaKmPanel extends JPanel {

    private static final String SQL = """
            SELECT h.datum, h.km, h.zdroj,
                   h.km - LAG(h.km) OVER (ORDER BY h.datum)       AS km_od_predch,
                   h.datum - LAG(h.datum) OVER (ORDER BY h.datum) AS dni_od_predch
              FROM vozidlo v, TABLE(v.historia_km) h
             WHERE v.id_vozidlo = ?
             ORDER BY h.datum""";

    private static final DateTimeFormatter DATUM = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter DATUM_VSTUP = DateTimeFormatter.ofPattern("d.M.yyyy");

    private final DefaultTableModel model = new DefaultTableModel(
            new String[]{"#", "Dátum", "Stav km", "Zdroj", "Km od predch.", "Dní od predch."}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JComboBox<PolozkyPanel.Polozka> cbVozidlo = new JComboBox<>();
    private final JTextField tfDatum = new JTextField(9);
    private final JTextField tfKm = new JTextField(8);
    private final JComboBox<String> cbZdroj = new JComboBox<>(new String[]{"SERVIS", "STK", "ZAKAZNIK", "PREDAJ"});
    private final JLabel status = new JLabel(" ");
    private boolean nacitavam;

    public HistoriaKmPanel() {
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        cbVozidlo.addActionListener(e -> {
            if (!nacitavam) {
                zobraz();
            }
        });
        JPanel vyber = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        vyber.add(new JLabel("Vozidlo:"));
        vyber.add(cbVozidlo);

        JPanel top = new JPanel(new GridLayout(0, 1, 0, 4));
        top.add(new JLabel("<html>Stĺpec <b>VOZIDLO.historia_km</b> je <b>VARRAY(30) OF t_stav_km</b> (dátum, km, zdroj). "
                + "Číta sa cez <i>TABLE(v.historia_km)</i>, nový záznam pridáva procedúra <i>PRIDAJ_STAV_KM</i> "
                + "(VARRAY sa mení ako celok – EXTEND, pri plnom poli sa najstarší záznam vyradí).</html>"));
        top.add(vyber);

        JButton btnPridat = new JButton("Pridať záznam (PRIDAJ_STAV_KM)");
        btnPridat.addActionListener(e -> pridaj());
        JPanel form = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2));
        form.setBorder(BorderFactory.createTitledBorder("Nový záznam tachometra"));
        form.add(new JLabel("Dátum:"));
        form.add(tfDatum);
        form.add(new JLabel("Km:"));
        form.add(tfKm);
        form.add(new JLabel("Zdroj:"));
        form.add(cbZdroj);
        form.add(btnPridat);

        JTextArea sqlArea = new JTextArea(SQL, 7, 60);
        sqlArea.setEditable(false);
        sqlArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane sqlScroll = new JScrollPane(sqlArea);
        sqlScroll.setBorder(BorderFactory.createTitledBorder("Použitý SQL dopyt"));

        JPanel bottom = new JPanel(new BorderLayout(4, 4));
        bottom.add(form, BorderLayout.NORTH);
        bottom.add(sqlScroll, BorderLayout.CENTER);
        bottom.add(status, BorderLayout.SOUTH);

        add(top, BorderLayout.NORTH);
        add(new JScrollPane(new JTable(model)), BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);
    }

    public void nacitaj() {
        if (tfDatum.getText().isBlank()) {
            tfDatum.setText(LocalDate.now().format(DATUM_VSTUP));
        }
        nacitavam = true;
        try (Statement st = Db.get().createStatement(); ResultSet rs = st.executeQuery("""
                SELECT v.id_vozidlo, v.spz || ' – ' || v.znacka || ' ' || v.model || ' (' || za.priezvisko || ')'
                  FROM vozidlo v JOIN zakaznik za ON za.id_zakaznik = v.id_zakaznik
                 ORDER BY v.id_vozidlo""")) {
            Object vybrane = cbVozidlo.getSelectedItem();
            cbVozidlo.removeAllItems();
            while (rs.next()) {
                cbVozidlo.addItem(new PolozkyPanel.Polozka(rs.getLong(1), rs.getString(2)));
            }
            if (vybrane != null) {
                cbVozidlo.setSelectedItem(vybrane);
            }
        } catch (SQLException ex) {
            chyba(ex);
        } finally {
            nacitavam = false;
        }
        zobraz();
    }

    private void zobraz() {
        PolozkyPanel.Polozka v = (PolozkyPanel.Polozka) cbVozidlo.getSelectedItem();
        if (v == null) {
            return;
        }
        model.setRowCount(0);
        try (PreparedStatement ps = Db.get().prepareStatement(SQL)) {
            ps.setLong(1, v.id());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    model.addRow(new Object[]{model.getRowCount() + 1,
                            DATUM.format(rs.getDate("datum").toLocalDate()), rs.getLong("km"), rs.getString("zdroj"),
                            rs.getObject("km_od_predch"), rs.getObject("dni_od_predch")});
                }
            }
            status.setText(model.getRowCount() == 0
                    ? "Vozidlo zatiaľ nemá históriu tachometra (VARRAY je prázdne alebo NULL)."
                    : "Záznamov: " + model.getRowCount() + " z max. 30 (limit VARRAY).");
        } catch (SQLException ex) {
            chyba(ex);
        }
    }

    private void pridaj() {
        PolozkyPanel.Polozka v = (PolozkyPanel.Polozka) cbVozidlo.getSelectedItem();
        if (v == null) {
            return;
        }
        try (CallableStatement cs = Db.get().prepareCall("BEGIN pridaj_stav_km(?, ?, ?, ?); END;")) {
            cs.setLong(1, v.id());
            cs.setDate(2, java.sql.Date.valueOf(LocalDate.parse(tfDatum.getText().trim(), DATUM_VSTUP)));
            cs.setLong(3, Long.parseLong(tfKm.getText().trim().replace(" ", "")));
            cs.setString(4, (String) cbZdroj.getSelectedItem());
            cs.execute();
            tfKm.setText("");
            zobraz();
        } catch (DateTimeParseException ex) {
            JOptionPane.showMessageDialog(this, "Dátum zadaj v tvare deň.mesiac.rok.", "Upozornenie", JOptionPane.WARNING_MESSAGE);
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this, "Stav km musí byť celé číslo.", "Upozornenie", JOptionPane.WARNING_MESSAGE);
        } catch (SQLException ex) {
            chyba(ex);
        }
    }

    private void chyba(SQLException ex) {
        String sprava = ex.getErrorCode() == 904 || ex.getErrorCode() == 6550
                ? "V schéme chýba stĺpec historia_km alebo procedúra PRIDAJ_STAV_KM.\n"
                  + "Spustite 02a_migracia_kolekcie.sql (staršia schéma) a 12_plsql_zakazky.sql."
                : Chyby.sprava(ex);
        JOptionPane.showMessageDialog(this, sprava, "História km (VARRAY)", JOptionPane.ERROR_MESSAGE);
    }
}
