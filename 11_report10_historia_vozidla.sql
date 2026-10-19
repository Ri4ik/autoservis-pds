-- AUTOSERVIS / CLEN 3 / REPORT 10
-- Servisna historia vozidla (PL/SQL, RECORD, kolekcie)
--   * procedura VYPIS_HISTORIU_VOZIDLA - PL/SQL RECORD, vnorena tabulka (nested table) zaznamov,
--     asociativne pole (INDEX BY), BULK COLLECT, vypis cez DBMS_OUTPUT, vynimky
--   * funkcia HISTORIA_VOZIDLA - vracia SQL kolekciu T_HISTORIA_TAB, da sa pouzit v SELECT ... FROM TABLE(...)
--     (pouziva ju aj aplikacia)
-- Spustit po 01_schema.sql a 02_demo_data.sql (F5 v SQL Developeri). Potrebne prava CREATE TYPE, CREATE PROCEDURE.

SET SERVEROUTPUT ON;

-- ============================================================
-- 1. SQL TYPY PRE KOLEKCIU (riadok historie + tabulka riadkov)
-- ============================================================
CREATE OR REPLACE TYPE t_historia_zaznam AS OBJECT (
    id_zakazka        NUMBER,
    datum_prijatia    DATE,
    datum_dokoncenia  DATE,
    stav              VARCHAR2(20),
    stav_km           NUMBER(9),
    popis_problemu    VARCHAR2(1000),
    mechanik          VARCHAR2(130),
    ukony             VARCHAR2(2000),
    suma_ukony        NUMBER(12,2),
    suma_diely        NUMBER(12,2),
    suma_spolu        NUMBER(12,2)
);
/

CREATE OR REPLACE TYPE t_historia_tab AS TABLE OF t_historia_zaznam;
/

-- ============================================================
-- 2. FUNKCIA - HISTORIA AKO KOLEKCIA (pre SQL a aplikaciu)
-- ============================================================
CREATE OR REPLACE FUNCTION historia_vozidla(p_id_vozidlo IN vozidlo.id_vozidlo%TYPE)
RETURN t_historia_tab
IS
    v_historia t_historia_tab;
    v_pocet    NUMBER;
BEGIN
    SELECT COUNT(*) INTO v_pocet FROM vozidlo WHERE id_vozidlo = p_id_vozidlo;
    IF v_pocet = 0 THEN
        RAISE_APPLICATION_ERROR(-20310, 'Vozidlo s ID ' || p_id_vozidlo || ' neexistuje.');
    END IF;

    SELECT t_historia_zaznam(
               z.id_zakazka, z.datum_prijatia, z.datum_dokoncenia, z.stav, z.stav_km, z.popis_problemu,
               m.meno || ' ' || m.priezvisko,
               (SELECT LISTAGG(su.nazov, ', ') WITHIN GROUP (ORDER BY vu.id_vykonany_ukon)
                  FROM vykonany_ukon vu JOIN servisny_ukon su ON su.id_ukon = vu.id_ukon
                 WHERE vu.id_zakazka = z.id_zakazka),
               NVL(u.suma, 0), NVL(d.suma, 0), NVL(u.suma, 0) + NVL(d.suma, 0))
    BULK COLLECT INTO v_historia
    FROM servisna_zakazka z
    LEFT JOIN zamestnanec m ON m.id_zamestnanec = z.id_zamestnanec
    LEFT JOIN (SELECT id_zakazka, SUM(mnozstvo * jednotkova_cena) suma
                 FROM vykonany_ukon GROUP BY id_zakazka) u ON u.id_zakazka = z.id_zakazka
    LEFT JOIN (SELECT id_zakazka, SUM(mnozstvo * jednotkova_cena) suma
                 FROM pouzity_diel GROUP BY id_zakazka) d ON d.id_zakazka = z.id_zakazka
    WHERE z.id_vozidlo = p_id_vozidlo
    ORDER BY z.datum_prijatia, z.id_zakazka;

    RETURN v_historia;
END;
/

