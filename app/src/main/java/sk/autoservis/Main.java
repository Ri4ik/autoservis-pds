package sk.autoservis;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Properties;

/** Vstupný bod aplikácie: prihlásenie do Oracle a hlavné okno so záložkami. */
public class Main {

    private static final Path CONFIG = Path.of("db.properties");

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // zostane predvolený vzhľad
        }
        SwingUtilities.invokeLater(Main::start);
    }

    private static void start() {
        if (!prihlasSa()) {
            System.exit(0);
        }
        JFrame frame = new JFrame("Autoservis – informačný systém");
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                Db.close();
                frame.dispose();
                System.exit(0);
            }
        });

        // Štruktúra: dáta (CRUD) -> procesy (PL/SQL) -> reporty -> objekty -> exporty XML / JSON
        JTabbedPane tabs = new JTabbedPane();
        TabulkyPanel tabulky = new TabulkyPanel();
        ZakazkyPanel zakazky = new ZakazkyPanel();
        ReportyPanel reporty = new ReportyPanel();
        HistoriaPanel historia = new HistoriaPanel();
        ObjektyPanel objekty = new ObjektyPanel();
        DokumentyPanel xml = new DokumentyPanel(true);
        DokumentyPanel json = new DokumentyPanel(false);
        tabs.addTab("Údaje (CRUD)", tabulky);
        tabs.addTab("Zákazky (PL/SQL)", zakazky);
        tabs.addTab("Reporty", reporty);
        tabs.addTab("História vozidla", historia);
        tabs.addTab("Objekty a kolekcie", objekty);
        tabs.addTab("XML", xml);
        tabs.addTab("JSON", json);
        // Obsah záložky sa načíta vždy po jej otvorení, takže zobrazuje aktuálne dáta.
        tabs.addChangeListener(e -> {
            Component vybrana = tabs.getSelectedComponent();
            if (vybrana == tabulky) {
                tabulky.nacitaj();
            } else if (vybrana == zakazky) {
                zakazky.nacitaj();
            } else if (vybrana == reporty) {
                reporty.nacitaj();
            } else if (vybrana == historia) {
                historia.nacitaj();
            } else if (vybrana == objekty) {
                objekty.nacitaj();
            } else if (vybrana == xml) {
                xml.nacitaj();
            } else if (vybrana == json) {
                json.nacitaj();
            }
        });
        tabulky.nacitaj();

        frame.add(tabs);
        frame.setSize(1100, 700);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    /** Zobrazuje prihlasovacie okno, kým sa pripojenie nepodarí alebo používateľ nezruší. */
    private static boolean prihlasSa() {
        Properties props = nacitajKonfiguraciu();
        JTextField tfUrl = new JTextField(props.getProperty("url", "jdbc:oracle:thin:@//localhost:1521/XEPDB1"), 32);
        JTextField tfUser = new JTextField(props.getProperty("user", ""), 32);
        JPasswordField tfHeslo = new JPasswordField(props.getProperty("password", ""), 32);

        JPanel panel = new JPanel(new GridLayout(0, 1, 0, 4));
        panel.add(new JLabel("JDBC URL (napr. jdbc:oracle:thin:@//localhost:1521/XEPDB1):"));
        panel.add(tfUrl);
        panel.add(new JLabel("Používateľ (schéma):"));
        panel.add(tfUser);
        panel.add(new JLabel("Heslo:"));
        panel.add(tfHeslo);

        while (true) {
            int volba = JOptionPane.showConfirmDialog(null, panel, "Pripojenie k Oracle databáze",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (volba != JOptionPane.OK_OPTION) {
                return false;
            }
            try {
                Db.connect(tfUrl.getText().trim(), tfUser.getText().trim(), new String(tfHeslo.getPassword()));
                ulozKonfiguraciu(tfUrl.getText().trim(), tfUser.getText().trim());
                return true;
            } catch (SQLException ex) {
                JOptionPane.showMessageDialog(null, "Pripojenie sa nepodarilo:\n" + Chyby.sprava(ex),
                        "Chyba pripojenia", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private static Properties nacitajKonfiguraciu() {
        Properties p = new Properties();
        if (Files.exists(CONFIG)) {
            try (InputStream in = Files.newInputStream(CONFIG)) {
                p.load(in);
            } catch (IOException ignored) {
                // použijú sa predvolené hodnoty
            }
        }
        return p;
    }

    /** Uloží iba URL a meno – heslo sa z bezpečnostných dôvodov neukladá. */
    private static void ulozKonfiguraciu(String url, String user) {
        Properties p = new Properties();
        p.setProperty("url", url);
        p.setProperty("user", user);
        try (OutputStream out = Files.newOutputStream(CONFIG)) {
            p.store(out, "Predvyplnené hodnoty prihlasovacieho okna (heslo sa neukladá)");
        } catch (IOException ignored) {
            // adresár nemusí byť zapisovateľný – nevadí
        }
    }
}
