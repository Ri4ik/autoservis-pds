-- AUTOSERVIS / CLEN 2 (implementoval clen 3) / CHECKPOINT 5T
-- PL/SQL logika servisnych zakaziek (cvicenie 2):
--   * VYTVOR_SERVISNU_ZAKAZKU  - vytvori zakazku v pociatocnom stave PRIJATA
--   * ZMEN_STAV_ZAKAZKY        - zmeni stav, kontroluje existenciu zakazky a povoleny prechod stavu
--   * VYPIS_OTVORENE_ZAKAZKY   - explicitny kurzor, %ROWTYPE, parametrizovany kurzor, DBMS_OUTPUT
--   * vynimky: EXCEPTION, RAISE_APPLICATION_ERROR, NO_DATA_FOUND, vlastna vynimka s PRAGMA EXCEPTION_INIT
--   * kolekcie v modeli: STANDARDNY_KONTROLNY_ZOZNAM (NESTED TABLE), PRIDAJ_STAV_KM (VARRAY - EXTEND, LIMIT, posun)
-- Spustit po 01_schema.sql a 02_demo_data.sql (F5 v SQL Developeri). Potrebne pravo CREATE PROCEDURE.
-- Procedury nerobia COMMIT - o potvrdeni rozhoduje volajuci (testy na konci suboru koncia ROLLBACK).
--
-- KODY CHYB:
--   -20101 nespravne vstupne udaje            -20104 servisna zakazka neexistuje
--   -20102 vozidlo neexistuje                 -20105 neznamy stav zakazky
--   -20103 zamestnanec neexistuje/neaktivny   -20106 nepovolena zmena stavu
--                                             -20107 zakazku bez vykonaneho ukonu nemozno dokoncit
--   -20111 nespravny datum v historii km      -20112 nizsi stav km (stocenie)   -20113 neznamy zdroj zaznamu km
--
-- POVOLENE PRECHODY STAVOV:
--   PRIJATA -> V_OPRAVE -> DOKONCENA -> ODOVZDANA
--   PRIJATA -> ZRUSENA,  V_OPRAVE -> ZRUSENA

SET SERVEROUTPUT ON;

-- ============================================================
-- 0. POMOCNE PODPROGRAMY PRE KOLEKCIE V MODELI
-- ============================================================
-- Standardny kontrolny zoznam pri prijati vozidla (NESTED TABLE) - vsetky body zatial nekontrolovane.
CREATE OR REPLACE FUNCTION standardny_kontrolny_zoznam RETURN t_kontrolny_zoznam
IS
BEGIN
    RETURN t_kontrolny_zoznam(
        t_bod_kontroly('Karoseria',       'NEKONTROLOVANE', NULL),
        t_bod_kontroly('Pneumatiky',      'NEKONTROLOVANE', NULL),
        t_bod_kontroly('Osvetlenie',      'NEKONTROLOVANE', NULL),
        t_bod_kontroly('Kontrolky',       'NEKONTROLOVANE', NULL),
        t_bod_kontroly('Doklady a kluce', 'NEKONTROLOVANE', NULL));
END;
/

-- Prida zaznam do historie tachometra vozidla (VARRAY t_historia_km, max. 30 prvkov).
-- Kontroluje chronologiu a rastuci stav km; pri plnom poli sa najstarsi zaznam vyradi (posun prvkov).
-- KODY CHYB: -20102 vozidlo neexistuje, -20111 nespravny datum, -20112 nizsi stav km, -20113 neznamy zdroj
CREATE OR REPLACE PROCEDURE pridaj_stav_km (
    p_id_vozidlo IN vozidlo.id_vozidlo%TYPE,
    p_datum      IN DATE,
    p_km         IN NUMBER,
    p_zdroj      IN VARCHAR2 DEFAULT 'SERVIS'
)
IS
    v_historia t_historia_km;
    v_zdroj    VARCHAR2(10) := UPPER(TRIM(p_zdroj));
