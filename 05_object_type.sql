-- AUTOSERVIS / Oracle Database 12c+
-- Objektový typ T_ZAKAZNIK s metódami. Spustite po 01_schema.sql a 02_demo_data.sql.
-- Aplikácia si typ vie nainštalovať sama (záložka "Objektový typ"); súbor je kompatibilný aj s SQL Developer / SQL*Plus.
-- Potrebné oprávnenie: CREATE TYPE.

CREATE OR REPLACE TYPE t_zakaznik AS OBJECT (
  id_zakaznik NUMBER,
  meno        VARCHAR2(60),
  priezvisko  VARCHAR2(60),
  telefon     VARCHAR2(25),
  email       VARCHAR2(150),
  MEMBER FUNCTION cele_meno      RETURN VARCHAR2,
  MEMBER FUNCTION kontakt        RETURN VARCHAR2,
  MEMBER FUNCTION pocet_vozidiel RETURN NUMBER
);
/

CREATE OR REPLACE TYPE BODY t_zakaznik AS

  -- "PRIEZVISKO Meno"
  MEMBER FUNCTION cele_meno RETURN VARCHAR2 IS
  BEGIN
    RETURN UPPER(priezvisko) || ' ' || meno;
  END cele_meno;

  -- telefón a (ak existuje) aj e-mail
  MEMBER FUNCTION kontakt RETURN VARCHAR2 IS
  BEGIN
    RETURN telefon || CASE WHEN email IS NOT NULL THEN ' / ' || email END;
  END kontakt;

  -- počet vozidiel zákazníka (číta relačné dáta z tabuľky VOZIDLO)
  MEMBER FUNCTION pocet_vozidiel RETURN NUMBER IS
    v_pocet NUMBER;
  BEGIN
    SELECT COUNT(*) INTO v_pocet FROM vozidlo WHERE id_zakaznik = SELF.id_zakaznik;
    RETURN v_pocet;
  END pocet_vozidiel;

END;
/
