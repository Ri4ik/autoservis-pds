-- Demostracne data pre 01_schema.sql. Spustit IBA RAZ do prazdnej schemy.
-- Upravene (clen 3): vozidla maju historiu tachometra (VARRAY t_historia_km),
-- zakazky maju kontrolny zoznam pri prijati (NESTED TABLE t_kontrolny_zoznam).
-- Pouzivame explicitne ID 1..999, automaticke ID budu generovane od 1000.
INSERT INTO zakaznik (id_zakaznik,meno,priezvisko,telefon,email,datum_registracie) VALUES (1,'Martin','Kovac','+421901111111','martin.kovac@example.com',DATE '2026-09-01');
INSERT INTO zakaznik (id_zakaznik,meno,priezvisko,telefon,email,datum_registracie) VALUES (2,'Jana','Novotna','+421902222222','jana.novotna@example.com',DATE '2026-09-05');
INSERT INTO zakaznik (id_zakaznik,meno,priezvisko,telefon,email,datum_registracie) VALUES (3,'Peter','Horvath','+421903333333','peter.horvath@example.com',DATE '2026-09-12');
INSERT INTO zakaznik (id_zakaznik,meno,priezvisko,telefon,email,datum_registracie) VALUES (4,'Lucia','Bielikova','+421904444444','lucia.bielikova@example.com',DATE '2026-10-01');
INSERT INTO zakaznik (id_zakaznik,meno,priezvisko,telefon,email,datum_registracie) VALUES (5,'Tomas','Polak','+421905555555','tomas.polak@example.com',DATE '2026-10-02');

INSERT INTO vozidlo (id_vozidlo,id_zakaznik,vin,spz,znacka,model,rok_vyroby,historia_km) VALUES (1,1,'WVWZZZ1KZAW000001','BA123AA','Volkswagen','Golf',2010,
  t_historia_km(
    t_stav_km(DATE '2022-05-10', 120000, 'STK'),
    t_stav_km(DATE '2024-05-12', 148000, 'STK'),
    t_stav_km(DATE '2026-10-01', 165000, 'SERVIS')));
INSERT INTO vozidlo (id_vozidlo,id_zakaznik,vin,spz,znacka,model,rok_vyroby,historia_km) VALUES (2,2,'TMBZZZ5JZB0000002','BA456BB','Skoda','Octavia',2015,
  t_historia_km(
    t_stav_km(DATE '2023-03-01', 150000, 'STK'),
    t_stav_km(DATE '2025-03-03', 178000, 'STK'),
    t_stav_km(DATE '2026-10-04', 190000, 'SERVIS')));
INSERT INTO vozidlo (id_vozidlo,id_zakaznik,vin,spz,znacka,model,rok_vyroby,historia_km) VALUES (3,3,'WBAZZZ3AZC0000003','TT789CC','BMW','320d',2017,
  t_historia_km(
    t_stav_km(DATE '2024-06-20', 118000, 'STK'),
    t_stav_km(DATE '2026-10-10', 142000, 'SERVIS')));
INSERT INTO vozidlo (id_vozidlo,id_zakaznik,vin,spz,znacka,model,rok_vyroby,historia_km) VALUES (4,4,'VF1ZZZBZDD0000004','ZA321DD','Renault','Clio',2018,
  t_historia_km(
    t_stav_km(DATE '2022-11-15', 61000, 'STK'),
    t_stav_km(DATE '2024-11-18', 90000, 'STK'),
    t_stav_km(DATE '2026-10-15', 112000, 'SERVIS')));
INSERT INTO vozidlo (id_vozidlo,id_zakaznik,vin,spz,znacka,model,rok_vyroby,historia_km) VALUES (5,5,'WAUZZZ8VZE0000005','BA654EE','Audi','A3',2020,
  t_historia_km(
    t_stav_km(DATE '2023-08-01', 30000, 'PREDAJ'),
    t_stav_km(DATE '2025-08-20', 52000, 'ZAKAZNIK'),
    t_stav_km(DATE '2026-09-15', 65000, 'SERVIS')));

INSERT INTO zamestnanec (id_zamestnanec,meno,priezvisko,pozicia) VALUES (1,'Marek','Slavik','MECHANIK');
INSERT INTO zamestnanec (id_zamestnanec,meno,priezvisko,pozicia) VALUES (2,'Adam','Urban','MECHANIK');
INSERT INTO zamestnanec (id_zamestnanec,meno,priezvisko,pozicia) VALUES (3,'Eva','Kralova','PRIJIMACI_TECHNIK');