BEGIN
    IF p_km IS NULL OR p_km < 0 THEN
        RAISE_APPLICATION_ERROR(-20112, 'Stav tachometra musi byt nezaporne cislo.');
    END IF;
    IF p_datum IS NULL OR p_datum > SYSDATE THEN
        RAISE_APPLICATION_ERROR(-20111, 'Datum zaznamu musi byt vyplneny a nesmie byt v buducnosti.');
    END IF;
    IF v_zdroj IS NULL OR v_zdroj NOT IN ('STK', 'SERVIS', 'PREDAJ', 'ZAKAZNIK') THEN
        RAISE_APPLICATION_ERROR(-20113, 'Neznamy zdroj zaznamu: ' || NVL(p_zdroj, '(prazdny)') || ' (STK, SERVIS, PREDAJ, ZAKAZNIK).');
    END IF;

    BEGIN
        SELECT historia_km INTO v_historia FROM vozidlo WHERE id_vozidlo = p_id_vozidlo FOR UPDATE;
    EXCEPTION
        WHEN NO_DATA_FOUND THEN
            RAISE_APPLICATION_ERROR(-20102, 'Vozidlo s ID ' || p_id_vozidlo || ' neexistuje.');
    END;

    IF v_historia IS NULL THEN
        v_historia := t_historia_km();
    END IF;

    IF v_historia.COUNT > 0 THEN
        IF TRUNC(p_datum) < TRUNC(v_historia(v_historia.COUNT).datum) THEN
            RAISE_APPLICATION_ERROR(-20111, 'Datum ' || TO_CHAR(p_datum, 'DD.MM.YYYY') || ' je skorsi ako posledny zaznam ('
                                    || TO_CHAR(v_historia(v_historia.COUNT).datum, 'DD.MM.YYYY') || ').');
        END IF;
        IF p_km < v_historia(v_historia.COUNT).km THEN
            RAISE_APPLICATION_ERROR(-20112, 'Stav ' || p_km || ' km je nizsi ako posledny zaznam ('
                                    || v_historia(v_historia.COUNT).km || ' km) - podozrenie na stocenie tachometra.');
        END IF;
    END IF;

    IF v_historia.COUNT = v_historia.LIMIT THEN
        -- pole je plne: posun o jeden prvok, najstarsi zaznam vypadne
        FOR i IN 1 .. v_historia.COUNT - 1 LOOP
            v_historia(i) := v_historia(i + 1);
        END LOOP;
    ELSE
        v_historia.EXTEND;
    END IF;
    v_historia(v_historia.COUNT) := t_stav_km(TRUNC(p_datum), p_km, v_zdroj);

    UPDATE vozidlo SET historia_km = v_historia WHERE id_vozidlo = p_id_vozidlo;
END;
/

-- ============================================================
-- 1. VYTVOR_SERVISNU_ZAKAZKU
-- ============================================================
CREATE OR REPLACE PROCEDURE vytvor_servisnu_zakazku (
    p_id_vozidlo      IN  servisna_zakazka.id_vozidlo%TYPE,
    p_datum_prijatia  IN  servisna_zakazka.datum_prijatia%TYPE,
    p_popis_problemu  IN  servisna_zakazka.popis_problemu%TYPE,
    p_id_zamestnanec  IN  servisna_zakazka.id_zamestnanec%TYPE DEFAULT NULL,
    p_stav_km         IN  servisna_zakazka.stav_km%TYPE DEFAULT NULL,
    p_id_zakazka      OUT servisna_zakazka.id_zakazka%TYPE
)
IS
    v_id_vozidlo   vozidlo.id_vozidlo%TYPE;
    v_aktivny      zamestnanec.aktivny%TYPE;
    v_max_km       servisna_zakazka.stav_km%TYPE;
