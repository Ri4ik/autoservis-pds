-- AUTOSERVIS / CLEN 3 / EXPORTY XML A JSON (report 12 + doplnkove scenare)
-- Scenare, ktore pouziva aplikacia (zalozky XML a JSON):
--   1. servisna zakazka           - XML: pohlad V_XML_ZAKAZKA (09_xml_report_zakazka.sql), JSON: pohlad V_JSON_ZAKAZKA
--   2. zakaznici a ich vozidla    - XML aj JSON priamo dotazom (povodny vystup clena 2)
--   3. servisna historia vozidla  - pohlady V_XML_HISTORIA_VOZIDLA a V_JSON_HISTORIA_VOZIDLA
--                                   (historia km z VARRAY + zakazky z kolekcie reportu 10)
--   4. faktury, platby a zostatok - XML aj JSON priamo dotazom
-- Na konci: citanie JSON spat do riadkov (JSON_TABLE, JSON_VALUE, JSON_EXISTS).
-- Spustit po 09_xml_report_zakazka.sql a 11_report10_historia_vozidla.sql (pouziva funkciu HISTORIA_VOZIDLA).
-- Potrebne pravo CREATE VIEW; vyzaduje Oracle 19c+ (JSON_SERIALIZE).

-- ============================================================
-- 1. SERVISNA ZAKAZKA AKO JSON (rovnaka struktura ako XML report v 09_xml_report_zakazka.sql)
-- ============================================================
CREATE OR REPLACE VIEW v_json_zakazka AS
SELECT z.id_zakazka,
       JSON_OBJECT(
         'id'             VALUE z.id_zakazka,
         'stav'           VALUE z.stav,
         'datum_prijatia' VALUE TO_CHAR(z.datum_prijatia, 'YYYY-MM-DD'),
         'popis_problemu' VALUE z.popis_problemu,
         'stav_km'        VALUE z.stav_km,
         'zakaznik'       VALUE JSON_OBJECT('id' VALUE za.id_zakaznik, 'meno' VALUE za.meno, 'priezvisko' VALUE za.priezvisko,
                                            'telefon' VALUE za.telefon, 'email' VALUE za.email),
         'vozidlo'        VALUE JSON_OBJECT('id' VALUE v.id_vozidlo, 'spz' VALUE v.spz, 'vin' VALUE v.vin,
                                            'znacka' VALUE v.znacka, 'model' VALUE v.model, 'rok_vyroby' VALUE v.rok_vyroby),
         'kontrolny_zoznam' VALUE NVL((SELECT JSON_ARRAYAGG(
                                                 JSON_OBJECT('polozka' VALUE k.polozka, 'stav' VALUE k.stav,
                                                             'poznamka' VALUE k.poznamka)
                                                 RETURNING CLOB)
                                          FROM TABLE(z.kontrolny_zoznam) k), '[]') FORMAT JSON,
         'ukony'          VALUE NVL((SELECT JSON_ARRAYAGG(
                                              JSON_OBJECT('id' VALUE su.id_ukon, 'nazov' VALUE su.nazov,
                                                          'mnozstvo' VALUE vu.mnozstvo, 'jednotkova_cena' VALUE vu.jednotkova_cena,
                                                          'cena' VALUE vu.mnozstvo * vu.jednotkova_cena)
                                              ORDER BY vu.id_vykonany_ukon RETURNING CLOB)
                                       FROM vykonany_ukon vu JOIN servisny_ukon su ON su.id_ukon = vu.id_ukon
                                      WHERE vu.id_zakazka = z.id_zakazka), '[]') FORMAT JSON,
         'diely'          VALUE NVL((SELECT JSON_ARRAYAGG(
                                              JSON_OBJECT('id' VALUE nd.id_diel, 'katalogove_cislo' VALUE nd.katalogove_cislo,
                                                          'nazov' VALUE nd.nazov, 'mnozstvo' VALUE pd.mnozstvo,
                                                          'jednotkova_cena' VALUE pd.jednotkova_cena,
                                                          'cena' VALUE pd.mnozstvo * pd.jednotkova_cena)
                                              ORDER BY pd.id_pouzity_diel RETURNING CLOB)
                                       FROM pouzity_diel pd JOIN nahradny_diel nd ON nd.id_diel = pd.id_diel
                                      WHERE pd.id_zakazka = z.id_zakazka), '[]') FORMAT JSON,
         'celkova_cena'   VALUE JSON_OBJECT('ukony' VALUE NVL(u.suma, 0), 'diely' VALUE NVL(d.suma, 0),
                                            'spolu' VALUE NVL(u.suma, 0) + NVL(d.suma, 0), 'mena' VALUE 'EUR')
         RETURNING CLOB) AS dokument
