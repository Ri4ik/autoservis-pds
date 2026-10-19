-- AUTOSERVIS / CLEN 3 / REPORT 8
-- Sedemdnovy klzavy priemer trzieb (AVG() OVER, ROWS BETWEEN)
-- Spustit po 01_schema.sql a 02_demo_data.sql (F5 v SQL Developeri). Potrebne pravo CREATE VIEW.
--
-- DOHODA O METRIKE (rovnako ako v 04_reports.sql): 'Trzby' = skutocne prijate platby v tabulke PLATBA.
--
-- Preco kalendar dni: okno "ROWS BETWEEN 6 PRECEDING AND CURRENT ROW" pocita so 7 RIADKAMI, nie 7 dnami.
-- Ak by sme pocitali len dni, v ktorych prisla platba, okno by mohlo pokryvat aj niekolko tyzdnov.
-- Preto sa najprv vygeneruje suvisly kalendar dni (CONNECT BY LEVEL) a dni bez platby maju trzbu 0.

-- ============================================================
-- 1. POHLAD S KLZAVYM PRIEMEROM (pouziva ho aj aplikacia)
-- ============================================================
CREATE OR REPLACE VIEW v_klzavy_priemer_trzieb AS
WITH rozsah AS (
    SELECT MIN(TRUNC(datum_platby)) AS prvy_den,
           MAX(TRUNC(datum_platby)) AS posledny_den
    FROM platba
),
kalendar AS (
    SELECT r.prvy_den + LEVEL - 1 AS den
    FROM rozsah r
    CONNECT BY LEVEL <= r.posledny_den - r.prvy_den + 1
),
denne_trzby AS (
    SELECT TRUNC(datum_platby) AS den,
           COUNT(*)            AS pocet_platieb,
           SUM(suma)           AS trzby
    FROM platba
    GROUP BY TRUNC(datum_platby)
)
SELECT k.den,
       NVL(d.pocet_platieb, 0) AS pocet_platieb,
       NVL(d.trzby, 0)         AS denne_trzby,
       SUM(NVL(d.trzby, 0)) OVER (ORDER BY k.den ROWS BETWEEN 6 PRECEDING AND CURRENT ROW) AS trzby_7_dni,
       ROUND(AVG(NVL(d.trzby, 0)) OVER (ORDER BY k.den ROWS BETWEEN 6 PRECEDING AND CURRENT ROW), 2) AS klzavy_priemer_7d,
       COUNT(*) OVER (ORDER BY k.den ROWS BETWEEN 6 PRECEDING AND CURRENT ROW) AS dni_v_okne
FROM kalendar k
LEFT JOIN denne_trzby d ON d.den = k.den;

-- ============================================================
-- 2. REPORT 8 - KLZAVY PRIEMER ZA KAZDY DEN
-- ============================================================
-- Prvych 6 dni ma neuplne okno (DNI_V_OKNE < 7), priemer sa pocita z dostupnych dni.
-- OCAKAVANE S DEMO DATAMI (platby 17.09. 40 EUR, 03.10. 129 EUR, 06.10. 100 EUR; 20 dni):
--   2026-09-17: 40      (1 den v okne)
--   2026-09-23: 5.71    (40 / 7)
--   2026-09-24: 0       (platba 17.09. uz vypadla z okna)
--   2026-10-03: 18.43   (129 / 7)
--   2026-10-06: 32.71   ((129 + 100) / 7)
SELECT den,
       pocet_platieb,
       denne_trzby,
       trzby_7_dni,
       klzavy_priemer_7d,
       dni_v_okne
FROM v_klzavy_priemer_trzieb
ORDER BY den;

-- ============================================================
-- 3. DOPLNOK - DNI S NAJVYSSIM KLZAVYM PRIEMEROM
-- ============================================================
-- Iba plne 7-dnove okna. OCAKAVANE: 2026-10-06 (32.71), 2026-10-03 (18.43), 2026-10-04 (18.43)
SELECT den, klzavy_priemer_7d
FROM v_klzavy_priemer_trzieb
WHERE dni_v_okne = 7
ORDER BY klzavy_priemer_7d DESC, den
FETCH FIRST 3 ROWS ONLY;

-- ============================================================
-- UPRATANIE (odkomentovat pri opakovanom spusteni - CREATE OR REPLACE VIEW to ale nepotrebuje)
-- ============================================================
-- DROP VIEW v_klzavy_priemer_trzieb;