BEGIN
    -- kontrola vstupnych udajov
    IF p_popis_problemu IS NULL OR TRIM(p_popis_problemu) IS NULL THEN
        RAISE_APPLICATION_ERROR(-20101, 'Popis problemu musi byt vyplneny.');
    END IF;
    IF p_datum_prijatia IS NULL OR p_datum_prijatia > SYSDATE THEN
        RAISE_APPLICATION_ERROR(-20101, 'Datum prijatia musi byt vyplneny a nesmie byt v buducnosti.');
    END IF;
    IF p_stav_km < 0 THEN
        RAISE_APPLICATION_ERROR(-20101, 'Stav tachometra nemoze byt zaporny.');
    END IF;

    -- existencia vozidla (NO_DATA_FOUND sa preklada na vlastnu chybu)
    BEGIN
        SELECT id_vozidlo INTO v_id_vozidlo FROM vozidlo WHERE id_vozidlo = p_id_vozidlo;
    EXCEPTION
        WHEN NO_DATA_FOUND THEN
            RAISE_APPLICATION_ERROR(-20102, 'Vozidlo s ID ' || p_id_vozidlo || ' neexistuje.');
    END;

    -- zamestnanec je nepovinny, ale ak je zadany, musi existovat a byt aktivny
    IF p_id_zamestnanec IS NOT NULL THEN
        BEGIN
            SELECT aktivny INTO v_aktivny FROM zamestnanec WHERE id_zamestnanec = p_id_zamestnanec;
        EXCEPTION
            WHEN NO_DATA_FOUND THEN
                RAISE_APPLICATION_ERROR(-20103, 'Zamestnanec s ID ' || p_id_zamestnanec || ' neexistuje.');
        END;
        IF v_aktivny <> 'A' THEN
            RAISE_APPLICATION_ERROR(-20103, 'Zamestnanec s ID ' || p_id_zamestnanec || ' nie je aktivny.');
        END IF;
    END IF;

    -- tachometer nemoze ukazovat menej ako pri poslednej zakazke toho isteho vozidla
    SELECT MAX(stav_km) INTO v_max_km FROM servisna_zakazka WHERE id_vozidlo = p_id_vozidlo;
    IF p_stav_km < v_max_km THEN
        RAISE_APPLICATION_ERROR(-20101, 'Stav tachometra ' || p_stav_km
                                || ' km je nizsi ako pri predchadzajucej zakazke (' || v_max_km || ' km).');
    END IF;

    -- nova zakazka dostane standardny kontrolny zoznam (NESTED TABLE)
    INSERT INTO servisna_zakazka (id_vozidlo, id_zamestnanec, datum_prijatia, popis_problemu, stav, stav_km, kontrolny_zoznam)
    VALUES (p_id_vozidlo, p_id_zamestnanec, p_datum_prijatia, TRIM(p_popis_problemu), 'PRIJATA', p_stav_km,
            standardny_kontrolny_zoznam())
    RETURNING id_zakazka INTO p_id_zakazka;

    -- stav tachometra pri prijati sa zapise aj do historie vozidla (VARRAY)
    IF p_stav_km IS NOT NULL THEN
        pridaj_stav_km(p_id_vozidlo, p_datum_prijatia, p_stav_km, 'SERVIS');
    END IF;
END;
/

-- ============================================================
-- 2. ZMEN_STAV_ZAKAZKY
-- ============================================================
CREATE OR REPLACE PROCEDURE zmen_stav_zakazky (
    p_id_zakazka  IN servisna_zakazka.id_zakazka%TYPE,
    p_novy_stav   IN servisna_zakazka.stav%TYPE,
    p_datum       IN DATE DEFAULT SYSDATE
)
IS
    v_zakazka   servisna_zakazka%ROWTYPE;
    v_novy_stav servisna_zakazka.stav%TYPE := UPPER(TRIM(p_novy_stav));
    v_pocet     PLS_INTEGER;