-- ============================================================
-- 3. PROCEDURA - VYPIS HISTORIE (RECORD + kolekcie + DBMS_OUTPUT)
-- ============================================================
CREATE OR REPLACE PROCEDURE vypis_historiu_vozidla(p_id_vozidlo IN vozidlo.id_vozidlo%TYPE)
IS
    -- RECORD s udajmi o vozidle a majitelovi
    TYPE r_vozidlo IS RECORD (
        spz        vozidlo.spz%TYPE,
        znacka     vozidlo.znacka%TYPE,
        model      vozidlo.model%TYPE,
        rok_vyroby vozidlo.rok_vyroby%TYPE,
        majitel    VARCHAR2(130)
    );
    -- RECORD s jednou zakazkou a vnorena tabulka (nested table) tychto zaznamov
    TYPE r_zakazka IS RECORD (
        id_zakazka       servisna_zakazka.id_zakazka%TYPE,
        datum_prijatia   servisna_zakazka.datum_prijatia%TYPE,
        datum_dokoncenia servisna_zakazka.datum_dokoncenia%TYPE,
        stav             servisna_zakazka.stav%TYPE,
        stav_km          servisna_zakazka.stav_km%TYPE,
        popis            servisna_zakazka.popis_problemu%TYPE,
        suma_ukony       NUMBER,
        suma_diely       NUMBER
    );
    TYPE t_zakazky IS TABLE OF r_zakazka;
    -- asociativne pole textov (zoznam ukonov a dielov jednej zakazky)
    TYPE t_texty IS TABLE OF VARCHAR2(300) INDEX BY PLS_INTEGER;

    v_vozidlo      r_vozidlo;
    v_zakazky      t_zakazky;
    v_polozky      t_texty;
    v_spolu        NUMBER := 0;
    v_dni_spolu    NUMBER := 0;
    v_dokoncene    PLS_INTEGER := 0;
    v_km_prve      NUMBER;
    v_km_posledne  NUMBER;

    -- Pocet dni so spravnym slovenskym tvarom: 1 den, 2-4 dni, 0 a 5+ dni (s dlznom), desatinne cislo -> dna (1,5 dna).
    -- Znaky s diakritikou cez UNISTR, aby skript nezavisel od kodovania suboru.
    FUNCTION dni_text(p_pocet NUMBER) RETURN VARCHAR2
    IS
        v_cislo VARCHAR2(20);
    BEGIN
        IF p_pocet = TRUNC(p_pocet) THEN
            v_cislo := TO_CHAR(p_pocet);
        ELSE
            v_cislo := TO_CHAR(p_pocet, 'FM9999990D0');
        END IF;

        IF p_pocet <> TRUNC(p_pocet) THEN
            RETURN v_cislo || ' ' || UNISTR('d\0148a');      -- 1,5 dna
        ELSIF p_pocet = 1 THEN
            RETURN v_cislo || ' ' || UNISTR('de\0148');      -- 1 den
        ELSIF p_pocet BETWEEN 2 AND 4 THEN
            RETURN v_cislo || ' dni';                        -- 2, 3, 4 dni
        ELSE
            RETURN v_cislo || ' ' || UNISTR('dn\00ED');      -- 0, 5, 6 ... dni (s dlznom)
        END IF;
    END dni_text;