INSERT INTO servisna_zakazka (id_zakazka,id_vozidlo,id_zamestnanec,datum_prijatia,datum_dokoncenia,datum_odovzdania,popis_problemu,stav,stav_km,kontrolny_zoznam)
VALUES (1,1,1,DATE '2026-10-01',DATE '2026-10-02',DATE '2026-10-03','Pravidelna vymena oleja a filtra','ODOVZDANA',165000,
  t_kontrolny_zoznam(
    t_bod_kontroly('Karoseria', 'OK', NULL),
    t_bod_kontroly('Pneumatiky', 'OK', NULL),
    t_bod_kontroly('Osvetlenie', 'OK', NULL),
    t_bod_kontroly('Kontrolky', 'OK', NULL),
    t_bod_kontroly('Doklady a kluce', 'OK', NULL),
    t_bod_kontroly('Hladina oleja', 'CHYBA', 'olej pod minimom')));
INSERT INTO servisna_zakazka (id_zakazka,id_vozidlo,id_zamestnanec,datum_prijatia,datum_dokoncenia,datum_odovzdania,popis_problemu,stav,stav_km,kontrolny_zoznam)
VALUES (2,2,2,DATE '2026-10-04',DATE '2026-10-05',DATE '2026-10-06','Zvuky pri brzdeni a slaby brzdny ucinok','ODOVZDANA',190000,
  t_kontrolny_zoznam(
    t_bod_kontroly('Karoseria', 'CHYBA', 'skrabance na zadnom narazniku'),
    t_bod_kontroly('Pneumatiky', 'OK', NULL),
    t_bod_kontroly('Osvetlenie', 'OK', NULL),
    t_bod_kontroly('Kontrolky', 'OK', NULL),
    t_bod_kontroly('Doklady a kluce', 'OK', NULL),
    t_bod_kontroly('Brzdove kotuce - vizualne', 'CHYBA', 'ryhy na prednych kotucoch')));
INSERT INTO servisna_zakazka (id_zakazka,id_vozidlo,id_zamestnanec,datum_prijatia,popis_problemu,stav,stav_km,kontrolny_zoznam)
VALUES (3,3,1,DATE '2026-10-10','Kontrolka motora a strata vykonu','V_OPRAVE',142000,
  t_kontrolny_zoznam(
    t_bod_kontroly('Karoseria', 'OK', NULL),
    t_bod_kontroly('Pneumatiky', 'OK', NULL),
    t_bod_kontroly('Osvetlenie', 'OK', NULL),
    t_bod_kontroly('Kontrolky', 'CHYBA', 'svieti kontrolka motora'),
    t_bod_kontroly('Doklady a kluce', 'OK', NULL)));
INSERT INTO servisna_zakazka (id_zakazka,id_vozidlo,id_zamestnanec,datum_prijatia,popis_problemu,stav,stav_km,kontrolny_zoznam)
VALUES (4,4,2,DATE '2026-10-15','Vozidlo neprechadza technickou kontrolou','PRIJATA',112000,
  t_kontrolny_zoznam(
    t_bod_kontroly('Karoseria', 'NEKONTROLOVANE', NULL),
    t_bod_kontroly('Pneumatiky', 'NEKONTROLOVANE', NULL),
    t_bod_kontroly('Osvetlenie', 'CHYBA', 'nesvieti lave stretavacie svetlo'),
    t_bod_kontroly('Kontrolky', 'OK', NULL),
    t_bod_kontroly('Doklady a kluce', 'OK', 'technicky preukaz prilozeny')));
INSERT INTO servisna_zakazka (id_zakazka,id_vozidlo,id_zamestnanec,datum_prijatia,datum_dokoncenia,popis_problemu,stav,stav_km,kontrolny_zoznam)
VALUES (5,5,1,DATE '2026-09-15',DATE '2026-09-16','Prezutie pneumatik','DOKONCENA',65000,
  t_kontrolny_zoznam(
    t_bod_kontroly('Karoseria', 'OK', NULL),
    t_bod_kontroly('Pneumatiky', 'OK', 'zimne pneumatiky v kufri'),
    t_bod_kontroly('Osvetlenie', 'OK', NULL),
    t_bod_kontroly('Kontrolky', 'OK', NULL),
    t_bod_kontroly('Doklady a kluce', 'OK', NULL)));

INSERT INTO servisny_ukon (id_ukon,nazov,standardna_cena) VALUES (1,'Vymena motoroveho oleja',55);
INSERT INTO servisny_ukon (id_ukon,nazov,standardna_cena) VALUES (2,'Diagnostika motora',35);
INSERT INTO servisny_ukon (id_ukon,nazov,standardna_cena) VALUES (3,'Vymena brzdovych dosticiek',85);
INSERT INTO servisny_ukon (id_ukon,nazov,standardna_cena) VALUES (4,'Prezutie pneumatik',40);
INSERT INTO servisny_ukon (id_ukon,nazov,standardna_cena) VALUES (5,'Vymena vzduchoveho filtra',25);