BEGIN
    IF v_novy_stav NOT IN ('PRIJATA', 'V_OPRAVE', 'DOKONCENA', 'ODOVZDANA', 'ZRUSENA') OR v_novy_stav IS NULL THEN
        RAISE_APPLICATION_ERROR(-20105, 'Neznamy stav zakazky: ' || NVL(p_novy_stav, '(prazdny)') || '.');
    END IF;

    -- zakazka musi existovat; FOR UPDATE zamkne riadok pocas zmeny
    BEGIN
        SELECT * INTO v_zakazka FROM servisna_zakazka WHERE id_zakazka = p_id_zakazka FOR UPDATE;
    EXCEPTION
        WHEN NO_DATA_FOUND THEN
            RAISE_APPLICATION_ERROR(-20104, 'Servisna zakazka s ID ' || p_id_zakazka || ' neexistuje.');
    END;

    -- povolene prechody stavov
    IF NOT (   (v_zakazka.stav = 'PRIJATA'   AND v_novy_stav IN ('V_OPRAVE', 'ZRUSENA'))
            OR (v_zakazka.stav = 'V_OPRAVE'  AND v_novy_stav IN ('DOKONCENA', 'ZRUSENA'))
            OR (v_zakazka.stav = 'DOKONCENA' AND v_novy_stav = 'ODOVZDANA')) THEN
        RAISE_APPLICATION_ERROR(-20106, 'Nepovolena zmena stavu zakazky ' || p_id_zakazka || ': '
                                || v_zakazka.stav || ' -> ' || v_novy_stav || '.');
    END IF;

    IF v_novy_stav = 'DOKONCENA' THEN
        SELECT COUNT(*) INTO v_pocet FROM vykonany_ukon WHERE id_zakazka = p_id_zakazka;
        IF v_pocet = 0 THEN
            RAISE_APPLICATION_ERROR(-20107, 'Zakazku ' || p_id_zakazka || ' nemozno dokoncit - nema ziadny vykonany ukon.');
        END IF;
        IF p_datum < v_zakazka.datum_prijatia THEN
            RAISE_APPLICATION_ERROR(-20101, 'Datum dokoncenia nemoze byt skorsi ako datum prijatia.');
        END IF;
        UPDATE servisna_zakazka SET stav = v_novy_stav, datum_dokoncenia = p_datum WHERE id_zakazka = p_id_zakazka;
    ELSIF v_novy_stav = 'ODOVZDANA' THEN
        IF p_datum < v_zakazka.datum_dokoncenia THEN
            RAISE_APPLICATION_ERROR(-20101, 'Datum odovzdania nemoze byt skorsi ako datum dokoncenia.');
        END IF;
        UPDATE servisna_zakazka SET stav = v_novy_stav, datum_odovzdania = p_datum WHERE id_zakazka = p_id_zakazka;
    ELSE
        UPDATE servisna_zakazka SET stav = v_novy_stav WHERE id_zakazka = p_id_zakazka;
    END IF;
END;
/

-- ============================================================
-- 3. VYPIS_OTVORENE_ZAKAZKY (kurzor, %ROWTYPE)
-- ============================================================
CREATE OR REPLACE PROCEDURE vypis_otvorene_zakazky
IS
    -- explicitny kurzor so vsetkymi nedokoncenymi zakazkami
    CURSOR c_otvorene IS
        SELECT z.id_zakazka,
               v.spz,
               v.znacka || ' ' || v.model         AS vozidlo,
               za.meno || ' ' || za.priezvisko    AS zakaznik,
               z.datum_prijatia,
               z.stav,
               TRUNC(SYSDATE) - TRUNC(z.datum_prijatia) AS dni_v_servise
        FROM servisna_zakazka z
        JOIN vozidlo v   ON v.id_vozidlo = z.id_vozidlo
        JOIN zakaznik za ON za.id_zakaznik = v.id_zakaznik
        WHERE z.stav IN ('PRIJATA', 'V_OPRAVE')
        ORDER BY z.datum_prijatia, z.id_zakazka;

    -- parametrizovany kurzor: ukony vykonane v danej zakazke
    CURSOR c_ukony(p_id_zakazka servisna_zakazka.id_zakazka%TYPE) IS
        SELECT su.nazov, vu.mnozstvo, vu.jednotkova_cena
        FROM vykonany_ukon vu
        JOIN servisny_ukon su ON su.id_ukon = vu.id_ukon
        WHERE vu.id_zakazka = p_id_zakazka
        ORDER BY vu.id_vykonany_ukon;

    v_zakazka c_otvorene%ROWTYPE;
    v_ukon    c_ukony%ROWTYPE;
