-- AUTOSERVIS / CLEN 1 (implementoval clen 3) / AUTOMATICKE GENEROVANIE TESTOVACICH UDAJOV
-- Procedura GENERUJ_TESTOVACIE_DATA vygeneruje logicky spravne udaje:
--   zakaznici -> vozidla -> servisne zakazky v roznych stavoch -> vykonane ukony, pouzite diely -> faktury -> platby.
-- Pravidla konzistencie (zhodne s CHECK obmedzeniami v 01_schema.sql):
--   * datum_dokoncenia >= datum_prijatia, datum_odovzdania >= datum_dokoncenia
--   * stav PRIJATA / V_OPRAVE / ZRUSENA nema datum dokoncenia; DOKONCENA nema datum odovzdania
--   * stav tachometra pri dalsej zakazke toho isteho vozidla rastie
--   * fakturu ma len DOKONCENA / ODOVZDANA zakazka, suma faktury = ukony + diely
--   * ODOVZDANA zakazka je zaplatena cela, DOKONCENA ciastocne alebo vobec
--   * e-mail, VIN, SPZ a cislo faktury su unikatne (obsahuju ID zaznamu)
--   * kolekcie: vozidlo dostane historiu tachometra (VARRAY - prvy zaznam PREDAJ + zaznam z kazdej zakazky),
--     zakazka kontrolny zoznam pri prijati (NESTED TABLE - standardne body s nahodnym stavom)
-- Vygenerovane zaznamy dostanu ID z identity (>= 1000), demo data s ID 1..999 sa nemenia.
--
-- POUZITIE:
--   EXEC generuj_testovacie_data(100);       -- cca 100 zakaznikov, ~1 000 zaznamov
--   EXEC generuj_testovacie_data(10000);     -- pre analyzu indexov (finalna praca): cca 120 000 zaznamov (50 zakaznikov ~ 600 zaznamov)
--   COMMIT;
-- POZOR: po trvalom vygenerovani sa zmenia vysledky reportov (ocakavane hodnoty v 04_reports.sql platia pre demo data).
-- Spustit po 01_schema.sql, 02_demo_data.sql a 12_plsql_zakazky.sql (pouziva STANDARDNY_KONTROLNY_ZOZNAM).
-- Potrebne pravo CREATE PROCEDURE.

SET SERVEROUTPUT ON;

CREATE OR REPLACE PROCEDURE generuj_testovacie_data (
    p_pocet_zakaznikov IN PLS_INTEGER DEFAULT 100,
    p_seed             IN VARCHAR2 DEFAULT 'autoservis'
)
IS
    TYPE t_texty   IS TABLE OF VARCHAR2(60);
    TYPE t_cisla   IS TABLE OF NUMBER;

    c_meno       CONSTANT t_texty := t_texty('Martin','Peter','Jan','Tomas','Michal','Lukas','Marek','Juraj',
                                             'Jana','Lucia','Maria','Katarina','Zuzana','Eva','Monika','Simona');
    c_priezvisko CONSTANT t_texty := t_texty('Kovac','Horvath','Varga','Toth','Nagy','Balaz','Szabo','Molnar',
                                             'Novak','Kral','Polak','Urban','Hudak','Kollar','Blaho','Sloboda');
    c_znacka     CONSTANT t_texty := t_texty('Skoda','Volkswagen','Kia','Hyundai','Toyota','Renault','Peugeot','BMW','Audi','Ford');
    c_model      CONSTANT t_texty := t_texty('Octavia','Golf','Ceed','i30','Corolla','Clio','308','320d','A4','Focus');
    c_popis      CONSTANT t_texty := t_texty('Pravidelny servis','Zvuky pri brzdeni','Kontrolka motora',
                                             'Prezutie pneumatik','Priprava na STK','Slaby vykon motora',
                                             'Klepanie podvozku','Porucha klimatizacie');
    c_stavy      CONSTANT t_texty := t_texty('ODOVZDANA','ODOVZDANA','ODOVZDANA','ODOVZDANA','ODOVZDANA',
                                             'DOKONCENA','V_OPRAVE','PRIJATA','ZRUSENA');
    c_sposob     CONSTANT t_texty := t_texty('HOTOVOST','KARTA','PREVOD');

    v_mechanici  t_cisla;
    v_ukony      t_cisla;
    v_ukony_cena t_cisla;
    v_diely      t_cisla;
    v_diely_cena t_cisla;

    v_id_zakaznik  zakaznik.id_zakaznik%TYPE;
    v_id_vozidlo   vozidlo.id_vozidlo%TYPE;
    v_id_zakazka   servisna_zakazka.id_zakazka%TYPE;
    v_id_faktura   faktura.id_faktura%TYPE;
    v_meno         VARCHAR2(60);
    v_priezvisko   VARCHAR2(60);
    v_index        PLS_INTEGER;
    v_stav         VARCHAR2(20);
    v_prijatie     DATE;
    v_dokoncenie   DATE;
    v_odovzdanie   DATE;
    v_km           NUMBER;
    v_suma         NUMBER;
    v_cena         NUMBER;
    v_mnozstvo     NUMBER;
    v_i            PLS_INTEGER;
    v_pocet        PLS_INTEGER := 0;
    -- nahodne hodnoty sa pocitaju vopred: lokalnu PL/SQL funkciu nemozno volat priamo v SQL prikaze
    v_telefon      zakaznik.telefon%TYPE;
    v_registracia  DATE;
    v_rok          NUMBER;
    v_znacka       vozidlo.znacka%TYPE;
    v_model        vozidlo.model%TYPE;
    v_mechanik     NUMBER;
    v_popis        VARCHAR2(1000);
    v_sposob       VARCHAR2(20);
    v_historia     t_historia_km;
    v_kontrola     t_kontrolny_zoznam;

    -- nahodne cele cislo z intervalu <p_od, p_do>
    FUNCTION nahodne(p_od NUMBER, p_do NUMBER) RETURN PLS_INTEGER IS
    BEGIN
        RETURN TRUNC(DBMS_RANDOM.VALUE(p_od, p_do + 1));
    END;
