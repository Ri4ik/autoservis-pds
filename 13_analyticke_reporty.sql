-- AUTOSERVIS / CLEN 2 (implementoval clen 3) / CHECKPOINT 5T
-- Analyticke SQL reporty: 3 (RANK), 4 (ROW_NUMBER), 6 (LAG), 7 (SUM() OVER)
-- Spustit po 01_schema.sql a 02_demo_data.sql (F5 v SQL Developeri). Potrebne pravo CREATE VIEW.
-- Tento subor obsahuje len pohlady a SELECT-y; demo data nemeni.
--
-- DOHODA O METRIKE (rovnako ako v 04_reports.sql): vydavky zakaznika = skutocne zaplatene platby (PLATBA).

-- ============================================================
-- REPORT 3 - TOP 10 ZAKAZNIKOV PODLA CELKOVYCH VYDAVKOV (RANK)
-- ============================================================
-- RANK() dava rovnakym sumam rovnake poradie a dalsie poradie preskoci (1, 2, 3, 4, 4, 6 ...).
-- Zakaznici bez platby su zahrnuti so sumou 0 (LEFT JOIN).
CREATE OR REPLACE VIEW v_report3_top_zakaznici AS
SELECT poradie, id_zakaznik, zakaznik, pocet_platieb, vydavky_eur
FROM (
    SELECT RANK() OVER (ORDER BY NVL(SUM(p.suma), 0) DESC) AS poradie,
           za.id_zakaznik,
           za.meno || ' ' || za.priezvisko AS zakaznik,
           COUNT(p.id_platba)              AS pocet_platieb,
           NVL(SUM(p.suma), 0)             AS vydavky_eur
    FROM zakaznik za
    LEFT JOIN vozidlo v           ON v.id_zakaznik = za.id_zakaznik
    LEFT JOIN servisna_zakazka z  ON z.id_vozidlo = v.id_vozidlo
    LEFT JOIN faktura f           ON f.id_zakazka = z.id_zakazka
    LEFT JOIN platba p            ON p.id_faktura = f.id_faktura
    GROUP BY za.id_zakaznik, za.meno, za.priezvisko
)
WHERE poradie <= 10;

-- OCAKAVANE S DEMO DATAMI:
--   1 Martin Kovac 129 | 2 Jana Novotna 100 | 3 Tomas Polak 40 | 4 Peter Horvath 0 | 4 Lucia Bielikova 0
SELECT * FROM v_report3_top_zakaznici ORDER BY poradie, zakaznik;

-- ============================================================
-- REPORT 4 - NAJCASTEJSIE VYKONAVANE SERVISNE UKONY (ROW_NUMBER)
-- ============================================================
-- ROW_NUMBER() dava kazdemu riadku jedinecne poradie; pri rovnakom pocte rozhoduje vyssia trzba, potom nazov.
CREATE OR REPLACE VIEW v_report4_najcastejsie_ukony AS
SELECT ROW_NUMBER() OVER (ORDER BY COUNT(vu.id_vykonany_ukon) DESC,
                                   NVL(SUM(vu.mnozstvo * vu.jednotkova_cena), 0) DESC,
                                   su.nazov) AS poradie,
       su.id_ukon,
       su.nazov,
       COUNT(vu.id_vykonany_ukon)                     AS pocet_vykonani,
       COUNT(DISTINCT vu.id_zakazka)                  AS pocet_zakaziek,
       NVL(SUM(vu.mnozstvo * vu.jednotkova_cena), 0)  AS trzba_eur
FROM servisny_ukon su
LEFT JOIN vykonany_ukon vu ON vu.id_ukon = su.id_ukon
GROUP BY su.id_ukon, su.nazov;

-- OCAKAVANE S DEMO DATAMI: 1 Diagnostika motora (2x, 70 EUR), 2 Vymena brzdovych dosticiek (1x, 85), 3 Vymena oleja (1x, 55),
--                         4 Prezutie pneumatik (1x, 40), 5 Vymena vzduchoveho filtra (0x)
SELECT * FROM v_report4_najcastejsie_ukony ORDER BY poradie;

