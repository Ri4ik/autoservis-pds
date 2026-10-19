-- AUTOSERVIS / CLEN 3 / KOLEKCIE V MODELI - REPORTY A PRACA S KOLEKCIAMI
--   VOZIDLO.historia_km               VARRAY(30) OF t_stav_km      (datum, km, zdroj)
--   SERVISNA_ZAKAZKA.kontrolny_zoznam NESTED TABLE OF t_bod_kontroly (polozka, stav, poznamka)
-- Doplnkove vystupy 13 a 14 (nad povinnych 12):
--   13  - rocny najazd vozidiel z historie tachometra (VARRAY, TABLE(), agregacie)
--   13b - historia tachometra s rozdielom oproti predchadzajucemu zaznamu (VARRAY + LAG)
--   14  - vysledky kontrolneho zoznamu pri prijati podla bodu (NESTED TABLE, agregacie)
--   14b - zistene chyby pri prijati (NESTED TABLE, detail)
-- Na konci: ukazka DML nad vnorenou tabulkou (INSERT / UPDATE / DELETE cez TABLE()), CARDINALITY, VARRAY v PL/SQL.
-- Spustit po 01_schema.sql, 02_demo_data.sql (alebo 02a_migracia_kolekcie.sql) a 12_plsql_zakazky.sql.

SET SERVEROUTPUT ON;

-- ============================================================
-- REPORT 13 - ROCNY NAJAZD VOZIDIEL (VARRAY)
-- ============================================================
CREATE OR REPLACE VIEW v_report13_najazd_vozidiel AS
SELECT v.id_vozidlo,
       v.spz,
       v.znacka || ' ' || v.model                     AS vozidlo,
       COUNT(*)                                        AS pocet_zaznamov,
       MIN(h.datum)                                    AS prvy_zaznam,
       MAX(h.datum)                                    AS posledny_zaznam,
       MAX(h.km) - MIN(h.km)                           AS najazdene_km,
       ROUND((MAX(h.km) - MIN(h.km)) / NULLIF(MAX(h.datum) - MIN(h.datum), 0) * 365) AS rocny_najazd_km
FROM vozidlo v, TABLE(v.historia_km) h
GROUP BY v.id_vozidlo, v.spz, v.znacka, v.model;

-- OCAKAVANE S DEMO DATAMI: 5 vozidiel; napr. Golf BA123AA 3 zaznamy, najazdene 45000 km
SELECT spz, vozidlo, pocet_zaznamov, prvy_zaznam, posledny_zaznam, najazdene_km, rocny_najazd_km
FROM v_report13_najazd_vozidiel
ORDER BY rocny_najazd_km DESC NULLS LAST;

-- ============================================================
-- REPORT 13b - HISTORIA TACHOMETRA S ROZDIELMI (VARRAY + LAG)
-- ============================================================
CREATE OR REPLACE VIEW v_report13b_historia_km AS
SELECT v.id_vozidlo,
       v.spz,
       h.datum,
       h.km,
       h.zdroj,
       h.km - LAG(h.km) OVER (PARTITION BY v.id_vozidlo ORDER BY h.datum)       AS km_od_predch,
       h.datum - LAG(h.datum) OVER (PARTITION BY v.id_vozidlo ORDER BY h.datum) AS dni_od_predch
FROM vozidlo v, TABLE(v.historia_km) h;

-- OCAKAVANE: BA123AA 2022-05-10 120000 | 2024-05-12 148000 (+28000) | 2026-10-01 165000 (+17000)
SELECT spz, datum, km, zdroj, km_od_predch, dni_od_predch
FROM v_report13b_historia_km
ORDER BY spz, datum;

-- ============================================================
-- REPORT 14 - KONTROLNY ZOZNAM PRI PRIJATI PODLA BODU (NESTED TABLE)
-- ============================================================
CREATE OR REPLACE VIEW v_report14_kontrola_pri_prijati AS
SELECT k.polozka,
       COUNT(*)                                             AS pocet_kontrol,
       SUM(CASE WHEN k.stav = 'OK' THEN 1 ELSE 0 END)             AS ok,
       SUM(CASE WHEN k.stav = 'CHYBA' THEN 1 ELSE 0 END)          AS chyba,
       SUM(CASE WHEN k.stav = 'NEKONTROLOVANE' THEN 1 ELSE 0 END) AS nekontrolovane,
       ROUND(100 * SUM(CASE WHEN k.stav = 'CHYBA' THEN 1 ELSE 0 END) / COUNT(*), 1) AS podiel_chyb_percent
FROM servisna_zakazka z, TABLE(z.kontrolny_zoznam) k
GROUP BY k.polozka;

-- OCAKAVANE S DEMO DATAMI: 7 bodov; Karoseria 5 kontrol / 1 chyba / 1 nekontrolovane; Kontrolky 1 chyba; Osvetlenie 1 chyba
SELECT * FROM v_report14_kontrola_pri_prijati ORDER BY chyba DESC, polozka;