BEGIN
    SELECT v.spz, v.znacka, v.model, v.rok_vyroby, za.meno || ' ' || za.priezvisko
    INTO v_vozidlo
    FROM vozidlo v
    JOIN zakaznik za ON za.id_zakaznik = v.id_zakaznik
    WHERE v.id_vozidlo = p_id_vozidlo;

    SELECT z.id_zakazka, z.datum_prijatia, z.datum_dokoncenia, z.stav, z.stav_km, z.popis_problemu,
           NVL((SELECT SUM(mnozstvo * jednotkova_cena) FROM vykonany_ukon WHERE id_zakazka = z.id_zakazka), 0),
           NVL((SELECT SUM(mnozstvo * jednotkova_cena) FROM pouzity_diel  WHERE id_zakazka = z.id_zakazka), 0)
    BULK COLLECT INTO v_zakazky
    FROM servisna_zakazka z
    WHERE z.id_vozidlo = p_id_vozidlo
    ORDER BY z.datum_prijatia, z.id_zakazka;

    DBMS_OUTPUT.PUT_LINE('SERVISNA HISTORIA VOZIDLA ' || v_vozidlo.spz || ' - ' || v_vozidlo.znacka || ' '
                         || v_vozidlo.model || ' (' || v_vozidlo.rok_vyroby || '), majitel: ' || v_vozidlo.majitel);
    DBMS_OUTPUT.PUT_LINE(RPAD('=', 80, '='));

    IF v_zakazky.COUNT = 0 THEN
        DBMS_OUTPUT.PUT_LINE('Vozidlo zatial nema ziadnu servisnu zakazku.');
        RETURN;
    END IF;

    FOR i IN 1 .. v_zakazky.COUNT LOOP
        DBMS_OUTPUT.PUT_LINE('Zakazka ' || v_zakazky(i).id_zakazka
                             || ' | prijata ' || TO_CHAR(v_zakazky(i).datum_prijatia, 'DD.MM.YYYY')
                             || ' | ' || v_zakazky(i).stav
                             || ' | ' || NVL(TO_CHAR(v_zakazky(i).stav_km), '?') || ' km');
        DBMS_OUTPUT.PUT_LINE('   Problem: ' || v_zakazky(i).popis);

        SELECT 'ukon: ' || su.nazov || ' (' || vu.mnozstvo || ' x ' || vu.jednotkova_cena || ' EUR)'
        BULK COLLECT INTO v_polozky
        FROM vykonany_ukon vu JOIN servisny_ukon su ON su.id_ukon = vu.id_ukon
        WHERE vu.id_zakazka = v_zakazky(i).id_zakazka
        ORDER BY vu.id_vykonany_ukon;
        FOR j IN 1 .. v_polozky.COUNT LOOP
            DBMS_OUTPUT.PUT_LINE('   - ' || v_polozky(j));
        END LOOP;

        SELECT 'diel: ' || nd.nazov || ' (' || pd.mnozstvo || ' x ' || pd.jednotkova_cena || ' EUR)'
        BULK COLLECT INTO v_polozky
        FROM pouzity_diel pd JOIN nahradny_diel nd ON nd.id_diel = pd.id_diel
        WHERE pd.id_zakazka = v_zakazky(i).id_zakazka
        ORDER BY pd.id_pouzity_diel;
        FOR j IN 1 .. v_polozky.COUNT LOOP
            DBMS_OUTPUT.PUT_LINE('   - ' || v_polozky(j));
        END LOOP;

        DBMS_OUTPUT.PUT_LINE('   Spolu: ' || (v_zakazky(i).suma_ukony + v_zakazky(i).suma_diely) || ' EUR');

        v_spolu := v_spolu + v_zakazky(i).suma_ukony + v_zakazky(i).suma_diely;
        IF v_zakazky(i).datum_dokoncenia IS NOT NULL THEN
            v_dokoncene := v_dokoncene + 1;
            v_dni_spolu := v_dni_spolu + (v_zakazky(i).datum_dokoncenia - v_zakazky(i).datum_prijatia);
        END IF;
        IF v_zakazky(i).stav_km IS NOT NULL THEN
            v_km_prve := NVL(v_km_prve, v_zakazky(i).stav_km);
            v_km_posledne := v_zakazky(i).stav_km;
        END IF;
    END LOOP;

    DBMS_OUTPUT.PUT_LINE(RPAD('-', 80, '-'));
    DBMS_OUTPUT.PUT_LINE('Pocet zakaziek: ' || v_zakazky.COUNT || ', spolu za servis: ' || v_spolu || ' EUR');
    IF v_dokoncene > 0 THEN
        DBMS_OUTPUT.PUT_LINE('Priemerna dlzka opravy: ' || dni_text(ROUND(v_dni_spolu / v_dokoncene, 1)));
    END IF;
    IF v_km_posledne > v_km_prve THEN
        DBMS_OUTPUT.PUT_LINE('Najazdene medzi prvou a poslednou zakazkou: ' || (v_km_posledne - v_km_prve) || ' km');
    END IF;
