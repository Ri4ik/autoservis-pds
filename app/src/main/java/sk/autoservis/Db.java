package sk.autoservis;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/** Jediné spojenie s databázou zdieľané celou aplikáciou. */
public final class Db {

    private static Connection connection;

    private Db() {
    }

    public static void connect(String url, String user, String password) throws SQLException {
        close();
        DriverManager.setLoginTimeout(10);
        Connection c = DriverManager.getConnection(url, user, password);
        // Každý príkaz sa potvrdí hneď (jednoduché CRUD operácie, žiadne transakcie naprieč príkazmi).
        c.setAutoCommit(true);
        connection = c;
    }

    public static boolean jePripojeny() {
        return connection != null;
    }

    public static Connection get() {
        if (connection == null) {
            throw new IllegalStateException("Aplikácia nie je pripojená k databáze.");
        }
        return connection;
    }

    /** Akcia nad databázou, ktorá môže vyhodiť SQLException. */
    @FunctionalInterface
    public interface SqlAkcia {
        void vykonaj(Connection c) throws SQLException;
    }

    /**
     * Vykoná akciu (napr. volanie PL/SQL procedúry) a vráti všetko, čo vypísala cez DBMS_OUTPUT
     * (ENABLE → akcia → GET_LINE v cykle → DISABLE).
     */
    public static String zachytVystup(SqlAkcia akcia) throws SQLException {
        Connection c = get();
        try (java.sql.Statement st = c.createStatement()) {
            st.execute("BEGIN DBMS_OUTPUT.ENABLE(NULL); END;");
        }
        try {
            akcia.vykonaj(c);
            StringBuilder sb = new StringBuilder();
            try (java.sql.CallableStatement cs = c.prepareCall("BEGIN DBMS_OUTPUT.GET_LINE(?, ?); END;")) {
                cs.registerOutParameter(1, java.sql.Types.VARCHAR);
                cs.registerOutParameter(2, java.sql.Types.INTEGER);
                while (true) {
                    cs.execute();
                    if (cs.getInt(2) != 0) {
                        break;
                    }
                    String riadok = cs.getString(1);
                    sb.append(riadok == null ? "" : riadok).append('\n');
                }
            }
            return sb.toString();
        } finally {
            try (java.sql.Statement st = c.createStatement()) {
                st.execute("BEGIN DBMS_OUTPUT.DISABLE; END;");
            }
        }
    }

    public static void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // nič s tým nespravíme
            }
            connection = null;
        }
    }
}