BEGIN
    IF p_pocet_zakaznikov IS NULL OR p_pocet_zakaznikov < 1 THEN
        RAISE_APPLICATION_ERROR(-20120, 'Pocet zakaznikov musi byt kladne cislo.');
    END IF;

    DBMS_RANDOM.SEED(p_seed);

    SELECT id_zamestnanec BULK COLLECT INTO v_mechanici
    FROM zamestnanec WHERE pozicia = 'MECHANIK' AND aktivny = 'A';
    SELECT id_ukon, standardna_cena BULK COLLECT INTO v_ukony, v_ukony_cena FROM servisny_ukon;
    SELECT id_diel, aktualna_cena BULK COLLECT INTO v_diely, v_diely_cena FROM nahradny_diel;
    IF v_mechanici.COUNT = 0 OR v_ukony.COUNT = 0 OR v_diely.COUNT = 0 THEN
        RAISE_APPLICATION_ERROR(-20121, 'Chybaju ciselniky (mechanici, servisne ukony alebo nahradne diely). '
                                || 'Spustite najprv 02_demo_data.sql.');
    END IF;

    FOR z IN 1 .. p_pocet_zakaznikov LOOP
        v_meno       := c_meno(nahodne(1, c_meno.COUNT));
        v_priezvisko := c_priezvisko(nahodne(1, c_priezvisko.COUNT));

        v_telefon     := '+4219' || LPAD(nahodne(0, 99999999), 8, '0');
        v_registracia := TRUNC(SYSDATE) - nahodne(400, 1500);
        INSERT INTO zakaznik (meno, priezvisko, telefon, datum_registracie)
        VALUES (v_meno, v_priezvisko, v_telefon, v_registracia)
        RETURNING id_zakaznik INTO v_id_zakaznik;
        UPDATE zakaznik SET email = LOWER(v_meno || '.' || v_priezvisko || '.' || v_id_zakaznik) || '@example.com'
        WHERE id_zakaznik = v_id_zakaznik;
        v_pocet := v_pocet + 1;

        FOR v IN 1 .. nahodne(1, 2) LOOP
            v_index := nahodne(1, c_znacka.COUNT);
            v_znacka := c_znacka(v_index);
            v_model  := c_model(v_index);
            v_rok   := nahodne(2005, 2024);
            INSERT INTO vozidlo (id_zakaznik, znacka, model, rok_vyroby)
            VALUES (v_id_zakaznik, v_znacka, v_model, v_rok)
            RETURNING id_vozidlo INTO v_id_vozidlo;
            UPDATE vozidlo
            SET vin = 'GEN' || LPAD(v_id_vozidlo, 14, '0'),
                spz = 'GN' || LPAD(v_id_vozidlo, 7, '0')
            WHERE id_vozidlo = v_id_vozidlo;
            v_pocet := v_pocet + 1;

            v_km := nahodne(20000, 150000);
            v_prijatie := TRUNC(SYSDATE) - nahodne(400, 730);
            -- historia tachometra zacina zaznamom pri kupe vozidla
            v_historia := t_historia_km(t_stav_km(v_prijatie, v_km, 'PREDAJ'));

            FOR k IN 1 .. nahodne(0, 3) LOOP
                -- dalsia zakazka je neskor a s vyssim stavom tachometra
                v_prijatie := v_prijatie + nahodne(30, 120);
                v_km := v_km + nahodne(3000, 20000);
                v_stav := c_stavy(nahodne(1, c_stavy.COUNT));
                -- otvorene zakazky su len posledne dva tyzdne
                IF v_stav IN ('PRIJATA', 'V_OPRAVE') THEN
                    v_prijatie := GREATEST(v_prijatie, TRUNC(SYSDATE) - nahodne(0, 14));
                END IF;
                v_prijatie := LEAST(v_prijatie, TRUNC(SYSDATE));
                v_dokoncenie := NULL;
                v_odovzdanie := NULL;
                IF v_stav IN ('DOKONCENA', 'ODOVZDANA') THEN
                    v_dokoncenie := v_prijatie + nahodne(0, 5);
                END IF;
                IF v_stav = 'ODOVZDANA' THEN
                    v_odovzdanie := v_dokoncenie + nahodne(0, 2);
                END IF;

                v_mechanik := v_mechanici(nahodne(1, v_mechanici.COUNT));
                v_popis    := c_popis(nahodne(1, c_popis.COUNT));

                -- kontrolny zoznam: pri PRIJATA cast bodov este nekontrolovana, inak OK / obcas CHYBA
                v_kontrola := standardny_kontrolny_zoznam();
                FOR b IN 1 .. v_kontrola.COUNT LOOP
                    IF v_stav = 'PRIJATA' AND nahodne(0, 1) = 1 THEN
                        v_kontrola(b).stav := 'NEKONTROLOVANE';
                    ELSIF nahodne(1, 100) <= 15 THEN
                        v_kontrola(b).stav := 'CHYBA';
                        v_kontrola(b).poznamka := 'zistene pri prijati vozidla';
                    ELSE
                        v_kontrola(b).stav := 'OK';
                    END IF;
                END LOOP;

                INSERT INTO servisna_zakazka (id_vozidlo, id_zamestnanec, datum_prijatia, datum_dokoncenia,
                                              datum_odovzdania, popis_problemu, stav, stav_km, kontrolny_zoznam)
                VALUES (v_id_vozidlo, v_mechanik, v_prijatie, v_dokoncenie,
                        v_odovzdanie, v_popis, v_stav, v_km, v_kontrola)
                RETURNING id_zakazka INTO v_id_zakazka;

                -- stav tachometra pri prijati sa prida do historie vozidla (VARRAY)
                IF v_historia.COUNT < v_historia.LIMIT THEN
                    v_historia.EXTEND;
                    v_historia(v_historia.COUNT) := t_stav_km(v_prijatie, v_km, 'SERVIS');
                END IF;
                v_pocet := v_pocet + 1;

                v_suma := 0;
                IF v_stav <> 'PRIJATA' AND v_stav <> 'ZRUSENA' THEN
                    FOR u IN 1 .. nahodne(1, 3) LOOP
                        v_i := nahodne(1, v_ukony.COUNT);
                        v_cena := ROUND(v_ukony_cena(v_i) * DBMS_RANDOM.VALUE(0.9, 1.1), 2);
                        v_mechanik := v_mechanici(nahodne(1, v_mechanici.COUNT));
                        INSERT INTO vykonany_ukon (id_zakazka, id_ukon, id_zamestnanec, mnozstvo, jednotkova_cena)
                        VALUES (v_id_zakazka, v_ukony(v_i), v_mechanik, 1, v_cena);
                        v_suma := v_suma + v_cena;
                        v_pocet := v_pocet + 1;
                    END LOOP;
                    FOR d IN 1 .. nahodne(0, 2) LOOP
                        v_i := nahodne(1, v_diely.COUNT);
                        v_mnozstvo := nahodne(1, 4);
                        INSERT INTO pouzity_diel (id_zakazka, id_diel, mnozstvo, jednotkova_cena)
                        VALUES (v_id_zakazka, v_diely(v_i), v_mnozstvo, v_diely_cena(v_i));
                        v_suma := v_suma + v_mnozstvo * v_diely_cena(v_i);
                        v_pocet := v_pocet + 1;
                    END LOOP;
                END IF;

                IF v_stav IN ('DOKONCENA', 'ODOVZDANA') THEN
                    INSERT INTO faktura (id_zakazka, cislo_faktury, datum_vystavenia, datum_splatnosti, celkova_suma)
                    VALUES (v_id_zakazka, 'FA-GEN-' || v_id_zakazka, v_dokoncenie, v_dokoncenie + 14, v_suma)
                    RETURNING id_faktura INTO v_id_faktura;
                    v_pocet := v_pocet + 1;

                    IF v_stav = 'ODOVZDANA' THEN
                        v_sposob := c_sposob(nahodne(1, c_sposob.COUNT));
                        INSERT INTO platba (id_faktura, datum_platby, suma, sposob)
                        VALUES (v_id_faktura, v_odovzdanie, v_suma, v_sposob);
                        v_pocet := v_pocet + 1;
                    ELSIF nahodne(0, 1) = 1 AND v_suma > 0 THEN
                        INSERT INTO platba (id_faktura, datum_platby, suma, sposob)
                        VALUES (v_id_faktura, v_dokoncenie, ROUND(v_suma / 2, 2), 'KARTA');
                        v_pocet := v_pocet + 1;
                    END IF;
                END IF;
            END LOOP;

            UPDATE vozidlo SET historia_km = v_historia WHERE id_vozidlo = v_id_vozidlo;
        END LOOP;
    END LOOP;

    DBMS_OUTPUT.PUT_LINE('Vygenerovanych zaznamov: ' || v_pocet || ' (zakaznikov: ' || p_pocet_zakaznikov || ').');