INSERT INTO vykonany_ukon (id_vykonany_ukon,id_zakazka,id_ukon,id_zamestnanec,mnozstvo,jednotkova_cena) VALUES (1,1,1,1,1,55);
INSERT INTO vykonany_ukon (id_vykonany_ukon,id_zakazka,id_ukon,id_zamestnanec,mnozstvo,jednotkova_cena) VALUES (2,2,2,2,1,35);
INSERT INTO vykonany_ukon (id_vykonany_ukon,id_zakazka,id_ukon,id_zamestnanec,mnozstvo,jednotkova_cena) VALUES (3,2,3,2,1,85);
INSERT INTO vykonany_ukon (id_vykonany_ukon,id_zakazka,id_ukon,id_zamestnanec,mnozstvo,jednotkova_cena) VALUES (4,3,2,1,1,35);
INSERT INTO vykonany_ukon (id_vykonany_ukon,id_zakazka,id_ukon,id_zamestnanec,mnozstvo,jednotkova_cena) VALUES (5,5,4,1,1,40);

INSERT INTO nahradny_diel (id_diel,katalogove_cislo,nazov,aktualna_cena,skladom) VALUES (1,'OIL-5W30','Motorovy olej 5W30 / liter',12,50);
INSERT INTO nahradny_diel (id_diel,katalogove_cislo,nazov,aktualna_cena,skladom) VALUES (2,'FILTER-O-01','Olejovy filter',14,12);
INSERT INTO nahradny_diel (id_diel,katalogove_cislo,nazov,aktualna_cena,skladom) VALUES (3,'BRAKE-P-01','Brzdove dosticky - sada',48,8);
INSERT INTO nahradny_diel (id_diel,katalogove_cislo,nazov,aktualna_cena,skladom) VALUES (4,'FILTER-A-01','Vzduchovy filter',18,15);
INSERT INTO nahradny_diel (id_diel,katalogove_cislo,nazov,aktualna_cena,skladom) VALUES (5,'BRAKE-F-01','Brzdova kvapalina / liter',16,20);

INSERT INTO pouzity_diel (id_pouzity_diel,id_zakazka,id_diel,mnozstvo,jednotkova_cena) VALUES (1,1,1,5,12);
INSERT INTO pouzity_diel (id_pouzity_diel,id_zakazka,id_diel,mnozstvo,jednotkova_cena) VALUES (2,1,2,1,14);
INSERT INTO pouzity_diel (id_pouzity_diel,id_zakazka,id_diel,mnozstvo,jednotkova_cena) VALUES (3,2,3,1,48);
INSERT INTO pouzity_diel (id_pouzity_diel,id_zakazka,id_diel,mnozstvo,jednotkova_cena) VALUES (4,3,4,1,18);

INSERT INTO faktura (id_faktura,id_zakazka,cislo_faktury,datum_vystavenia,datum_splatnosti,celkova_suma) VALUES (1,1,'FA-2026-001',DATE '2026-10-02',DATE '2026-10-16',129);
INSERT INTO faktura (id_faktura,id_zakazka,cislo_faktury,datum_vystavenia,datum_splatnosti,celkova_suma) VALUES (2,2,'FA-2026-002',DATE '2026-10-05',DATE '2026-10-19',168);
INSERT INTO faktura (id_faktura,id_zakazka,cislo_faktury,datum_vystavenia,datum_splatnosti,celkova_suma) VALUES (3,5,'FA-2026-003',DATE '2026-09-16',DATE '2026-09-30',40);

INSERT INTO platba (id_platba,id_faktura,datum_platby,suma,sposob) VALUES (1,1,DATE '2026-10-03',129,'KARTA');
INSERT INTO platba (id_platba,id_faktura,datum_platby,suma,sposob) VALUES (2,2,DATE '2026-10-06',100,'HOTOVOST');
INSERT INTO platba (id_platba,id_faktura,datum_platby,suma,sposob) VALUES (3,3,DATE '2026-09-17',40,'PREVOD');

-- Ulozene BLOB dokumenty su textove prilohy demonstrujuce binarne ulozisko.
INSERT INTO priloha(id_priloha,id_zakazka,nazov_suboru,mime_typ,obsah)
VALUES (1,1,'protokol-olej.txt','text/plain',TO_BLOB(UTL_RAW.CAST_TO_RAW('Vymena oleja uspesne dokoncena')));
INSERT INTO priloha(id_priloha,id_zakazka,nazov_suboru,mime_typ,obsah)
VALUES (2,2,'kontrola-brzd.txt','text/plain',TO_BLOB(UTL_RAW.CAST_TO_RAW('Brzdove dosticky boli vymenene')));
COMMIT;
