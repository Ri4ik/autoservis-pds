package sk.autoservis;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;

/**
 * Všetky tabuľkové SQL reporty projektu na jednom mieste (výber zo zoznamu).
 * Report 10 (história vozidla) má vlastnú záložku, report 11 je v záložke Objekty a kolekcie, report 12 v záložkách XML / JSON.
 * Doplnkové výstupy 13 a 14 spracúvajú kolekcie v modeli (VARRAY a NESTED TABLE).
 */
public class ReportyPanel extends JPanel {

    private record Report(String nazov, String popis, String skript, String sql) {
        @Override
        public String toString() {
            return nazov;
        }
    }

    private static final Report[] REPORTY = {
            new Report("1 – Celkové tržby (SUM, COUNT)",
                    "Tržby = skutočne prijaté platby v tabuľke PLATBA.", "04_reports.sql", """
                    SELECT NVL(SUM(p.suma), 0) AS celkove_trzby_eur,
                           COUNT(p.id_platba)  AS pocet_platieb
                      FROM platba p"""),
            new Report("2 – Mesačné tržby (GROUP BY, TRUNC)",
                    "Súčet prijatých platieb po mesiacoch.", "04_reports.sql", """
                    SELECT TRUNC(p.datum_platby, 'MM') AS mesiac,
                           COUNT(*)                    AS pocet_platieb,
                           SUM(p.suma)                 AS trzby_eur
                      FROM platba p
                     GROUP BY TRUNC(p.datum_platby, 'MM')
                     ORDER BY mesiac"""),
            new Report("3 – TOP 10 zákazníkov podľa výdavkov (RANK)",
                    "RANK() dáva rovnakým sumám rovnaké poradie a ďalšie poradie preskočí.", "13_analyticke_reporty.sql", """
                    SELECT poradie, zakaznik, pocet_platieb, vydavky_eur
                      FROM v_report3_top_zakaznici
                     ORDER BY poradie, zakaznik"""),
            new Report("4 – Najčastejšie vykonávané servisné úkony (ROW_NUMBER)",
                    "ROW_NUMBER() dáva každému úkonu jedinečné poradie; pri zhode rozhoduje tržba, potom názov.",
                    "13_analyticke_reporty.sql", """
                    SELECT poradie, nazov, pocet_vykonani, pocet_zakaziek, trzba_eur
                      FROM v_report4_najcastejsie_ukony
                     ORDER BY poradie"""),
            new Report("4b – Najčastejší úkon každého mechanika (ROW_NUMBER + PARTITION BY)",
                    "ROW_NUMBER() OVER (PARTITION BY mechanik ...) = 1 vyberie pre každého mechanika jeden úkon.",
                    "13_analyticke_reporty.sql", """
                    SELECT mechanik, nazov, pocet_vykonani
                      FROM (SELECT zm.meno || ' ' || zm.priezvisko AS mechanik, su.nazov, COUNT(*) AS pocet_vykonani,
                                   ROW_NUMBER() OVER (PARTITION BY zm.id_zamestnanec
                                                      ORDER BY COUNT(*) DESC, SUM(vu.mnozstvo * vu.jednotkova_cena) DESC,
                                                               su.nazov) AS poradie
                              FROM vykonany_ukon vu
                              JOIN servisny_ukon su ON su.id_ukon = vu.id_ukon
                              JOIN zamestnanec zm ON zm.id_zamestnanec = vu.id_zamestnanec
                             GROUP BY zm.id_zamestnanec, zm.meno, zm.priezvisko, su.nazov)
                     WHERE poradie = 1
                     ORDER BY mechanik"""),
            new Report("5 – Priemerná dĺžka opravy (AVG, dátumy)",
                    "Iba zákazky s vyplneným dátumom dokončenia; rozdiel dvoch DATE je počet dní.", "04_reports.sql", """
                    SELECT COUNT(*)                                             AS pocet_dokoncenych_oprav,
                           ROUND(AVG(z.datum_dokoncenia - z.datum_prijatia), 2) AS priemerna_dlzka_dni
                      FROM servisna_zakazka z
                     WHERE z.datum_dokoncenia IS NOT NULL"""),
            new Report("6 – Porovnanie mesačných tržieb (LAG)",
                    "LAG() vráti tržbu predchádzajúceho mesiaca; kalendár mesiacov dopĺňa mesiace bez platby (0 €).",
                    "13_analyticke_reporty.sql", """
                    SELECT mesiac, trzby_eur, predch_mesiac_eur, rozdiel_eur, zmena_percent
                      FROM v_report6_porovnanie_mesiacov
                     ORDER BY mesiac"""),
            new Report("7 – Kumulatívne tržby (SUM() OVER)",
                    "Priebežný súčet tržieb od začiatku a v rámci mesiaca (PARTITION BY mesiac).",
                    "13_analyticke_reporty.sql", """
                    SELECT den, denne_trzby, kumulativne_eur, kumulativne_v_mesiaci_eur
                      FROM v_report7_kumulativne_trzby
                     ORDER BY den"""),
            new Report("8 – Sedemdňový kĺzavý priemer tržieb (AVG() OVER, ROWS BETWEEN)",
                    "Kalendár dní (dni bez platby = 0 €), AVG(...) OVER (ORDER BY den ROWS BETWEEN 6 PRECEDING AND CURRENT ROW).",
                    "10_report8_klzavy_priemer.sql", """
                    SELECT den, pocet_platieb, denne_trzby, trzby_7_dni, klzavy_priemer_7d, dni_v_okne
                      FROM v_klzavy_priemer_trzieb
                     ORDER BY den"""),
            new Report("9 – Počet opráv podľa značky a modelu (JOIN, GROUP BY)",
                    "LEFT JOIN zahrnie aj modely bez opravy (počet 0).", "04_reports.sql", """
                    SELECT v.znacka, v.model, COUNT(z.id_zakazka) AS pocet_oprav
                      FROM vozidlo v
                      LEFT JOIN servisna_zakazka z ON z.id_vozidlo = v.id_vozidlo
                     GROUP BY v.znacka, v.model
                     ORDER BY pocet_oprav DESC, v.znacka, v.model"""),
            new Report("13 – Ročný nájazd vozidiel z histórie km (VARRAY)",
                    "Doplnkový výstup: kolekcia VOZIDLO.historia_km rozbalená cez TABLE(), agregácie nad prvkami VARRAY.",
                    "16_kolekcie_reporty.sql", """
                    SELECT spz, vozidlo, pocet_zaznamov, prvy_zaznam, posledny_zaznam, najazdene_km, rocny_najazd_km
                      FROM v_report13_najazd_vozidiel
                     ORDER BY rocny_najazd_km DESC NULLS LAST"""),
            new Report("13b – História tachometra s rozdielmi (VARRAY + LAG)",
                    "Každý záznam histórie km a rozdiel km a dní oproti predchádzajúcemu záznamu toho istého vozidla.",
                    "16_kolekcie_reporty.sql", """
                    SELECT spz, datum, km, zdroj, km_od_predch, dni_od_predch
                      FROM v_report13b_historia_km
                     ORDER BY spz, datum"""),
            new Report("14 – Kontrolný zoznam pri prijatí podľa bodu (NESTED TABLE)",
                    "Doplnkový výstup: vnorená tabuľka SERVISNA_ZAKAZKA.kontrolny_zoznam – počty OK / CHYBA / NEKONTROLOVANE.",
                    "16_kolekcie_reporty.sql", """
                    SELECT polozka, pocet_kontrol, ok, chyba, nekontrolovane, podiel_chyb_percent
                      FROM v_report14_kontrola_pri_prijati
                     ORDER BY chyba DESC, polozka"""),
            new Report("14b – Zistené chyby pri prijatí vozidla (NESTED TABLE)",
                    "Body kontrolného zoznamu so stavom CHYBA s poznámkou, zákazkou a vozidlom.",
                    "16_kolekcie_reporty.sql", """
                    SELECT id_zakazka, spz, datum_prijatia, stav_zakazky, polozka, poznamka
                      FROM v_report14b_chyby_pri_prijati
                     ORDER BY datum_prijatia, id_zakazka, polozka""")
    };