FROM servisna_zakazka z
JOIN vozidlo v   ON v.id_vozidlo = z.id_vozidlo
JOIN zakaznik za ON za.id_zakaznik = v.id_zakaznik
LEFT JOIN (SELECT id_zakazka, SUM(mnozstvo * jednotkova_cena) suma FROM vykonany_ukon GROUP BY id_zakazka) u ON u.id_zakazka = z.id_zakazka
LEFT JOIN (SELECT id_zakazka, SUM(mnozstvo * jednotkova_cena) suma FROM pouzity_diel GROUP BY id_zakazka) d ON d.id_zakazka = z.id_zakazka;

-- OCAKAVANE: zakazka 1 - 1 ukon, 2 diely, celkova_cena.spolu = 129
SELECT JSON_SERIALIZE(dokument RETURNING CLOB PRETTY) AS json_report FROM v_json_zakazka WHERE id_zakazka = 1;

-- ============================================================
-- 2. ZAKAZNICI A ICH VOZIDLA (povodny vystup clena 2)
-- ============================================================
SELECT XMLSERIALIZE(DOCUMENT
         XMLELEMENT("zakaznici",
           XMLAGG(XMLELEMENT("zakaznik", XMLATTRIBUTES(z.id_zakaznik AS "id"),
                    XMLFOREST(z.meno AS "meno", z.priezvisko AS "priezvisko", z.telefon AS "telefon", z.email AS "email"),
                    (SELECT XMLELEMENT("vozidla", XMLAGG(XMLELEMENT("vozidlo",
                              XMLFOREST(v.spz AS "spz", v.znacka AS "znacka", v.model AS "model", v.rok_vyroby AS "rok_vyroby"))
                              ORDER BY v.id_vozidlo))
                       FROM vozidlo v WHERE v.id_zakaznik = z.id_zakaznik))
                  ORDER BY z.id_zakaznik))
         AS CLOB INDENT SIZE = 2) AS xml_zakaznici
FROM zakaznik z;

SELECT JSON_SERIALIZE(
         JSON_OBJECT('zakaznici' VALUE
           JSON_ARRAYAGG(JSON_OBJECT('id' VALUE z.id_zakaznik, 'meno' VALUE z.meno, 'priezvisko' VALUE z.priezvisko,
                                     'telefon' VALUE z.telefon, 'email' VALUE z.email,
                                     'vozidla' VALUE NVL((SELECT JSON_ARRAYAGG(JSON_OBJECT('spz' VALUE v.spz, 'znacka' VALUE v.znacka,
                                                                                           'model' VALUE v.model, 'rok_vyroby' VALUE v.rok_vyroby)
                                                                               ORDER BY v.id_vozidlo RETURNING CLOB)
                                                            FROM vozidlo v WHERE v.id_zakaznik = z.id_zakaznik), '[]') FORMAT JSON
                                     RETURNING CLOB)
                         ORDER BY z.id_zakaznik RETURNING CLOB)
           RETURNING CLOB)
         RETURNING CLOB PRETTY) AS json_zakaznici
FROM zakaznik z;

-- ============================================================
-- 3. SERVISNA HISTORIA VOZIDLA (kolekcia T_HISTORIA_TAB z reportu 10)
-- ============================================================
CREATE OR REPLACE VIEW v_xml_historia_vozidla AS
SELECT v.id_vozidlo,
       XMLELEMENT("historia_vozidla",
         XMLATTRIBUTES(v.id_vozidlo AS "id", v.spz AS "spz"),
         XMLFOREST(v.znacka AS "znacka", v.model AS "model", v.rok_vyroby AS "rok_vyroby",
                   za.meno || ' ' || za.priezvisko AS "majitel"),
         -- historia tachometra (VARRAY stlpec vozidla)
         XMLELEMENT("historia_km",
           (SELECT XMLAGG(XMLELEMENT("zaznam",
                            XMLATTRIBUTES(TO_CHAR(hk.datum, 'YYYY-MM-DD') AS "datum", hk.zdroj AS "zdroj"),
                            hk.km)
                          ORDER BY hk.datum)
              FROM TABLE(v.historia_km) hk)),
         XMLELEMENT("zakazky",
           (SELECT XMLAGG(XMLELEMENT("zakazka",
                            XMLATTRIBUTES(h.id_zakazka AS "id", h.stav AS "stav"),
                            XMLFOREST(TO_CHAR(h.datum_prijatia, 'YYYY-MM-DD') AS "datum_prijatia",
                                      TO_CHAR(h.datum_dokoncenia, 'YYYY-MM-DD') AS "datum_dokoncenia",
                                      h.stav_km AS "stav_km", h.mechanik AS "mechanik", h.ukony AS "ukony",
                                      h.suma_spolu AS "suma"))
                          ORDER BY h.datum_prijatia)
              FROM TABLE(historia_vozidla(v.id_vozidlo)) h)),
         XMLELEMENT("spolu", XMLATTRIBUTES('EUR' AS "mena"),
           (SELECT NVL(SUM(h.suma_spolu), 0) FROM TABLE(historia_vozidla(v.id_vozidlo)) h))
       ) AS dokument
