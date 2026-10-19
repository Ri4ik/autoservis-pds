-- AUTOSERVIS / CLEN 3 / CHECKPOINT 5T
-- XML report servisnej zakazky (cvicenie 4):
--   ID zakazky, udaje zakaznika, udaje vozidla, datum prijatia, kontrolny zoznam pri prijati (NESTED TABLE), zoznam servisnych ukonov,
--   pouzite nahradne diely a celkova cena opravy.
-- Pouzite funkcie: XMLROOT, XMLELEMENT, XMLATTRIBUTES, XMLFOREST, XMLAGG,
--                  EXTRACT, EXTRACTVALUE, EXISTSNODE, UPDATEXML, XMLTABLE.
-- Spustit po 01_schema.sql a 02_demo_data.sql (F5 v SQL Developeri). Potrebne pravo CREATE VIEW.
-- Skript spustajte raz; na opakovane spustenie pouzite cast UPRATANIE na konci suboru.

-- ============================================================
-- 1. POHLAD, KTORY GENERUJE XML DOKUMENT KU KAZDEJ ZAKAZKE
-- ============================================================
-- Zoznamy ukonov a dielov su v samostatnych poddotazoch, aby sa riadky navzajom nenasobili.
CREATE OR REPLACE VIEW v_xml_zakazka AS
SELECT z.id_zakazka,
       XMLROOT(
         XMLELEMENT("zakazka",
           XMLATTRIBUTES(z.id_zakazka AS "id", z.stav AS "stav"),
           XMLFOREST(
             TO_CHAR(z.datum_prijatia, 'YYYY-MM-DD')   AS "datum_prijatia",
             TO_CHAR(z.datum_dokoncenia, 'YYYY-MM-DD') AS "datum_dokoncenia",
             z.popis_problemu                          AS "popis_problemu",
             z.stav_km                                 AS "stav_km"
           ),
           XMLELEMENT("zakaznik",
             XMLATTRIBUTES(za.id_zakaznik AS "id"),
             XMLFOREST(za.meno AS "meno", za.priezvisko AS "priezvisko",
                       za.telefon AS "telefon", za.email AS "email")
           ),
           XMLELEMENT("vozidlo",
             XMLATTRIBUTES(v.id_vozidlo AS "id"),
             XMLFOREST(v.spz AS "spz", v.vin AS "vin", v.znacka AS "znacka",
                       v.model AS "model", v.rok_vyroby AS "rok_vyroby")
           ),
           -- kontrolny zoznam pri prijati (NESTED TABLE stlpec zakazky)
           XMLELEMENT("kontrolny_zoznam",
             (SELECT XMLAGG(XMLELEMENT("bod",
                              XMLATTRIBUTES(k.stav AS "stav"),
                              XMLFOREST(k.polozka AS "polozka", k.poznamka AS "poznamka")))
                FROM TABLE(z.kontrolny_zoznam) k)
           ),
           XMLELEMENT("ukony",
             XMLATTRIBUTES(NVL(u.pocet, 0) AS "pocet"),
             (SELECT XMLAGG(
                       XMLELEMENT("ukon",
                         XMLATTRIBUTES(vu.id_ukon AS "id"),
                         XMLFOREST(su.nazov AS "nazov",
                                   vu.mnozstvo AS "mnozstvo",
                                   vu.jednotkova_cena AS "jednotkova_cena",
                                   vu.mnozstvo * vu.jednotkova_cena AS "cena",
                                   zm.meno || ' ' || zm.priezvisko AS "mechanik"))
                       ORDER BY vu.id_vykonany_ukon)
                FROM vykonany_ukon vu
                JOIN servisny_ukon su ON su.id_ukon = vu.id_ukon
                LEFT JOIN zamestnanec zm ON zm.id_zamestnanec = vu.id_zamestnanec
               WHERE vu.id_zakazka = z.id_zakazka)
           ),
           XMLELEMENT("diely",
             XMLATTRIBUTES(NVL(d.pocet, 0) AS "pocet"),
             (SELECT XMLAGG(
                       XMLELEMENT("diel",
                         XMLATTRIBUTES(nd.id_diel AS "id", nd.katalogove_cislo AS "katalogove_cislo"),
                         XMLFOREST(nd.nazov AS "nazov",
                                   pd.mnozstvo AS "mnozstvo",
                                   pd.jednotkova_cena AS "jednotkova_cena",
                                   pd.mnozstvo * pd.jednotkova_cena AS "cena"))
                       ORDER BY pd.id_pouzity_diel)
                FROM pouzity_diel pd
                JOIN nahradny_diel nd ON nd.id_diel = pd.id_diel
               WHERE pd.id_zakazka = z.id_zakazka)
           ),
           XMLELEMENT("celkova_cena",
             XMLATTRIBUTES('EUR' AS "mena",
                           NVL(u.suma, 0) AS "ukony",
                           NVL(d.suma, 0) AS "diely"),
             NVL(u.suma, 0) + NVL(d.suma, 0)
           )
         ), VERSION '1.0'
       ) AS dokument