    private static final DateTimeFormatter DATUM = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final JComboBox<Report> cbReport = new JComboBox<>(REPORTY);
    private final JLabel lbPopis = new JLabel(" ");
    private final DefaultTableModel model = new DefaultTableModel() {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTextArea sqlArea = new JTextArea(9, 60);
    private final JLabel status = new JLabel(" ");

    public ReportyPanel() {
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        cbReport.addActionListener(e -> nacitaj());
        JButton btnNacitat = new JButton("Načítať");
        btnNacitat.addActionListener(e -> nacitaj());

        JPanel vyber = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        vyber.add(new JLabel("Report:"));
        vyber.add(cbReport);
        vyber.add(btnNacitat);
        JPanel top = new JPanel(new GridLayout(0, 1, 0, 4));
        top.add(vyber);
        top.add(lbPopis);

        sqlArea.setEditable(false);
        sqlArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane sqlScroll = new JScrollPane(sqlArea);
        sqlScroll.setBorder(BorderFactory.createTitledBorder("Použitý SQL dopyt"));

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(sqlScroll, BorderLayout.CENTER);
        bottom.add(status, BorderLayout.SOUTH);

        add(top, BorderLayout.NORTH);
        add(new JScrollPane(new JTable(model)), BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);
    }

    public void nacitaj() {
        Report r = (Report) cbReport.getSelectedItem();
        if (r == null) {
            return;
        }
        lbPopis.setText("<html>" + r.popis() + " <i>(skript " + r.skript() + ")</i></html>");
        sqlArea.setText(r.sql());
        sqlArea.setCaretPosition(0);
        try (Statement st = Db.get().createStatement(); ResultSet rs = st.executeQuery(r.sql())) {
            ResultSetMetaData md = rs.getMetaData();
            String[] hlavicka = new String[md.getColumnCount()];
            for (int i = 0; i < hlavicka.length; i++) {
                hlavicka[i] = md.getColumnLabel(i + 1).toLowerCase();
            }
            model.setDataVector(new Object[0][], hlavicka);
            while (rs.next()) {
                Object[] riadok = new Object[hlavicka.length];
                for (int i = 0; i < hlavicka.length; i++) {
                    int typ = md.getColumnType(i + 1);
                    if (typ == java.sql.Types.DATE || typ == java.sql.Types.TIMESTAMP) {
                        Timestamp t = rs.getTimestamp(i + 1);
                        riadok[i] = t == null ? "" : DATUM.format(t.toLocalDateTime());
                    } else {
                        riadok[i] = rs.getObject(i + 1);
                    }
                }
                model.addRow(riadok);
            }
            status.setText("Riadkov: " + model.getRowCount());
        } catch (SQLException ex) {
            status.setText("Report sa nepodarilo načítať.");
            String sprava = ex.getErrorCode() == 942
                    ? "Pohľad tohto reportu ešte nie je vytvorený.\nSpustite v SQL Developeri skript " + r.skript() + " (F5)."
                    : Chyby.sprava(ex);
            JOptionPane.showMessageDialog(this, sprava, "Reporty", JOptionPane.INFORMATION_MESSAGE);
        }
    }
}
