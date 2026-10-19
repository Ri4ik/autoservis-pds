package sk.autoservis;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Struct;
import java.util.Arrays;

/**
 * Výstup s použitím objektového typu T_ZAKAZNIK: relačné dáta sa v dopyte zabalia do objektov
 * a nad každým objektom sa zavolajú jeho metódy cele_meno(), kontakt() a pocet_vozidiel().
 */
public class ObjectPanel extends JPanel {

    static final String SQL = """
            SELECT x.obj                    AS objekt,
                   x.obj.cele_meno()        AS cele_meno,
                   x.obj.kontakt()          AS kontakt,
                   x.obj.pocet_vozidiel()   AS pocet_vozidiel
              FROM (SELECT t_zakaznik(id_zakaznik, meno, priezvisko, telefon, email) AS obj
                      FROM zakaznik) x
             ORDER BY x.obj.id_zakaznik""";

    private final DefaultTableModel model = new DefaultTableModel(
            new String[]{"Objekt T_ZAKAZNIK", "cele_meno()", "kontakt()", "pocet_vozidiel()"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JLabel status = new JLabel(" ");

    public ObjectPanel() {
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JLabel popis = new JLabel("<html>Dopyt vytvorí z riadkov tabuľky ZAKAZNIK objekty typu <b>T_ZAKAZNIK</b> "
                + "a nad každým zavolá jeho metódy (<i>cele_meno</i>, <i>kontakt</i>, <i>pocet_vozidiel</i>).</html>");

        JButton btnNacitat = new JButton("Načítať");
        btnNacitat.addActionListener(e -> nacitaj());
        JPanel top = new JPanel(new BorderLayout(8, 4));
        top.add(popis, BorderLayout.CENTER);
        top.add(btnNacitat, BorderLayout.EAST);

        JTable table = new JTable(model);
        table.setAutoCreateRowSorter(true);

        JTextArea sqlArea = new JTextArea(SQL, 7, 60);
        sqlArea.setEditable(false);
        sqlArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane sqlScroll = new JScrollPane(sqlArea);
        sqlScroll.setBorder(BorderFactory.createTitledBorder("Použitý SQL dopyt"));

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(sqlScroll, BorderLayout.CENTER);
        bottom.add(status, BorderLayout.SOUTH);

        add(top, BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);
    }

    private void nacitaj() {
        try {
            if (!TypeInstaller.jeNainstalovany()) {
                int odpoved = JOptionPane.showConfirmDialog(this,
                        "Objektový typ T_ZAKAZNIK ešte nie je v tejto schéme vytvorený.\n"
                                + "Vytvoriť ho teraz (skript 05_object_type.sql)?",
                        "Objektový typ", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
                if (odpoved != JOptionPane.YES_OPTION) {
                    return;
                }
                TypeInstaller.nainstaluj();
            }
            model.setRowCount(0);
            try (Statement st = Db.get().createStatement(); ResultSet rs = st.executeQuery(SQL)) {
                while (rs.next()) {
                    model.addRow(new Object[]{
                            objektAkoText(rs.getObject("objekt")),
                            rs.getString("cele_meno"),
                            rs.getString("kontakt"),
                            rs.getInt("pocet_vozidiel")});
                }
            }
            status.setText("Načítaných objektov: " + model.getRowCount());
        } catch (SQLException ex) {
            JOptionPane.showMessageDialog(this, Chyby.sprava(ex), "Chyba databázy", JOptionPane.ERROR_MESSAGE);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Chyba", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static String objektAkoText(Object o) throws SQLException {
        if (o instanceof Struct s) {
            Object[] atributy = s.getAttributes();
            String zoznam = Arrays.toString(atributy);
            return s.getSQLTypeName() + "(" + zoznam.substring(1, zoznam.length() - 1) + ")";
        }
        return String.valueOf(o);
    }
}
