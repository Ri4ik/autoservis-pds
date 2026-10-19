package sk.autoservis;

import javax.swing.*;
import java.awt.*;

/**
 * Objektové typy a kolekcie Oracle na jednom mieste:
 * T_SERVISNA_POLOZKA (konštruktor, 2 funkcie, MAP, objektový atribút tabuľky – detail zákazky, report 11),
 * T_ZAKAZNIK (objekty vytvorené v dopyte), VARRAY história km vozidla a NESTED TABLE kontrolný zoznam zákazky.
 */
public class ObjektyPanel extends JPanel {

    private final PolozkyPanel polozky = new PolozkyPanel();
    private final HistoriaKmPanel historiaKm = new HistoriaKmPanel();
    private final KontrolnyZoznamPanel kontrola = new KontrolnyZoznamPanel();
    private final JTabbedPane vnutorne = new JTabbedPane();

    public ObjektyPanel() {
        setLayout(new BorderLayout());
        vnutorne.addTab("Objekt T_SERVISNA_POLOZKA (report 11)", polozky);
        vnutorne.addTab("Objekt T_ZAKAZNIK", new ObjectPanel());
        vnutorne.addTab("VARRAY – história km vozidla", historiaKm);
        vnutorne.addTab("NESTED TABLE – kontrolný zoznam zákazky", kontrola);
        vnutorne.addChangeListener(e -> nacitaj());
        add(vnutorne, BorderLayout.CENTER);
    }

    public void nacitaj() {
        Component vybrana = vnutorne.getSelectedComponent();
        if (vybrana == polozky) {
            polozky.nacitaj();
        } else if (vybrana == historiaKm) {
            historiaKm.nacitaj();
        } else if (vybrana == kontrola) {
            kontrola.nacitaj();
        }
    }
}