FROM vozidlo v
JOIN zakaznik za ON za.id_zakaznik = v.id_zakaznik;

CREATE OR REPLACE VIEW v_json_historia_vozidla AS
SELECT v.id_vozidlo,
       JSON_OBJECT(
         'vozidlo' VALUE JSON_OBJECT('id' VALUE v.id_vozidlo, 'spz' VALUE v.spz, 'znacka' VALUE v.znacka,
                                     'model' VALUE v.model, 'rok_vyroby' VALUE v.rok_vyroby,
                                     'majitel' VALUE za.meno || ' ' || za.priezvisko),
         'historia_km' VALUE NVL((SELECT JSON_ARRAYAGG(
                                           JSON_OBJECT('datum' VALUE TO_CHAR(hk.datum, 'YYYY-MM-DD'), 'km' VALUE hk.km,
                                                       'zdroj' VALUE hk.zdroj)
                                           ORDER BY hk.datum RETURNING CLOB)
                                    FROM TABLE(v.historia_km) hk), '[]') FORMAT JSON,
         'zakazky' VALUE NVL((SELECT JSON_ARRAYAGG(
                                       JSON_OBJECT('id' VALUE h.id_zakazka, 'stav' VALUE h.stav,
                                                   'datum_prijatia' VALUE TO_CHAR(h.datum_prijatia, 'YYYY-MM-DD'),
                                                   'stav_km' VALUE h.stav_km, 'mechanik' VALUE h.mechanik,
                                                   'ukony' VALUE h.ukony, 'suma' VALUE h.suma_spolu)
                                       ORDER BY h.datum_prijatia RETURNING CLOB)
                                FROM TABLE(historia_vozidla(v.id_vozidlo)) h), '[]') FORMAT JSON,
         'spolu_eur' VALUE (SELECT NVL(SUM(h.suma_spolu), 0) FROM TABLE(historia_vozidla(v.id_vozidlo)) h)
         RETURNING CLOB) AS dokument
FROM vozidlo v
JOIN zakaznik za ON za.id_zakaznik = v.id_zakaznik;

-- OCAKAVANE: vozidlo 2 (Skoda Octavia) - 1 zakazka, spolu 168 EUR
SELECT XMLSERIALIZE(DOCUMENT dokument AS CLOB INDENT SIZE = 2) AS xml_historia FROM v_xml_historia_vozidla WHERE id_vozidlo = 2;
SELECT JSON_SERIALIZE(dokument RETURNING CLOB PRETTY) AS json_historia FROM v_json_historia_vozidla WHERE id_vozidlo = 2;

-- ============================================================
-- 4. FAKTURY, PLATBY A NEUHRADENY ZOSTATOK
-- ============================================================
-- OCAKAVANE: FA-2026-002 suma 168, zaplatene 100, zostatok 68; ostatne faktury zostatok 0
SELECT XMLSERIALIZE(DOCUMENT
         XMLELEMENT("faktury",
           XMLAGG(XMLELEMENT("faktura",
                    XMLATTRIBUTES(f.cislo_faktury AS "cislo", f.id_zakazka AS "zakazka"),
                    XMLFOREST(TO_CHAR(f.datum_vystavenia, 'YYYY-MM-DD') AS "vystavena",
                              TO_CHAR(f.datum_splatnosti, 'YYYY-MM-DD') AS "splatnost",
                              f.celkova_suma AS "suma",
                              NVL(pl.zaplatene, 0) AS "zaplatene",
                              f.celkova_suma - NVL(pl.zaplatene, 0) AS "zostatok"),
                    (SELECT XMLELEMENT("platby", XMLAGG(XMLELEMENT("platba", XMLATTRIBUTES(p.sposob AS "sposob"),
                              XMLFOREST(TO_CHAR(p.datum_platby, 'YYYY-MM-DD') AS "datum", p.suma AS "suma"))
                              ORDER BY p.datum_platby))
                       FROM platba p WHERE p.id_faktura = f.id_faktura))
                  ORDER BY f.datum_vystavenia, f.id_faktura))
         AS CLOB INDENT SIZE = 2) AS xml_faktury