-- Doplnok: najcastejsi ukon kazdeho mechanika (ROW_NUMBER s PARTITION BY)
-- OCAKAVANE: Adam Urban - Vymena brzdovych dosticiek / Marek Slavik - Vymena motoroveho oleja (pri zhode rozhoduje trzba)
SELECT mechanik, nazov, pocet_vykonani
FROM (
    SELECT zm.meno || ' ' || zm.priezvisko AS mechanik,
           su.nazov,
           COUNT(*) AS pocet_vykonani,
           ROW_NUMBER() OVER (PARTITION BY zm.id_zamestnanec
                              ORDER BY COUNT(*) DESC, SUM(vu.mnozstvo * vu.jednotkova_cena) DESC, su.nazov) AS poradie
    FROM vykonany_ukon vu
    JOIN servisny_ukon su ON su.id_ukon = vu.id_ukon
    JOIN zamestnanec zm   ON zm.id_zamestnanec = vu.id_zamestnanec
    GROUP BY zm.id_zamestnanec, zm.meno, zm.priezvisko, su.nazov
)
WHERE poradie = 1
ORDER BY mechanik;

-- ============================================================
-- REPORT 6 - POROVNANIE MESACNYCH TRZIEB (LAG)
-- ============================================================
-- Kalendar mesiacov (CONNECT BY) zabezpeci, ze mesiac bez platby ma trzbu 0 a LAG porovnava naozaj s predchadzajucim mesiacom.
CREATE OR REPLACE VIEW v_report6_porovnanie_mesiacov AS
WITH rozsah AS (
    SELECT TRUNC(MIN(datum_platby), 'MM') AS prvy, TRUNC(MAX(datum_platby), 'MM') AS posledny FROM platba
),
mesiace AS (
    SELECT ADD_MONTHS(r.prvy, LEVEL - 1) AS mesiac
    FROM rozsah r
    CONNECT BY LEVEL <= MONTHS_BETWEEN(r.posledny, r.prvy) + 1
),
mesacne AS (
    SELECT m.mesiac, NVL(SUM(p.suma), 0) AS trzby_eur
    FROM mesiace m
    LEFT JOIN platba p ON TRUNC(p.datum_platby, 'MM') = m.mesiac
    GROUP BY m.mesiac
)
SELECT mesiac,
       trzby_eur,
       LAG(trzby_eur) OVER (ORDER BY mesiac)             AS predch_mesiac_eur,
       trzby_eur - LAG(trzby_eur) OVER (ORDER BY mesiac) AS rozdiel_eur,
       ROUND((trzby_eur - LAG(trzby_eur) OVER (ORDER BY mesiac))
             / NULLIF(LAG(trzby_eur) OVER (ORDER BY mesiac), 0) * 100, 1) AS zmena_percent
FROM mesacne;

-- OCAKAVANE S DEMO DATAMI: 2026-09 40 (bez predchadzajuceho) | 2026-10 229, predch. 40, rozdiel 189, +472.5 %
SELECT * FROM v_report6_porovnanie_mesiacov ORDER BY mesiac;

-- ============================================================
-- REPORT 7 - KUMULATIVNE TRZBY AUTOSERVISU (SUM() OVER)
-- ============================================================
CREATE OR REPLACE VIEW v_report7_kumulativne_trzby AS
SELECT den,
       denne_trzby,
       SUM(denne_trzby) OVER (ORDER BY den ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS kumulativne_eur,
       SUM(denne_trzby) OVER (PARTITION BY TRUNC(den, 'MM') ORDER BY den
                              ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW)            AS kumulativne_v_mesiaci_eur
FROM (
    SELECT TRUNC(datum_platby) AS den, SUM(suma) AS denne_trzby
    FROM platba
    GROUP BY TRUNC(datum_platby)
);

-- OCAKAVANE S DEMO DATAMI: 17.09. 40 / 40 / 40 | 03.10. 129 / 169 / 129 | 06.10. 100 / 269 / 229
SELECT * FROM v_report7_kumulativne_trzby ORDER BY den;

-- ============================================================
-- UPRATANIE (odkomentovat pri opakovanom spusteni - CREATE OR REPLACE to ale nepotrebuje)
-- ============================================================
-- DROP VIEW v_report3_top_zakaznici;
-- DROP VIEW v_report4_najcastejsie_ukony;
-- DROP VIEW v_report6_porovnanie_mesiacov;
-- DROP VIEW v_report7_kumulativne_trzby;
