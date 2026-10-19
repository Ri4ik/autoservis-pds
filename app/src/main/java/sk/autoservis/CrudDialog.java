package sk.autoservis;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Všeobecný formulár CREATE / UPDATE pre ľubovoľnú tabuľku podľa jej metadát ({@link TabulkaMeta}).
 * Cudzie kľúče a CHECK hodnoty sú rozbaľovacie zoznamy, BLOB sa vyberá zo súboru.
 * Všetky príkazy používajú bind premenné; názvy tabuliek a stĺpcov pochádzajú z dátového slovníka.
 */
public class CrudDialog extends JDialog {

    private static final DateTimeFormatter DATUM = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter DATUM_CAS = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final TabulkaMeta meta;
    private final Map<String, Object> povodne;   // null = nový záznam
    private final Map<String, JComponent> polia = new LinkedHashMap<>();
    private final Map<String, Path> subory = new LinkedHashMap<>();
    private boolean ulozene;

    private CrudDialog(Window owner, TabulkaMeta meta, Map<String, Object> povodne) throws SQLException {
        super(owner, (povodne == null ? "Nový záznam – " : "Úprava záznamu – ") + meta.tabulka, ModalityType.APPLICATION_MODAL);
        this.meta = meta;
        this.povodne = povodne;

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(10, 10, 4, 10));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 4, 3, 4);
        c.anchor = GridBagConstraints.WEST;
        int riadok = 0;
        for (TabulkaMeta.Stlpec s : meta.stlpce) {
            c.gridy = riadok++;
            c.gridx = 0;
            c.fill = GridBagConstraints.NONE;
            c.weightx = 0;
            form.add(new JLabel(s.nazov.toLowerCase() + (s.povinny && !s.identity && !s.maDefault ? " *" : "") + ":"), c);
            c.gridx = 1;
            c.fill = GridBagConstraints.HORIZONTAL;
            c.weightx = 1;
            JComponent pole = vytvorPole(s);
            polia.put(s.nazov, pole);
            form.add(pole, c);
            c.gridx = 2;
            c.fill = GridBagConstraints.NONE;
            c.weightx = 0;
            form.add(new JLabel(napoveda(s)), c);
        }

        JButton btnUlozit = new JButton(povodne == null ? "Pridať" : "Uložiť zmeny");
        JButton btnZrusit = new JButton("Zrušiť");
        btnUlozit.addActionListener(e -> uloz());
        btnZrusit.addActionListener(e -> dispose());
        JPanel tlacidla = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        tlacidla.add(new JLabel("* povinný údaj   "));
        tlacidla.add(btnUlozit);
        tlacidla.add(btnZrusit);
        getRootPane().setDefaultButton(btnUlozit);

        setLayout(new BorderLayout());
        add(new JScrollPane(form), BorderLayout.CENTER);
        add(tlacidla, BorderLayout.SOUTH);
        pack();
        setSize(Math.min(getWidth() + 40, 760), Math.min(getHeight() + 20, 640));
        setLocationRelativeTo(owner);
    }

    /** Zobrazí formulár; vráti true, ak sa záznam uložil. {@code povodne == null} znamená nový záznam. */
    public static boolean zobraz(Component parent, TabulkaMeta meta, Map<String, Object> povodne) throws SQLException {
        CrudDialog d = new CrudDialog(SwingUtilities.getWindowAncestor(parent), meta, povodne);
        d.setVisible(true);
        return d.ulozene;
    }

    private Object povodnaHodnota(TabulkaMeta.Stlpec s) {
        return povodne == null ? null : povodne.get(s.nazov);
    }

    private JComponent vytvorPole(TabulkaMeta.Stlpec s) throws SQLException {
        Object hodnota = povodnaHodnota(s);
        if (s.udt) {
            JTextField tf = new JTextField("kolekcia " + s.typ.toLowerCase() + " – upravuje sa v záložke Objekty a kolekcie", 20);
            tf.setEditable(false);
            return tf;
        }
        if (s.identity || (s.pk && povodne != null)) {
            JTextField tf = new JTextField(hodnota == null ? "(vygeneruje sa automaticky)" : hodnota.toString(), 20);
            tf.setEditable(false);
            return tf;
        }
        if (s.fkTabulka != null) {
            JComboBox<PolozkyPanel.Polozka> cb = new JComboBox<>();
            if (!s.povinny) {
                cb.addItem(new PolozkyPanel.Polozka(null, "— prázdne —"));
            }
            String sql = "SELECT " + s.fkStlpec + ", " + s.fkStlpec + " || ' – ' || (" + TabulkaMeta.popisRiadku(s.fkTabulka)
                    + ") FROM " + s.fkTabulka + " ORDER BY 1";
            try (Statement st = Db.get().createStatement(); ResultSet rs = st.executeQuery(sql)) {
                while (rs.next()) {
                    PolozkyPanel.Polozka p = new PolozkyPanel.Polozka(rs.getLong(1), rs.getString(2));
                    cb.addItem(p);
                    if (hodnota != null && new BigDecimal(hodnota.toString()).longValue() == p.id()) {
                        cb.setSelectedItem(p);
                    }
                }
            }
            return cb;
        }
        if (s.povoleneHodnoty != null) {
            JComboBox<String> cb = new JComboBox<>();
            if (!s.povinny || (povodne == null && s.maDefault)) {
                cb.addItem("");
            }
            s.povoleneHodnoty.forEach(cb::addItem);
            cb.setSelectedItem(hodnota == null ? "" : hodnota.toString().trim());
            return cb;
        }
        if (s.jeBlob()) {
            JButton btn = new JButton(povodne == null ? "Vybrať súbor…" : "Nahradiť súborom… (inak bez zmeny)");
            btn.addActionListener(e -> {
                JFileChooser ch = new JFileChooser();
                if (ch.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                    Path p = ch.getSelectedFile().toPath();
                    subory.put(s.nazov, p);
                    btn.setText(p.getFileName().toString());
                }
            });
            return btn;
        }
        JTextField tf = new JTextField(20);
        if (hodnota instanceof Timestamp t) {
            LocalDateTime ldt = t.toLocalDateTime();
            tf.setText((ldt.toLocalTime().toSecondOfDay() == 0 ? DATUM : DATUM_CAS).format(ldt));
        } else if (hodnota != null) {
            tf.setText(hodnota.toString().trim());
        }
        return tf;
    }

    private static String napoveda(TabulkaMeta.Stlpec s) {
        if (s.udt) {
            return "";
        }
        if (s.identity || s.fkTabulka != null || s.povoleneHodnoty != null) {
            return s.fkTabulka != null ? "→ " + s.fkTabulka.toLowerCase() : "";
        }
        if (s.jeDatum()) {
            return "dd.mm.rrrr [hh:mm]" + (s.maDefault ? ", prázdne = predvolená hodnota" : "");
        }
        if (s.jeBlob()) {
            return "súbor";
        }
        if (s.jeCislo()) {
            return "číslo" + (s.maDefault ? ", prázdne = predvolená hodnota" : "");
        }
        return "text, max. " + s.dlzka + " znakov" + (s.maDefault ? ", prázdne = predvolená hodnota" : "");
    }

    /** Hodnota stĺpca z formulára prevedená na typ pre JDBC; null = prázdne pole. */
    private Object hodnotaPola(TabulkaMeta.Stlpec s) {
        JComponent pole = polia.get(s.nazov);
        if (pole instanceof JComboBox<?> cb) {
            Object v = cb.getSelectedItem();
            if (v instanceof PolozkyPanel.Polozka p) {
                return p.id();
            }
            return v == null || v.toString().isEmpty() ? null : v.toString();
        }
        if (pole instanceof JTextField tf) {
            String text = tf.getText().trim();
            if (text.isEmpty()) {
                return null;
            }
            if (s.jeCislo()) {
                try {
                    return new BigDecimal(text.replace(',', '.').replace(" ", ""));
                } catch (NumberFormatException ex) {
                    throw new IllegalArgumentException("Stĺpec " + s.nazov.toLowerCase() + " musí byť číslo.");
                }
            }
            if (s.jeDatum()) {
                try {
                    return text.contains(":")
                            ? Timestamp.valueOf(LocalDateTime.parse(text, DateTimeFormatter.ofPattern("d.M.yyyy H:mm")))
                            : Timestamp.valueOf(LocalDate.parse(text, DateTimeFormatter.ofPattern("d.M.yyyy")).atStartOfDay());
                } catch (DateTimeParseException ex) {
                    throw new IllegalArgumentException("Stĺpec " + s.nazov.toLowerCase()
                            + " musí byť dátum v tvare dd.mm.rrrr alebo dd.mm.rrrr hh:mm.");
                }
            }
            return text;
        }
        return null;
    }

    private void uloz() {
        try {
            List<TabulkaMeta.Stlpec> stlpce = new ArrayList<>();
            List<Object> hodnoty = new ArrayList<>();
            for (TabulkaMeta.Stlpec s : meta.stlpce) {
                if (s.identity || s.udt || (s.pk && povodne != null)) {
                    continue;   // identity, kolekcie a PK pri úprave sa neukladajú z formulára
                }
                if (s.jeBlob()) {
                    Path p = subory.get(s.nazov);
                    if (p == null && povodne != null) {
                        continue;   // BLOB bez zmeny
                    }
                    if (p == null && s.povinny) {
                        throw new IllegalArgumentException("Vyber súbor pre stĺpec " + s.nazov.toLowerCase() + ".");
                    }
                    stlpce.add(s);
                    hodnoty.add(p == null ? null : Files.readAllBytes(p));
                    continue;
                }
                Object v = hodnotaPola(s);
                if (v == null && povodne == null && s.maDefault) {
                    continue;       // nový záznam – použije sa DEFAULT z databázy
                }
                if (v == null && s.povinny) {
                    throw new IllegalArgumentException("Stĺpec " + s.nazov.toLowerCase() + " je povinný.");
                }
                stlpce.add(s);
                hodnoty.add(v);
            }

            String sql;
            List<Object> parametre = new ArrayList<>(hodnoty);
            if (povodne == null) {
                sql = "INSERT INTO " + meta.tabulka + " ("
                        + String.join(", ", stlpce.stream().map(s -> s.nazov).toList()) + ") VALUES ("
                        + String.join(", ", stlpce.stream().map(s -> "?").toList()) + ")";
            } else {
                List<TabulkaMeta.Stlpec> pk = meta.pkStlpce();
                sql = "UPDATE " + meta.tabulka + " SET "
                        + String.join(", ", stlpce.stream().map(s -> s.nazov + " = ?").toList())
                        + " WHERE " + String.join(" AND ", pk.stream().map(s -> s.nazov + " = ?").toList());
                pk.forEach(s -> parametre.add(povodne.get(s.nazov)));
                stlpce.addAll(pk);
            }
            try (PreparedStatement ps = Db.get().prepareStatement(sql)) {
                for (int i = 0; i < parametre.size(); i++) {
                    nastav(ps, i + 1, stlpce.get(i), parametre.get(i));
                }
                ps.executeUpdate();
            }
            ulozene = true;
            dispose();
        } catch (IllegalArgumentException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Nesprávny údaj", JOptionPane.WARNING_MESSAGE);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Súbor sa nepodarilo načítať: " + ex.getMessage(), "Chyba", JOptionPane.ERROR_MESSAGE);
        } catch (SQLException ex) {
            JOptionPane.showMessageDialog(this, Chyby.sprava(ex), "Chyba databázy", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static void nastav(PreparedStatement ps, int index, TabulkaMeta.Stlpec s, Object v) throws SQLException {
        if (v == null) {
            ps.setNull(index, s.jeBlob() ? Types.BLOB : s.jeDatum() ? Types.TIMESTAMP : s.jeCislo() ? Types.NUMERIC : Types.VARCHAR);
        } else if (v instanceof byte[] b) {
            ps.setBinaryStream(index, new java.io.ByteArrayInputStream(b), b.length);
        } else if (v instanceof Long l) {
            ps.setLong(index, l);
        } else {
            ps.setObject(index, v);
        }
    }
}