END;
/

-- ============================================================
-- UKAZKA - vygeneruje 50 zakaznikov, overi konzistenciu a vsetko vrati spat (ROLLBACK)
-- ============================================================
EXEC generuj_testovacie_data(50);

SELECT 'ZAKAZNIK' tabulka, COUNT(*) pocet FROM zakaznik WHERE id_zakaznik >= 1000
UNION ALL SELECT 'VOZIDLO', COUNT(*) FROM vozidlo WHERE id_vozidlo >= 1000
UNION ALL SELECT 'SERVISNA_ZAKAZKA', COUNT(*) FROM servisna_zakazka WHERE id_zakazka >= 1000
UNION ALL SELECT 'VYKONANY_UKON', COUNT(*) FROM vykonany_ukon WHERE id_vykonany_ukon >= 1000
UNION ALL SELECT 'POUZITY_DIEL', COUNT(*) FROM pouzity_diel WHERE id_pouzity_diel >= 1000
UNION ALL SELECT 'FAKTURA', COUNT(*) FROM faktura WHERE id_faktura >= 1000
UNION ALL SELECT 'PLATBA', COUNT(*) FROM platba WHERE id_platba >= 1000;

-- Kontrola: suma kazdej vygenerovanej faktury = ukony + diely (OCAKAVANE: 0 nezhod)
SELECT COUNT(*) AS pocet_nezhod
FROM faktura f
WHERE f.id_faktura >= 1000
  AND f.celkova_suma <> NVL((SELECT SUM(mnozstvo * jednotkova_cena) FROM vykonany_ukon WHERE id_zakazka = f.id_zakazka), 0)
                      + NVL((SELECT SUM(mnozstvo * jednotkova_cena) FROM pouzity_diel  WHERE id_zakazka = f.id_zakazka), 0);

-- Kontrola kolekcii: kazde vygenerovane vozidlo ma historiu km a kazda zakazka kontrolny zoznam (OCAKAVANE: 0 a 0)
SELECT (SELECT COUNT(*) FROM vozidlo WHERE vin LIKE 'GEN%' AND historia_km IS NULL) AS vozidla_bez_historie,
       (SELECT COUNT(*) FROM servisna_zakazka z JOIN vozidlo v ON v.id_vozidlo = z.id_vozidlo
         WHERE v.vin LIKE 'GEN%' AND z.kontrolny_zoznam IS NULL)                     AS zakazky_bez_kontroly
FROM dual;

-- Kontrola: rozdelenie stavov vygenerovanych zakaziek
SELECT stav, COUNT(*) AS pocet FROM servisna_zakazka WHERE id_zakazka >= 1000 GROUP BY stav ORDER BY pocet DESC;

ROLLBACK;

-- ============================================================
-- UPRATANIE (odkomentovat pri opakovanom spusteni - CREATE OR REPLACE to ale nepotrebuje)
-- ============================================================
-- DROP PROCEDURE generuj_testovacie_data;
