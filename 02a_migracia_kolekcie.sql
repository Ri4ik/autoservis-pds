-- AUTOSERVIS / MIGRACIA NA KOLEKCIE V MODELI (clen 3)
-- IBA pre schemy vytvorene starsou verziou 01_schema.sql (bez stlpcov historia_km a kontrolny_zoznam).
-- Pri novej instalacii sa tento skript NESPUSTA - typy a stlpce uz vytvori 01_schema.sql a hodnoty 02_demo_data.sql.
--
-- Co robi:
--   1. vytvori typy T_STAV_KM, T_HISTORIA_KM (VARRAY), T_BOD_KONTROLY, T_KONTROLNY_ZOZNAM (NESTED TABLE)
--   2. prida stlpce VOZIDLO.historia_km a SERVISNA_ZAKAZKA.kontrolny_zoznam
--   3. doplni rovnake demo hodnoty ako nova verzia 02_demo_data.sql (len zaznamy s ID 1..5)
--   4. prekompiluje objekty, ktore zmena tabuliek zneplatnila (pohlady, procedury)
-- Spustit raz (F5 v SQL Developeri), hned po starsom 02_demo_data.sql a pred skriptami 08 a dalej.

-- ============================================================
-- 1. TYPY
-- ============================================================
CREATE OR REPLACE TYPE t_stav_km AS OBJECT (
 datum DATE,
 km    NUMBER(9),
 zdroj VARCHAR2(10)
);
/

CREATE OR REPLACE TYPE t_historia_km AS VARRAY(30) OF t_stav_km;
/

CREATE OR REPLACE TYPE t_bod_kontroly AS OBJECT (
 polozka  VARCHAR2(60),
 stav     VARCHAR2(15),
 poznamka VARCHAR2(200)
);
/

CREATE OR REPLACE TYPE t_kontrolny_zoznam AS TABLE OF t_bod_kontroly;
/

-- ============================================================
-- 2. NOVE STLPCE
-- ============================================================
ALTER TABLE vozidlo ADD (historia_km t_historia_km);

ALTER TABLE servisna_zakazka ADD (kontrolny_zoznam t_kontrolny_zoznam)
  NESTED TABLE kontrolny_zoznam STORE AS servisna_zakazka_kontrola_nt;

ALTER TABLE servisna_zakazka_kontrola_nt ADD CONSTRAINT ck_kontrola_stav
  CHECK (stav IN ('OK','CHYBA','NEKONTROLOVANE'));

-- ============================================================
-- 3. DEMO HODNOTY KOLEKCII (rovnake ako v 02_demo_data.sql)
-- ============================================================
UPDATE vozidlo SET historia_km =
  t_historia_km(
    t_stav_km(DATE '2022-05-10', 120000, 'STK'),
    t_stav_km(DATE '2024-05-12', 148000, 'STK'),
    t_stav_km(DATE '2026-10-01', 165000, 'SERVIS'))
WHERE id_vozidlo = 1;
UPDATE vozidlo SET historia_km =
  t_historia_km(
    t_stav_km(DATE '2023-03-01', 150000, 'STK'),
    t_stav_km(DATE '2025-03-03', 178000, 'STK'),
    t_stav_km(DATE '2026-10-04', 190000, 'SERVIS'))
WHERE id_vozidlo = 2;
UPDATE vozidlo SET historia_km =
  t_historia_km(
    t_stav_km(DATE '2024-06-20', 118000, 'STK'),
    t_stav_km(DATE '2026-10-10', 142000, 'SERVIS'))
WHERE id_vozidlo = 3;
UPDATE vozidlo SET historia_km =
  t_historia_km(
    t_stav_km(DATE '2022-11-15', 61000, 'STK'),
    t_stav_km(DATE '2024-11-18', 90000, 'STK'),
    t_stav_km(DATE '2026-10-15', 112000, 'SERVIS'))
WHERE id_vozidlo = 4;
UPDATE vozidlo SET historia_km =
  t_historia_km(
    t_stav_km(DATE '2023-08-01', 30000, 'PREDAJ'),
    t_stav_km(DATE '2025-08-20', 52000, 'ZAKAZNIK'),
    t_stav_km(DATE '2026-09-15', 65000, 'SERVIS'))