BEGIN
    DBMS_OUTPUT.PUT_LINE(RPAD('ID', 6) || RPAD('SPZ', 10) || RPAD('VOZIDLO', 20) || RPAD('ZAKAZNIK', 20)
                         || RPAD('PRIJATA', 12) || RPAD('STAV', 10) || 'DNI');
    DBMS_OUTPUT.PUT_LINE(RPAD('-', 82, '-'));

    OPEN c_otvorene;
    LOOP
        FETCH c_otvorene INTO v_zakazka;
        EXIT WHEN c_otvorene%NOTFOUND;

        DBMS_OUTPUT.PUT_LINE(RPAD(v_zakazka.id_zakazka, 6) || RPAD(v_zakazka.spz, 10)
                             || RPAD(v_zakazka.vozidlo, 20) || RPAD(v_zakazka.zakaznik, 20)
                             || RPAD(TO_CHAR(v_zakazka.datum_prijatia, 'DD.MM.YYYY'), 12)
                             || RPAD(v_zakazka.stav, 10) || v_zakazka.dni_v_servise);

        OPEN c_ukony(v_zakazka.id_zakazka);
        LOOP
            FETCH c_ukony INTO v_ukon;
            EXIT WHEN c_ukony%NOTFOUND;
            DBMS_OUTPUT.PUT_LINE(LPAD(' ', 6) || '- ' || v_ukon.nazov || ' (' || v_ukon.mnozstvo
                                 || ' x ' || v_ukon.jednotkova_cena || ' EUR)');
        END LOOP;
        IF c_ukony%ROWCOUNT = 0 THEN
            DBMS_OUTPUT.PUT_LINE(LPAD(' ', 6) || '- zatial bez vykonanych ukonov');
        END IF;
        CLOSE c_ukony;
    END LOOP;

    DBMS_OUTPUT.PUT_LINE(RPAD('-', 82, '-'));
    DBMS_OUTPUT.PUT_LINE('Pocet otvorenych zakaziek: ' || c_otvorene%ROWCOUNT);
    CLOSE c_otvorene;
EXCEPTION
    WHEN OTHERS THEN
        IF c_ukony%ISOPEN THEN CLOSE c_ukony; END IF;
        IF c_otvorene%ISOPEN THEN CLOSE c_otvorene; END IF;
        RAISE;
END;
/

-- ============================================================
-- 4. TESTY NA DEMO DATACH (na konci ROLLBACK - demo data sa nezmenia)
-- ============================================================
-- OCAKAVANE: zakazky 3 (V_OPRAVE, 1 ukon) a 4 (PRIJATA, bez ukonov), pocet = 2
EXEC vypis_otvorene_zakazky;

-- Uspesny scenar: nova zakazka -> V_OPRAVE -> (ukon) -> DOKONCENA -> ODOVZDANA
DECLARE
    v_id servisna_zakazka.id_zakazka%TYPE;
    v_stav servisna_zakazka.stav%TYPE;
    v_pocet PLS_INTEGER;
    v_km NUMBER;
BEGIN
    vytvor_servisnu_zakazku(p_id_vozidlo => 5, p_datum_prijatia => SYSDATE - 2,
                            p_popis_problemu => 'Klepanie podvozku', p_id_zamestnanec => 2,
                            p_stav_km => 70000, p_id_zakazka => v_id);
    DBMS_OUTPUT.PUT_LINE('Vytvorena zakazka ' || v_id);
    zmen_stav_zakazky(v_id, 'V_OPRAVE');
    INSERT INTO vykonany_ukon (id_zakazka, id_ukon, id_zamestnanec, mnozstvo, jednotkova_cena)
    VALUES (v_id, 2, 2, 1, 35);
    zmen_stav_zakazky(v_id, 'DOKONCENA', SYSDATE - 1);
    zmen_stav_zakazky(v_id, 'ODOVZDANA');
    SELECT stav INTO v_stav FROM servisna_zakazka WHERE id_zakazka = v_id;
    DBMS_OUTPUT.PUT_LINE('Zakazka ' || v_id || ' je v stave ' || v_stav);

    -- kolekcie: zakazka dostala standardny kontrolny zoznam, vozidlo novy zaznam v historii km
    -- OCAKAVANE: 5 bodov kontroly, historia vozidla 5 ma 4 zaznamy (posledny 70000 km)
    SELECT COUNT(*) INTO v_pocet FROM servisna_zakazka z, TABLE(z.kontrolny_zoznam) k WHERE z.id_zakazka = v_id;
    DBMS_OUTPUT.PUT_LINE('Body kontrolneho zoznamu: ' || v_pocet);
    SELECT COUNT(*), MAX(h.km) INTO v_pocet, v_km FROM vozidlo v, TABLE(v.historia_km) h WHERE v.id_vozidlo = 5;
    DBMS_OUTPUT.PUT_LINE('Zaznamy v historii km vozidla 5: ' || v_pocet || ', posledny stav ' || v_km || ' km');
END;
/