FROM faktura f
LEFT JOIN (SELECT id_faktura, SUM(suma) zaplatene FROM platba GROUP BY id_faktura) pl ON pl.id_faktura = f.id_faktura;

SELECT JSON_SERIALIZE(
         JSON_OBJECT('faktury' VALUE
           JSON_ARRAYAGG(JSON_OBJECT('cislo' VALUE f.cislo_faktury, 'zakazka' VALUE f.id_zakazka,
                                     'vystavena' VALUE TO_CHAR(f.datum_vystavenia, 'YYYY-MM-DD'),
                                     'splatnost' VALUE TO_CHAR(f.datum_splatnosti, 'YYYY-MM-DD'),
                                     'suma' VALUE f.celkova_suma,
                                     'zaplatene' VALUE NVL(pl.zaplatene, 0),
                                     'zostatok' VALUE f.celkova_suma - NVL(pl.zaplatene, 0),
                                     'platby' VALUE NVL((SELECT JSON_ARRAYAGG(JSON_OBJECT('datum' VALUE TO_CHAR(p.datum_platby, 'YYYY-MM-DD'),
                                                                                          'suma' VALUE p.suma, 'sposob' VALUE p.sposob)
                                                                              ORDER BY p.datum_platby RETURNING CLOB)
                                                           FROM platba p WHERE p.id_faktura = f.id_faktura), '[]') FORMAT JSON
                                     RETURNING CLOB)
                         ORDER BY f.datum_vystavenia, f.id_faktura RETURNING CLOB)
           RETURNING CLOB)
         RETURNING CLOB PRETTY) AS json_faktury
FROM faktura f
LEFT JOIN (SELECT id_faktura, SUM(suma) zaplatene FROM platba GROUP BY id_faktura) pl ON pl.id_faktura = f.id_faktura;

-- ============================================================
-- 5. CITANIE JSON SPAT DO RIADKOV
-- ============================================================
-- a) JSON_VALUE - jednotlive hodnoty z dokumentu (OCAKAVANE: 1=129, 2=168, 3=53, 4=0, 5=40)
SELECT JSON_VALUE(dokument, '$.id' RETURNING NUMBER)                 AS id_zakazka,
       JSON_VALUE(dokument, '$.zakaznik.priezvisko')                 AS zakaznik,
       JSON_VALUE(dokument, '$.vozidlo.spz')                         AS spz,
       JSON_VALUE(dokument, '$.celkova_cena.spolu' RETURNING NUMBER) AS celkova_cena
FROM v_json_zakazka
ORDER BY 1;

-- b) JSON_TABLE - vsetky ukony zo vsetkych dokumentov ako riadky
SELECT j.id_zakazka, j.nazov, j.cena
FROM v_json_zakazka x,
     JSON_TABLE(x.dokument, '$'
                COLUMNS (id_zakazka NUMBER PATH '$.id',
                         NESTED PATH '$.ukony[*]'
                         COLUMNS (nazov VARCHAR2(120) PATH '$.nazov',
                                  cena  NUMBER        PATH '$.cena'))) j
WHERE j.nazov IS NOT NULL
ORDER BY j.id_zakazka, j.nazov;

-- c) JSON_EXISTS - zakazky, v ktorych sa pouzili brzdove dosticky (OCAKAVANE: zakazka 2)
SELECT id_zakazka
FROM v_json_zakazka
WHERE JSON_EXISTS(dokument, '$.diely[*]?(@.katalogove_cislo == "BRAKE-P-01")');

-- ============================================================
-- UPRATANIE (odkomentovat pri opakovanom spusteni - CREATE OR REPLACE to ale nepotrebuje)
-- ============================================================
-- DROP VIEW v_json_zakazka;
-- DROP VIEW v_xml_historia_vozidla;
-- DROP VIEW v_json_historia_vozidla;