WHERE id_vozidlo = 5;

UPDATE servisna_zakazka SET kontrolny_zoznam =
  t_kontrolny_zoznam(
    t_bod_kontroly('Karoseria', 'OK', NULL),
    t_bod_kontroly('Pneumatiky', 'OK', NULL),
    t_bod_kontroly('Osvetlenie', 'OK', NULL),
    t_bod_kontroly('Kontrolky', 'OK', NULL),
    t_bod_kontroly('Doklady a kluce', 'OK', NULL),
    t_bod_kontroly('Hladina oleja', 'CHYBA', 'olej pod minimom'))
WHERE id_zakazka = 1;
UPDATE servisna_zakazka SET kontrolny_zoznam =
  t_kontrolny_zoznam(
    t_bod_kontroly('Karoseria', 'CHYBA', 'skrabance na zadnom narazniku'),
    t_bod_kontroly('Pneumatiky', 'OK', NULL),
    t_bod_kontroly('Osvetlenie', 'OK', NULL),
    t_bod_kontroly('Kontrolky', 'OK', NULL),
    t_bod_kontroly('Doklady a kluce', 'OK', NULL),
    t_bod_kontroly('Brzdove kotuce - vizualne', 'CHYBA', 'ryhy na prednych kotucoch'))
WHERE id_zakazka = 2;
UPDATE servisna_zakazka SET kontrolny_zoznam =
  t_kontrolny_zoznam(
    t_bod_kontroly('Karoseria', 'OK', NULL),
    t_bod_kontroly('Pneumatiky', 'OK', NULL),
    t_bod_kontroly('Osvetlenie', 'OK', NULL),
    t_bod_kontroly('Kontrolky', 'CHYBA', 'svieti kontrolka motora'),
    t_bod_kontroly('Doklady a kluce', 'OK', NULL))
WHERE id_zakazka = 3;
UPDATE servisna_zakazka SET kontrolny_zoznam =
  t_kontrolny_zoznam(
    t_bod_kontroly('Karoseria', 'NEKONTROLOVANE', NULL),
    t_bod_kontroly('Pneumatiky', 'NEKONTROLOVANE', NULL),
    t_bod_kontroly('Osvetlenie', 'CHYBA', 'nesvieti lave stretavacie svetlo'),
    t_bod_kontroly('Kontrolky', 'OK', NULL),
    t_bod_kontroly('Doklady a kluce', 'OK', 'technicky preukaz prilozeny'))
WHERE id_zakazka = 4;
UPDATE servisna_zakazka SET kontrolny_zoznam =
  t_kontrolny_zoznam(
    t_bod_kontroly('Karoseria', 'OK', NULL),
    t_bod_kontroly('Pneumatiky', 'OK', 'zimne pneumatiky v kufri'),
    t_bod_kontroly('Osvetlenie', 'OK', NULL),
    t_bod_kontroly('Kontrolky', 'OK', NULL),
    t_bod_kontroly('Doklady a kluce', 'OK', NULL))
WHERE id_zakazka = 5;

COMMIT;

-- ============================================================
-- 4. PREKOMPILOVANIE ZNEPLATNENYCH OBJEKTOV
-- ============================================================
BEGIN
    DBMS_UTILITY.COMPILE_SCHEMA(schema => USER, compile_all => FALSE);
END;
/

-- Kontrola: OCAKAVANE 5 vozidiel s historiou, 5 zakaziek s kontrolnym zoznamom, 0 neplatnych objektov projektu
-- (neplatne objekty z cviceni, napr. T_OSOBA, s projektom nesuvisia)
SELECT (SELECT COUNT(*) FROM vozidlo WHERE historia_km IS NOT NULL)               AS vozidla_s_historiou,
       (SELECT COUNT(*) FROM servisna_zakazka WHERE kontrolny_zoznam IS NOT NULL) AS zakazky_s_kontrolou,
       (SELECT COUNT(*) FROM user_objects WHERE status = 'INVALID')               AS neplatne_objekty
FROM dual;