-- Chybove scenare - kazda vynimka sa odchyti a vypise
-- OCAKAVANE: ORA-20102, ORA-20101 (2x), ORA-20103, ORA-20104, ORA-20105, ORA-20106, ORA-20107, ORA-20112, ORA-20111, ORA-20113
DECLARE
    v_id servisna_zakazka.id_zakazka%TYPE;

    -- vlastna pomenovana vynimka naviazana na kod chyby (ako na cviceni 2)
    e_neplatna_zmena EXCEPTION;
    PRAGMA EXCEPTION_INIT(e_neplatna_zmena, -20106);

    PROCEDURE test(p_popis VARCHAR2, p_kod PLS_INTEGER) IS
    BEGIN
        DBMS_OUTPUT.PUT_LINE(RPAD(p_popis, 45) || ' -> ' || CASE WHEN p_kod = 0 THEN 'OK' ELSE 'ORA' || p_kod END);
    END;
BEGIN
    BEGIN vytvor_servisnu_zakazku(999, SYSDATE, 'Test', NULL, NULL, v_id); test('neexistujuce vozidlo', 0);
    EXCEPTION WHEN OTHERS THEN test('neexistujuce vozidlo', SQLCODE); END;

    BEGIN vytvor_servisnu_zakazku(1, SYSDATE, '   ', NULL, NULL, v_id); test('prazdny popis', 0);
    EXCEPTION WHEN OTHERS THEN test('prazdny popis', SQLCODE); END;

    BEGIN vytvor_servisnu_zakazku(1, SYSDATE, 'Test', NULL, 1000, v_id); test('tachometer nizsi ako minule', 0);
    EXCEPTION WHEN OTHERS THEN test('tachometer nizsi ako minule', SQLCODE); END;

    BEGIN vytvor_servisnu_zakazku(1, SYSDATE, 'Test', 99, NULL, v_id); test('neexistujuci zamestnanec', 0);
    EXCEPTION WHEN OTHERS THEN test('neexistujuci zamestnanec', SQLCODE); END;

    BEGIN zmen_stav_zakazky(999, 'V_OPRAVE'); test('neexistujuca zakazka', 0);
    EXCEPTION WHEN OTHERS THEN test('neexistujuca zakazka', SQLCODE); END;

    BEGIN zmen_stav_zakazky(4, 'OPRAVENA'); test('neznamy stav', 0);
    EXCEPTION WHEN OTHERS THEN test('neznamy stav', SQLCODE); END;

    BEGIN zmen_stav_zakazky(1, 'V_OPRAVE'); test('ODOVZDANA -> V_OPRAVE', 0);
    EXCEPTION
        WHEN e_neplatna_zmena THEN test('ODOVZDANA -> V_OPRAVE (vlastna vynimka)', SQLCODE);
        WHEN OTHERS THEN test('ODOVZDANA -> V_OPRAVE', SQLCODE);
    END;

    BEGIN zmen_stav_zakazky(4, 'V_OPRAVE'); zmen_stav_zakazky(4, 'DOKONCENA'); test('dokoncenie bez ukonu', 0);
    EXCEPTION WHEN OTHERS THEN test('dokoncenie bez ukonu', SQLCODE); END;

    -- historia km (VARRAY)
    BEGIN pridaj_stav_km(1, SYSDATE, 100000, 'STK'); test('historia km: nizsi stav (stocenie)', 0);
    EXCEPTION WHEN OTHERS THEN test('historia km: nizsi stav (stocenie)', SQLCODE); END;

    BEGIN pridaj_stav_km(1, DATE '2020-01-01', 200000, 'STK'); test('historia km: starsi datum', 0);
    EXCEPTION WHEN OTHERS THEN test('historia km: starsi datum', SQLCODE); END;

    BEGIN pridaj_stav_km(1, SYSDATE, 170000, 'BENZINKA'); test('historia km: neznamy zdroj', 0);
    EXCEPTION WHEN OTHERS THEN test('historia km: neznamy zdroj', SQLCODE); END;
END;
/

ROLLBACK;

-- ============================================================
-- UPRATANIE (odkomentovat pri opakovanom spusteni - CREATE OR REPLACE to ale nepotrebuje)
-- ============================================================
-- DROP PROCEDURE vypis_otvorene_zakazky;
-- DROP PROCEDURE zmen_stav_zakazky;
-- DROP PROCEDURE vytvor_servisnu_zakazku;
-- DROP PROCEDURE pridaj_stav_km;
-- DROP FUNCTION standardny_kontrolny_zoznam;