FROM servisna_zakazka z
JOIN vozidlo v   ON v.id_vozidlo = z.id_vozidlo
JOIN zakaznik za ON za.id_zakaznik = v.id_zakaznik
LEFT JOIN (SELECT id_zakazka, COUNT(*) pocet, SUM(mnozstvo * jednotkova_cena) suma
             FROM vykonany_ukon GROUP BY id_zakazka) u ON u.id_zakazka = z.id_zakazka
LEFT JOIN (SELECT id_zakazka, COUNT(*) pocet, SUM(mnozstvo * jednotkova_cena) suma
             FROM pouzity_diel GROUP BY id_zakazka) d ON d.id_zakazka = z.id_zakazka;

-- Report pre jednu zakazku (napr. c. 1) - citatelne odsadeny XML dokument
-- OCAKAVANE: 1 ukon (55), 2 diely (60 + 14), celkova_cena = 129
SELECT XMLSERIALIZE(DOCUMENT dokument AS CLOB INDENT SIZE = 2) AS xml_report
FROM v_xml_zakazka
WHERE id_zakazka = 1;

-- ============================================================
-- 2. TABULKA XML DOKUMENTOV (ako na cviceni: CREATE TABLE ... OF XMLTYPE)
-- ============================================================
CREATE TABLE xml_zakazky OF XMLTYPE;

INSERT INTO xml_zakazky
SELECT dokument FROM v_xml_zakazka;

COMMIT;

-- ============================================================
-- 3. DOTAZY NAD XML DOKUMENTAMI
-- ============================================================
-- a) Zoznam servisnych ukonov zo vsetkych dokumentov (EXTRACT)
SELECT EXTRACT(VALUE(x), '/zakazka/ukony') AS ukony
FROM xml_zakazky x;

-- b) Prehlad zakaziek - hodnoty vytiahnute z XML (EXTRACTVALUE)
-- OCAKAVANE: 1=129, 2=168, 3=53, 4=0, 5=40
SELECT EXTRACTVALUE(VALUE(x), '/zakazka/@id')                AS id_zakazka,
       EXTRACTVALUE(VALUE(x), '/zakazka/@stav')              AS stav,
       EXTRACTVALUE(VALUE(x), '/zakazka/zakaznik/priezvisko') AS zakaznik,
       EXTRACTVALUE(VALUE(x), '/zakazka/vozidlo/spz')        AS spz,
       EXTRACTVALUE(VALUE(x), '/zakazka/celkova_cena')       AS celkova_cena
FROM xml_zakazky x
ORDER BY TO_NUMBER(EXTRACTVALUE(VALUE(x), '/zakazka/@id'));

-- c) Zakazky s celkovou cenou nad 100 EUR (EXISTSNODE s podmienkou v XPath)
-- OCAKAVANE: zakazky 1 a 2
SELECT EXTRACTVALUE(VALUE(x), '/zakazka/@id') AS id_zakazka,
       EXTRACT(VALUE(x), '/zakazka/celkova_cena') AS cena
FROM xml_zakazky x
WHERE EXISTSNODE(VALUE(x), '/zakazka[celkova_cena > 100]') = 1;

-- d) Zakazky, v ktorych sa pouzili brzdove dosticky (filter podla atributu)
-- OCAKAVANE: zakazka 2
SELECT EXTRACTVALUE(VALUE(x), '/zakazka/@id') AS id_zakazka,
       EXTRACT(VALUE(x), '/zakazka/diely/diel[@katalogove_cislo="BRAKE-P-01"]') AS diel
FROM xml_zakazky x
WHERE EXISTSNODE(VALUE(x), '/zakazka/diely/diel[@katalogove_cislo="BRAKE-P-01"]') = 1;

-- e) Rozbalenie XML spat do riadkov (XMLTABLE) - vsetky ukony vsetkych zakaziek
SELECT TO_NUMBER(EXTRACTVALUE(VALUE(x), '/zakazka/@id')) AS id_zakazka, t.ukon, t.cena
FROM xml_zakazky x,
     XMLTABLE('/zakazka/ukony/ukon' PASSING VALUE(x)
              COLUMNS ukon VARCHAR2(120) PATH 'nazov',
                      cena NUMBER        PATH 'cena') t
ORDER BY 1;

-- ============================================================
-- 4. UPRAVA XML DOKUMENTU (UPDATEXML)
-- ============================================================
-- Zakazka 4 presla do opravy - zmena atributu stav v XML dokumente
UPDATE xml_zakazky x
SET VALUE(x) = UPDATEXML(VALUE(x), '/zakazka/@stav', 'V_OPRAVE')
WHERE EXISTSNODE(VALUE(x), '/zakazka[@id="4"]') = 1;

-- OCAKAVANE: stav = V_OPRAVE
SELECT EXTRACTVALUE(VALUE(x), '/zakazka/@stav') AS stav
FROM xml_zakazky x
WHERE EXISTSNODE(VALUE(x), '/zakazka[@id="4"]') = 1;

ROLLBACK;

-- ============================================================
-- UPRATANIE (odkomentovat pri opakovanom spusteni)
-- ============================================================
-- DROP TABLE xml_zakazky;
-- DROP VIEW v_xml_zakazka;