-- ============================================================
-- REPORT 14b - ZISTENE CHYBY PRI PRIJATI (NESTED TABLE, DETAIL)
-- ============================================================
CREATE OR REPLACE VIEW v_report14b_chyby_pri_prijati AS
SELECT z.id_zakazka,
       v.spz,
       z.datum_prijatia,
       z.stav AS stav_zakazky,
       k.polozka,
       k.poznamka
FROM servisna_zakazka z
JOIN vozidlo v ON v.id_vozidlo = z.id_vozidlo,
     TABLE(z.kontrolny_zoznam) k
WHERE k.stav = 'CHYBA';

-- OCAKAVANE: 5 chyb - zakazka 1 hladina oleja, 2 karoseria a brzdove kotuce, 3 kontrolky, 4 osvetlenie
SELECT * FROM v_report14b_chyby_pri_prijati ORDER BY datum_prijatia, id_zakazka, polozka;

-- ============================================================
-- PRACA S KOLEKCIAMI (na konci ROLLBACK - demo data sa nezmenia)
-- ============================================================
-- a) pocet prvkov kolekcie bez rozbalenia (CARDINALITY)
SELECT z.id_zakazka,
       CARDINALITY(z.kontrolny_zoznam) AS pocet_bodov,
       (SELECT COUNT(*) FROM TABLE(z.kontrolny_zoznam) k WHERE k.stav = 'CHYBA') AS pocet_chyb
FROM servisna_zakazka z
ORDER BY z.id_zakazka;

-- b) body navyse oproti standardnemu zoznamu (vnorena tabulka zo stlpca vs. kolekcia z funkcie)
-- OCAKAVANE: zakazka 1 Hladina oleja, zakazka 2 Brzdove kotuce - vizualne
SELECT z.id_zakazka, k.polozka AS bod_navyse, k.stav
FROM servisna_zakazka z, TABLE(z.kontrolny_zoznam) k
WHERE k.polozka NOT IN (SELECT s.polozka FROM TABLE(standardny_kontrolny_zoznam()) s)
ORDER BY z.id_zakazka;

-- c) DML priamo nad vnorenou tabulkou jednej zakazky
-- pridanie bodu
INSERT INTO TABLE(SELECT kontrolny_zoznam FROM servisna_zakazka WHERE id_zakazka = 4)
VALUES (t_bod_kontroly('Stierace', 'OK', NULL));
-- zmena stavu bodu
UPDATE TABLE(SELECT kontrolny_zoznam FROM servisna_zakazka WHERE id_zakazka = 4) k
SET k.stav = 'OK', k.poznamka = 'skontrolovane mechanikom'
WHERE k.polozka = 'Karoseria';
-- vymazanie bodu
DELETE FROM TABLE(SELECT kontrolny_zoznam FROM servisna_zakazka WHERE id_zakazka = 4) k
WHERE k.polozka = 'Pneumatiky';

-- OCAKAVANE: Karoseria OK, Pneumatiky chyba, pribudli Stierace (spolu 5 bodov)
SELECT k.polozka, k.stav, k.poznamka
FROM servisna_zakazka z, TABLE(z.kontrolny_zoznam) k
WHERE z.id_zakazka = 4;

-- d) VARRAY sa meni ako celok - pridanie zaznamu procedurou PRIDAJ_STAV_KM (12_plsql_zakazky.sql)
EXEC pridaj_stav_km(3, SYSDATE, 145500, 'ZAKAZNIK');

-- OCAKAVANE: BMW TT789CC ma 3 zaznamy, posledny 145500 km (ZAKAZNIK)
SELECT h.datum, h.km, h.zdroj
FROM vozidlo v, TABLE(v.historia_km) h
WHERE v.id_vozidlo = 3;

-- e) VARRAY v PL/SQL - prechod prvkami a vypocet priemerneho denneho najazdu
DECLARE
    v_historia t_historia_km;
BEGIN
    SELECT historia_km INTO v_historia FROM vozidlo WHERE id_vozidlo = 1;
    DBMS_OUTPUT.PUT_LINE('Historia km vozidla 1: ' || v_historia.COUNT || ' z max. ' || v_historia.LIMIT || ' zaznamov');
    FOR i IN 2 .. v_historia.COUNT LOOP
        DBMS_OUTPUT.PUT_LINE('  ' || TO_CHAR(v_historia(i).datum, 'DD.MM.YYYY') || ': +'
                             || (v_historia(i).km - v_historia(i - 1).km) || ' km, priemerne '
                             || ROUND((v_historia(i).km - v_historia(i - 1).km)
                                      / (v_historia(i).datum - v_historia(i - 1).datum), 1) || ' km/den');
    END LOOP;
END;
/

ROLLBACK;

-- ============================================================
-- UPRATANIE (odkomentovat pri opakovanom spusteni - CREATE OR REPLACE to ale nepotrebuje)
-- ============================================================
-- DROP VIEW v_report13_najazd_vozidiel;
-- DROP VIEW v_report13b_historia_km;
-- DROP VIEW v_report14_kontrola_pri_prijati;
-- DROP VIEW v_report14b_chyby_pri_prijati;