EXCEPTION
    WHEN NO_DATA_FOUND THEN
        RAISE_APPLICATION_ERROR(-20310, 'Vozidlo s ID ' || p_id_vozidlo || ' neexistuje.');
END;
/

-- ============================================================
-- 4. TESTY NA DEMO DATACH
-- ============================================================
-- Historia vozidla 2 (Skoda Octavia) - OCAKAVANE: 1 zakazka, 2 ukony + 1 diel, spolu 168 EUR
EXEC vypis_historiu_vozidla(2);

SELECT h.id_zakazka, h.datum_prijatia, h.stav, h.stav_km, h.ukony, h.suma_spolu
FROM TABLE(historia_vozidla(2)) h;

-- Neexistujuce vozidlo - vynimka ORA-20310 sa odchyti v anonymnom bloku
BEGIN
    vypis_historiu_vozidla(999);
EXCEPTION
    WHEN OTHERS THEN
        DBMS_OUTPUT.PUT_LINE('Odchytena vynimka: ' || SQLERRM);
END;
/

-- ============================================================
-- 5. UKAZKA S DLHSOU HISTORIOU (docasne data, na konci ROLLBACK)
-- ============================================================
-- Demo data maju na kazde vozidlo len 1 zakazku. Pre ukazku pridame VW Golfu (vozidlo 1) dve starsie
-- zakazky; na konci sa vsetko vrati spat, takze reporty clena 1 sa nezmenia.
INSERT INTO servisna_zakazka (id_zakazka,id_vozidlo,id_zamestnanec,datum_prijatia,datum_dokoncenia,datum_odovzdania,popis_problemu,stav,stav_km)
VALUES (901,1,2,DATE '2025-03-10',DATE '2025-03-12',DATE '2025-03-12','Vymena brzdovych dosticiek vpredu','ODOVZDANA',140000);
INSERT INTO servisna_zakazka (id_zakazka,id_vozidlo,id_zamestnanec,datum_prijatia,datum_dokoncenia,datum_odovzdania,popis_problemu,stav,stav_km)
VALUES (902,1,1,DATE '2025-10-05',DATE '2025-10-05',DATE '2025-10-06','Prezutie na zimne pneumatiky','ODOVZDANA',152000);
INSERT INTO vykonany_ukon (id_vykonany_ukon,id_zakazka,id_ukon,id_zamestnanec,mnozstvo,jednotkova_cena) VALUES (901,901,3,2,1,80);
INSERT INTO vykonany_ukon (id_vykonany_ukon,id_zakazka,id_ukon,id_zamestnanec,mnozstvo,jednotkova_cena) VALUES (902,902,4,1,1,38);
INSERT INTO pouzity_diel (id_pouzity_diel,id_zakazka,id_diel,mnozstvo,jednotkova_cena) VALUES (901,901,3,1,45);

-- OCAKAVANE: 3 zakazky (2025-03-10: 125 EUR, 2025-10-05: 38 EUR, 2026-10-01: 129 EUR), spolu 292 EUR,
--            priemerna dlzka opravy 1 den, najazdene 25000 km
EXEC vypis_historiu_vozidla(1);

SELECT h.id_zakazka, h.datum_prijatia, h.stav_km, h.mechanik, h.ukony, h.suma_spolu,
       SUM(h.suma_spolu) OVER (ORDER BY h.datum_prijatia) AS kumulativne_naklady
FROM TABLE(historia_vozidla(1)) h;

ROLLBACK;

-- ============================================================
-- UPRATANIE (odkomentovat pri opakovanom spusteni)
-- ============================================================
-- DROP PROCEDURE vypis_historiu_vozidla;
-- DROP FUNCTION historia_vozidla;
-- DROP TYPE t_historia_tab;
-- DROP TYPE t_historia_zaznam;
