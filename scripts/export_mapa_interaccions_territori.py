#!/usr/bin/env python3
"""
Extracció, neteja i diagnòstic de dades de MongoDB (kraken / EGRETA) per construir
el mapa d'interaccions entre la UAB i els agents del territori català.

Genera a `exports/mapa_territori/`:
  1. `mapa_interaccions_uab_territori_catala.xlsx` (4 pestanyes: resum/metodologia,
     convenis, projectes de recerca conjunts i directori d'agents del territori)
  2. `1_convenis_territori_catala.csv`
  3. `2_projectes_recerca_territori_catala.csv`
  4. `3_agents_territori_catala_resum.csv`

Només realitza lectures sobre MongoDB i fitxers locals; no modifica cap base de dades.
"""

from __future__ import annotations

import collections
import csv
import datetime as dt
import os
import re
import sys
import unicodedata
from pathlib import Path
from typing import Any

import openpyxl
from openpyxl.styles import Alignment, Border, Font, PatternFill, Side
from openpyxl.utils import get_column_letter
import pymongo


# ---------------------------------------------------------------------------
# Configuració i constants de domini (Spring-egreta / Pure UAB)
# ---------------------------------------------------------------------------

REPO_ROOT = Path(__file__).resolve().parent.parent
OUTPUT_DIR = REPO_ROOT / "exports" / "mapa_territori"
PROPS_PATH = REPO_ROOT / "src" / "main" / "resources" / "application.properties"
RC0025R_PATH = REPO_ROOT / "src" / "main" / "resources" / "TABLON-Balanç de recursos - detallat (RC0025R).xlsx"

UAB_ORG_UUID = "84443078-1a60-462d-9d0a-b04312afd9eb"
ESFERA_ORG_UUID = "53d7b18a-caf7-4ded-840e-942ff50adc82"

CONVENI_TYPES = {
    "Concessió conveni",
    "Conveni extern a la UAB",
}

PROJECT_TYPES = {
    "Projectes i Ajuts a la Recerca",
    "Projectes d'investigació Internacionals",
    "Projectes de Recerca i altres tipus d'ajuts Externs a la UAB",
    "Grups i Xarxes de Recerca",
    "Grups i Xarxes de Recerca externs a la UAB",
    "Projectes Educatius Internacionals",
    "Accions complementàries i altres ajuts",
    "Finançament Específic",
}

PLACEHOLDER_ORG_NAMES = {
    "",
    "-",
    "--",
    "sense entitat",
    "sin entidad",
    "clients varis",
    "clientes varios",
    "empresa privada",
    "desconegut",
    "desconocido",
    "unknown",
    "sense definir",
    "sin definir",
    "altres",
    "otros",
    "varis",
    "varios",
    "remesa micromecenatge",
    "no aplica",
    "n a",
}

NON_CAT_CITIES = {
    "madrid", "sevilla", "valencia", "zaragoza", "bilbao", "malaga", "murcia",
    "palma", "palma de mallorca", "las palmas", "valladolid", "vigo", "gijon",
    "oviedo", "cordoba", "alicante", "alacant", "granada", "vitoria", "gasteiz",
    "elche", "elx", "santander", "pamplona", "iruna", "almeria", "burgos",
    "salamanca", "albacete", "logrono", "badajoz", "huelva", "leon", "cadiz",
    "jaen", "ourense", "lugo", "caceres", "santiago de compostela", "castellon",
    "castello de la plana", "alcorcon", "getafe", "alcala de henares", "alcobendas",
    "tres cantos", "majadahonda", "pozuelo", "pozuelo de alarcon", "leganes",
    "fuenlabrada", "mostoles", "toledo", "guadalajara", "cuenca", "ciudad real",
    "avila", "segovia", "soria", "zamora", "palencia", "huesca", "teruel",
    "tudela", "donostia", "san sebastian", "barakaldo", "getxo", "cartagena",
    "lorca", "merida", "plasencia", "ferrol", "pontevedra", "torrelavega",
    "aviles", "telde", "arrecife", "la laguna", "santa cruz de tenerife",
    "ceuta", "melilla", "ibiza", "eivissa", "mahon", "mao", "manacor", "inca",
    "elche", "benidorm", "gandia", "sagunto", "sagunt", "torrent", "paterna",
    "alcoy", "alcoi", "orihuela", "torrevieja", "elda", "san vicente del raspeig",
    "cartagena", "molina de segura", "jerez", "algeciras", "marbella", "fuengirola",
    "dos hermanas", "roquetas de mar", "linares", "motril", "talavera de la reina",
    "puertollano", "ponferrada", "aranda de duero", "miranda de ebro",
    "boadilla del monte", "las rozas", "san sebastian de los reyes", "rivas vaciamadrid",
    "coslada", "torrejon de ardoz", "colmenar viejo", "valdemoro", "parla",
    "pinto", "arganda del rey", "barajas", "zamudio", "derio", "erandio", "leioa",
    "arrigorriaga", "durango", "eibar", "irun", "arrasate", "mondragon", "hernani",
    "paris", "london", "brussels", "bruxelles", "berlin", "munich", "rome", "roma",
    "milan", "milano", "lisbon", "lisboa", "porto", "amsterdam", "zurich", "geneva",
    "new york", "boston", "cambridge", "oxford", "tokyo", "beijing", "shanghai",
}

NON_CAT_REGIONS = {
    "madrid", "comunidad de madrid", "andalucia", "junta de andalucia",
    "comunitat valenciana", "comunidad valenciana", "generalitat valenciana",
    "euskadi", "pais vasco", "gobierno vasco", "galicia", "xunta de galicia",
    "asturias", "principado de asturias", "cantabria", "navarra", "aragon",
    "gobierno de aragon", "region de murcia", "extremadura", "castilla y leon",
    "castilla la mancha", "la rioja", "illes balears", "islas baleares",
    "gobierno balear", "govern balear", "govern de les illes balears",
    "canarias", "gobierno de canarias", "tenerife", "gran canaria",
    "venezuela", "anzoategui", "colombia", "mexico", "argentina", "chile",
    "peru", "brasil", "brazil", "france", "francia", "germany", "deutschland",
    "italy", "italia", "portugal", "united kingdom", "usa", "united states",
    "switzerland", "netherlands", "belgium", "japan", "china", "canada",
}

# Municipis i territoris catalans principals -> Província
CAT_MUNICIPIS_PROV: dict[str, str] = {
    # Barcelona
    "barcelona": "Barcelona", "hospitalet": "Barcelona", "l hospitalet de llobregat": "Barcelona",
    "hospitalet de llobregat": "Barcelona", "badalona": "Barcelona", "terrassa": "Barcelona",
    "sabadell": "Barcelona", "mataro": "Barcelona", "santa coloma de gramenet": "Barcelona",
    "cornella": "Barcelona", "cornella de llobregat": "Barcelona", "sant boi": "Barcelona",
    "sant boi de llobregat": "Barcelona", "sant cugat": "Barcelona", "sant cugat del valles": "Barcelona",
    "rubi": "Barcelona", "manresa": "Barcelona", "vilanova i la geltru": "Barcelona",
    "viladecans": "Barcelona", "castelldefels": "Barcelona", "el prat": "Barcelona",
    "el prat de llobregat": "Barcelona", "granollers": "Barcelona", "cerdanyola": "Barcelona",
    "cerdanyola del valles": "Barcelona", "bellaterra": "Barcelona", "mollet": "Barcelona",
    "mollet del valles": "Barcelona", "esplugues": "Barcelona", "esplugues de llobregat": "Barcelona",
    "gava": "Barcelona", "vic": "Barcelona", "sant feliu de llobregat": "Barcelona",
    "igualada": "Barcelona", "vilafranca del penedes": "Barcelona", "ripollet": "Barcelona",
    "sant adria de besos": "Barcelona", "montcada i reixac": "Barcelona", "sant joan despi": "Barcelona",
    "barbera del valles": "Barcelona", "premia de mar": "Barcelona", "sant vicenc dels horts": "Barcelona",
    "sitges": "Barcelona", "martorell": "Barcelona", "pineda de mar": "Barcelona",
    "santa perpetua de mogoda": "Barcelona", "molins de rei": "Barcelona", "calella": "Barcelona",
    "castellar del valles": "Barcelona", "parets del valles": "Barcelona", "caldes de montbui": "Barcelona",
    "berga": "Barcelona", "cardedeu": "Barcelona", "les franqueses del valles": "Barcelona",
    "canovelles": "Barcelona", "malgrat de mar": "Barcelona", "espai barcelona": "Barcelona",
    "sant just desvern": "Barcelona", "tiana": "Barcelona", "montgat": "Barcelona",
    "el masnou": "Barcelona", "arenys de mar": "Barcelona", "sant celoni": "Barcelona",
    "la garriga": "Barcelona", "manlleu": "Barcelona", "torello": "Barcelona",
    "sant quirze del valles": "Barcelona", "polinya": "Barcelona", "palau solita i plegamans": "Barcelona",
    "abrera": "Barcelona", "esparreguera": "Barcelona", "olesa de montserrat": "Barcelona",
    "sant andreu de la barca": "Barcelona", "cubelles": "Barcelona", "sant pere de ribes": "Barcelona",
    "vilanova del cami": "Barcelona", "masquefa": "Barcelona", "cabrils": "Barcelona",
    "vilassar de mar": "Barcelona", "sant pol de mar": "Barcelona", "matadepera": "Barcelona",
    "vacarisses": "Barcelona", "llicia": "Barcelona", "llica d amunt": "Barcelona",
    "vallromanes": "Barcelona", "cabrera de mar": "Barcelona", "alella": "Barcelona",
    # Girona
    "girona": "Girona", "figueres": "Girona", "blanes": "Girona", "lloret de mar": "Girona",
    "olot": "Girona", "salt": "Girona", "palafrugell": "Girona", "sant feliu de guixols": "Girona",
    "roses": "Girona", "banyoles": "Girona", "palamos": "Girona", "santa coloma de farners": "Girona",
    "torroella de montgri": "Girona", "ripoll": "Girona", "castello d empuries": "Girona",
    "la bisbal d emporda": "Girona", "puigcerda": "Girona", "cassà de la selva": "Girona",
    "cassa de la selva": "Girona", "arbucies": "Girona", "tossa de mar": "Girona",
    "platja d aro": "Girona", "castell platja d aro": "Girona", "l escala": "Girona",
    "besalu": "Girona", "begur": "Girona", "cadaques": "Girona", "camprodon": "Girona",
    # Lleida
    "lleida": "Lleida", "tarrega": "Lleida", "balaguer": "Lleida", "mollerussa": "Lleida",
    "la seu d urgell": "Lleida", "seu d urgell": "Lleida", "cervera": "Lleida",
    "solsona": "Lleida", "guissona": "Lleida", "alcarras": "Lleida", "tremp": "Lleida",
    "almacelles": "Lleida", "agramunt": "Lleida", "les borges blanques": "Lleida",
    "borges blanques": "Lleida", "bellpuig": "Lleida", "alpicat": "Lleida",
    "vielha": "Lleida", "sort": "Lleida", "el pont de suert": "Lleida", "artesa de segre": "Lleida",
    # Tarragona
    "tarragona": "Tarragona", "reus": "Tarragona", "tortosa": "Tarragona",
    "el vendrell": "Tarragona", "vendrell": "Tarragona", "cambrils": "Tarragona",
    "salou": "Tarragona", "valls": "Tarragona", "calafell": "Tarragona",
    "amposta": "Tarragona", "vila seca": "Tarragona", "vilaseca": "Tarragona",
    "sant carles de la rapita": "Tarragona", "la rapita": "Tarragona", "torredembarra": "Tarragona",
    "cunit": "Tarragona", "montblanc": "Tarragona", "deltebre": "Tarragona",
    "alcanar": "Tarragona", "la senia": "Tarragona", "mora d ebre": "Tarragona",
    "falset": "Tarragona", "constantí": "Tarragona", "constanti": "Tarragona",
    "riudoms": "Tarragona", "roquetes": "Tarragona", "ulldecona": "Tarragona",
    "vandellos": "Tarragona", "l hospitalet de l infant": "Tarragona",
}

CAT_NAME_INDICATORS: list[tuple[str, str]] = [
    ("diputacio de barcelona", "Barcelona"),
    ("area metropolitana de barcelona", "Barcelona"),
    ("barcelona", "Barcelona"),
    ("bellaterra", "Barcelona"),
    ("cerdanyola", "Barcelona"),
    ("sabadell", "Barcelona"),
    ("terrassa", "Barcelona"),
    ("sant cugat", "Barcelona"),
    ("badalona", "Barcelona"),
    ("hospitalet", "Barcelona"),
    ("mataro", "Barcelona"),
    ("manresa", "Barcelona"),
    ("granollers", "Barcelona"),
    ("igualada", "Barcelona"),
    ("vilafranca", "Barcelona"),
    ("vilanova i la geltru", "Barcelona"),
    ("caldes de montbui", "Barcelona"),
    ("cornella", "Barcelona"),
    ("esplugues", "Barcelona"),
    ("el prat de llobregat", "Barcelona"),
    ("sant joan despi", "Barcelona"),
    ("parc tauli", "Barcelona"),
    ("vall d hebron", "Barcelona"),
    ("germans trias", "Barcelona"),
    ("can ruti", "Barcelona"),
    ("bellvitge", "Barcelona"),
    ("sant pau", "Barcelona"),
    ("sincrotro", "Barcelona"),
    ("alba cells", "Barcelona"),
    ("eurecat", "Barcelona"),
    ("leitat", "Barcelona"),
    ("girona", "Girona"),
    ("figueres", "Girona"),
    ("banyoles", "Girona"),
    ("trueta", "Girona"),
    ("lleida", "Lleida"),
    ("tarrega", "Lleida"),
    ("balaguer", "Lleida"),
    ("mollerussa", "Lleida"),
    ("arnau de vilanova", "Lleida"),
    ("centre tecnologic forestal", "Lleida"),
    ("forest technology centre", "Lleida"),
    ("tarragona", "Tarragona"),
    ("reus", "Tarragona"),
    ("tortosa", "Tarragona"),
    ("amposta", "Tarragona"),
    ("cambrils", "Tarragona"),
    ("joan xxiii", "Tarragona"),
    ("iciq", "Tarragona"),
    ("iphes", "Tarragona"),
    ("paleoecologia humana", "Tarragona"),
    ("catalunya", "Catalunya (general)"),
    ("catalonia", "Catalunya (general)"),
    ("catalan", "Catalunya (general)"),
    ("catalana", "Catalunya (general)"),
    ("generalitat de catalunya", "Catalunya (general)"),
    ("generalitat", "Catalunya (general)"),
    ("agaur", "Catalunya (general)"),
    ("catsalut", "Catalunya (general)"),
    ("servei catala", "Catalunya (general)"),
    ("institut catala", "Catalunya (general)"),
    ("cerca", "Catalunya (general)"),
    ("icrea", "Catalunya (general)"),
    ("irta", "Catalunya (general)"),
]

CP_PREFIX_PROVINCE = {
    "08": "Barcelona",
    "17": "Girona",
    "25": "Lleida",
    "43": "Tarragona",
}

PRIVATE_UNI_KEYWORDS = (
    "ramon llull", "uic", "internacional de catalunya", "abat oliba",
    "esade", "iqs", "iese", "eada", "blanquerna", "la salle", "uoc",
    "oberta de catalunya", "uvic", "universitat de vic",
)

PRIVATE_LEGAL_SUFFIXES = (
    "s l", "sl", "s a", "sa", "s l u", "slu", "s a u", "sau", "s l p", "slp",
    "s coop", "scoop", "gmbh", "ltd", "llc", "inc", "corp", "plc", "b v", "s r l",
)

PUBLIC_KEYWORDS = (
    "ajuntament", "ayuntamiento", "diputacio", "diputacion", "generalitat",
    "consell comarcal", "conselleria", "ministeri", "ministerio", "gobierno",
    "govern", "agencia estatal", "agaur", "catsalut", "servei catala",
    "institut catala", "csic", "cerca", "irta", "icrea", "hospital universitari",
    "hospital clinic", "hospital de la santa creu", "parc tauli", "vall d hebron",
    "germans trias", "bellvitge", "arnau de vilanova", "josep trueta", "joan xxiii",
    "conselh generau", "entitat municipal", "mancomunitat", "consorci sanitari",
    "area metropolitana de barcelona", "autoritat del transport", "autoritat portuaria",
)

IP_ROLE_KEYWORDS = (
    "investigador/a principal",
    "principal investigator",
)

COIP_ROLE_KEYWORDS = (
    "co-investigador/a principal",
    "co-principal investigator",
)


# ---------------------------------------------------------------------------
# Helpers genèrics
# ---------------------------------------------------------------------------

def get_mongo_uri() -> str:
    """Obté la URI de MongoDB de MONGO_URI o de application.properties."""
    if os.environ.get("MONGO_URI"):
        return os.environ["MONGO_URI"]
    if PROPS_PATH.exists():
        for line in PROPS_PATH.read_text(encoding="utf-8").splitlines():
            if line.startswith("spring.mongodb.uri="):
                return line.split("=", 1)[1].strip()
    raise RuntimeError("No s'ha trobat spring.mongodb.uri a application.properties ni a MONGO_URI.")


def normalize_text(value: Any) -> str:
    """Normalitza text (NFD, sense accents, minúscules, alfanumèric + espais)."""
    s = unicodedata.normalize("NFD", str(value or ""))
    s = "".join(ch for ch in s if not unicodedata.category(ch).startswith("M"))
    s = s.lower()
    s = re.sub(r"[^a-z0-9\s]", " ", s, flags=re.ASCII)
    return re.sub(r"\s+", " ", s, flags=re.ASCII).strip()


def contains_word(haystack_norm: str, needle_norm: str) -> bool:
    """Comprova si `needle_norm` apareix com a seqüència de paraules completes."""
    if not haystack_norm or not needle_norm:
        return False
    return f" {needle_norm} " in f" {haystack_norm} "


def pick_lang(obj: Any, langs: tuple[str, ...] = ("ca_ES", "es_ES", "en_GB")) -> str:
    """Extreu text multilingüe d'un dict Pure o string netejant espais."""
    if obj is None:
        return ""
    if isinstance(obj, str):
        return re.sub(r"\s+", " ", obj).strip()
    if isinstance(obj, dict):
        if "term" in obj and isinstance(obj["term"], dict):
            return pick_lang(obj["term"], langs)
        for lang in langs:
            val = obj.get(lang)
            if isinstance(val, str) and val.strip():
                return re.sub(r"\s+", " ", val).strip()
        if isinstance(obj.get("text"), list):
            for lang in langs:
                for entry in obj["text"]:
                    if isinstance(entry, dict) and entry.get("locale") == lang and str(entry.get("value") or "").strip():
                        return re.sub(r"\s+", " ", str(entry["value"])).strip()
            for entry in obj["text"]:
                if isinstance(entry, dict) and str(entry.get("value") or "").strip():
                    return re.sub(r"\s+", " ", str(entry["value"])).strip()
        for val in obj.values():
            if isinstance(val, str) and val.strip():
                return re.sub(r"\s+", " ", val).strip()
    return ""


def parse_date(val: Any) -> dt.date | None:
    """Converteix datetime/str/dict de MongoDB a datetime.date."""
    if val is None:
        return None
    if isinstance(val, dt.datetime):
        return val.date()
    if isinstance(val, dt.date):
        return val
    if isinstance(val, str):
        s = val.strip()[:10]
        try:
            return dt.date.fromisoformat(s)
        except ValueError:
            return None
    if isinstance(val, dict) and val.get("year"):
        try:
            return dt.date(int(val["year"]), int(val.get("month") or 1), int(val.get("day") or 1))
        except (ValueError, TypeError):
            return None
    return None


def parse_float(val: Any) -> float:
    """Converteix imports de Pure (str, int, float) a float segur."""
    if val is None:
        return 0.0
    if isinstance(val, (int, float)):
        return float(val)
    s = str(val).strip().replace(",", ".")
    try:
        return float(s)
    except ValueError:
        return 0.0


def clean_description(descriptions: Any) -> str:
    """Extreu la descripció significativa d'un document Award o Project."""
    if not isinstance(descriptions, list):
        return ""
    ignore_norm = {
        "summary not available",
        "resum no disponible",
        "resumen no disponible",
        "no description",
        "sense descripcio",
    }
    for d in descriptions:
        if not isinstance(d, dict):
            continue
        val = pick_lang(d.get("value"))
        if val and normalize_text(val) not in ignore_norm:
            if len(val) > 800:
                return val[:797] + "..."
            return val
    return ""


# ---------------------------------------------------------------------------
# Classificació Territorial i Caràcter Públic/Privat d'ExternalOrganizations
# ---------------------------------------------------------------------------

UAB_EXTERNAL_SELF_UUID = "9c6105b0-d76c-4055-a281-0f8271544640"

UAB_KEEP_KEYWORDS = (
    "fundacio uab", "fundacion uab", "parc de recerca uab", "clinic veterinari",
    "salut i envelliment", "autonoma solidaria", "irta", "generalitat",
    "diputacio", "ajuntament", "ayuntamiento", "universidad de barcelona",
    "universitat de barcelona", "universitat politecnica", "universitat pompeu fabra",
    "universitat de vic", "csic", "cerca", "british council", "vrije",
    "estatal russa", "collado", "24 institucions", "associacio", "asociacion",
)


def is_uab_self_reference(eo_uuid: str, name_raw: str, name_norm: str) -> bool:
    """
    Detecta autoreferències internes de la UAB dins ExternalOrganizations
    (el perfil mirall de la UAB i òrgans interns sense personalitat jurídica pròpia).
    Manté fundacions/entitats vinculades pròpies i consorcis mixtos amb tercers.
    """
    if eo_uuid == UAB_EXTERNAL_SELF_UUID:
        return True
    if (
        "universitat autonoma de barcelona" in name_norm
        or "universidad autonoma de barcelona" in name_norm
        or contains_word(name_norm, "uab")
    ):
        if any(contains_word(name_norm, kw) for kw in UAB_KEEP_KEYWORDS):
            return False
        return True
    return False


def detect_catalan_territory(eo_doc: dict[str, Any]) -> tuple[bool, str, str]:
    """
    Determina si una ExternalOrganization pertany al territori català.
    Retorna: (es_catalunya, metode_deteccio, provincia).
    """
    name = pick_lang(eo_doc.get("name"))
    name_norm = normalize_text(name)
    if name_norm in PLACEHOLDER_ORG_NAMES or name_norm.startswith("sense entitat"):
        return False, "", ""

    addr = eo_doc.get("address") or {}
    country_uri = str(((addr.get("country") or {}).get("uri")) or "").strip().lower()
    country_term_norm = normalize_text(pick_lang((addr.get("country") or {}).get("term")))

    if country_uri and not country_uri.endswith("/es"):
        return False, "", ""
    if not country_uri and country_term_norm and country_term_norm not in ("spain", "espana", "espanya", ""):
        return False, "", ""

    raw_cp = str(addr.get("postalCode") or "").strip()
    cp_digits = re.sub(r"[^0-9]", "", raw_cp)
    city_raw = str(addr.get("city") or "").strip()
    city_norm = normalize_text(city_raw)
    addr1_norm = normalize_text(addr.get("address1") or "")

    # 1. Codi postal espanyol de 5 dígits (prioritat màxima i excloent)
    if len(cp_digits) == 5:
        prefix = cp_digits[:2]
        if prefix in CP_PREFIX_PROVINCE:
            return True, "Confirmada per Codi Postal", CP_PREFIX_PROVINCE[prefix]
        return False, "", ""

    # 2. Municipi informat
    if city_norm and city_norm not in ("-", "unknown", "desconegut", "desconocido", "n a"):
        # Descartar ciutats clarament de fora de Catalunya
        for nc in NON_CAT_CITIES:
            if city_norm == nc or contains_word(city_norm, nc):
                return False, "", ""
        # Comprovar municipis catalans exactes o per paraula completa
        for muni, prov in CAT_MUNICIPIS_PROV.items():
            if city_norm == muni or contains_word(city_norm, muni):
                return True, "Confirmada per Municipi", prov
        # Si city indica Catalunya/regió o nom entitat indica Catalunya sense ciutat no-catalana
        for kw, prov in CAT_NAME_INDICATORS:
            if contains_word(city_norm, kw) or contains_word(addr1_norm, kw) or contains_word(name_norm, kw):
                return True, "Confirmada per Municipi / Adreça", prov
        return False, "", ""

    # 3. Sense CP vàlid ni municipi informat: avaluar addr1 i nom de l'entitat
    combined_no_city = f"{addr1_norm} {name_norm}".strip()
    for nc in NON_CAT_CITIES | NON_CAT_REGIONS:
        if contains_word(combined_no_city, nc):
            # Excepció: "Generalitat de Catalunya" vs "Generalitat Valenciana"
            if nc == "valencia" and "valenciana" not in combined_no_city and "catalunya" in combined_no_city:
                continue
            return False, "", ""

    for kw, prov in CAT_NAME_INDICATORS:
        if contains_word(combined_no_city, kw):
            return True, "Inferida pel Nom de l'Entitat", prov

    return False, "", ""


def extract_caracter_and_interaction(eo_doc: dict[str, Any]) -> tuple[str, str, str]:
    """
    Extreu el caràcter (Pública / Privada / Mixta) d'EGRETA o l'infereix.
    Retorna: (caracter_agent, origen_caracter, tipus_interaccio_uab).
    """
    caracter_val = ""
    for kg in eo_doc.get("keywordGroups") or []:
        if "caracter" not in str(kg.get("logicalName") or "").lower():
            continue
        for cl in kg.get("classifications") or []:
            val = pick_lang(cl.get("term"))
            if val:
                caracter_val = val
                break
        if not caracter_val:
            for kc in kg.get("keywordContainers") or []:
                sk = kc.get("structuredKeyword") or {}
                val = pick_lang(sk.get("term"))
                if val:
                    caracter_val = val
                    break
        if caracter_val:
            break

    if caracter_val:
        c_norm = normalize_text(caracter_val)
        if c_norm in ("publica", "public", "publico"):
            return "Pública", "EGRETA validat", "Publico-Públic"
        if c_norm in ("privada", "private", "privado"):
            return "Privada", "EGRETA validat", "Publico-Privat"
        if c_norm in ("mixta", "mixto", "mixed"):
            return "Mixta", "EGRETA validat", "Publico-Mixt / Tercer Sector"
        if c_norm == "altres":
            return "Altres", "EGRETA validat", "Publico-Mixt / Tercer Sector"

    # Fallback inferit per tipologia i nom
    tipus_ca = pick_lang((eo_doc.get("type") or {}).get("term"))
    tipus_norm = normalize_text(tipus_ca)
    name_norm = normalize_text(pick_lang(eo_doc.get("name")))

    # Comprovar universitats privades abans de regla general d'universitat
    if any(contains_word(name_norm, k) for k in PRIVATE_UNI_KEYWORDS):
        return "Privada (inferida)", "Inferit per tipologia/nom", "Publico-Privat"

    if tipus_norm == "entitat empresarial" or any(contains_word(name_norm, s) for s in PRIVATE_LEGAL_SUFFIXES):
        return "Privada (inferida)", "Inferit per tipologia/nom", "Publico-Privat"

    if tipus_norm in (
        "agencia estatal",
        "govern",
        "organisme public d investigacio",
        "universitat",
        "departament universitari",
        "institut universitari d investigacio",
        "entitat gestora del sistema nacional de salut",
        "administracio internacional",
        "sector administracio publica",
    ) or any(contains_word(name_norm, k) for k in PUBLIC_KEYWORDS):
        return "Pública (inferida)", "Inferit per tipologia/nom", "Publico-Públic"

    return "Sense classificar (Tercer sector / Altres)", "No classificat a EGRETA", "Publico-Mixt / Tercer Sector"


def extract_eo_identifiers(eo_doc: dict[str, Any]) -> tuple[str, str]:
    """Extreu (ror_id, nif_cif) d'una ExternalOrganization."""
    ror = ""
    nif = ""
    for ident in eo_doc.get("identifiers") or []:
        if not isinstance(ident, dict):
            continue
        type_uri = str(((ident.get("type") or {}).get("uri")) or "").lower()
        id_src = str(ident.get("idSource") or "").lower()
        val = str(ident.get("value") or ident.get("id") or "").strip()
        if not val:
            continue
        if not ror and ("/ror" in type_uri or id_src == "ror" or "ror.org" in val.lower()):
            ror = val
        elif not nif and ("/nif" in type_uri or id_src == "nif"):
            nif = val
    return ror, nif


# ---------------------------------------------------------------------------
# Catàlegs interns i auxiliars
# ---------------------------------------------------------------------------

def load_internal_orgs(db) -> tuple[dict[str, dict[str, Any]], dict[str, str]]:
    """Carrega Organizations i resol àmbit UAB vs Esfera UAB."""
    orgs: dict[str, dict[str, Any]] = {}
    parents_map: dict[str, list[str]] = {}
    for doc in db["Organizations"].find({}, {"uuid": 1, "name": 1, "type": 1, "parents": 1}):
        u = doc.get("uuid")
        if not u:
            continue
        orgs[u] = {
            "nom": pick_lang(doc.get("name")),
            "tipus": pick_lang((doc.get("type") or {}).get("term")),
        }
        parents_map[u] = [p.get("uuid") for p in (doc.get("parents") or []) if isinstance(p, dict) and p.get("uuid")]

    ambit_cache: dict[str, str] = {}

    def resolve_ambit(uuid: str) -> str:
        if not uuid:
            return ""
        if uuid in ambit_cache:
            return ambit_cache[uuid]
        visited = set()
        queue = collections.deque([uuid])
        res = "UAB / Altre"
        while queue:
            curr = queue.popleft()
            if curr in visited:
                continue
            visited.add(curr)
            if curr == ESFERA_ORG_UUID:
                res = "Esfera UAB"
                break
            if curr == UAB_ORG_UUID:
                res = "UAB"
                break
            for p in parents_map.get(curr, []):
                if p not in visited:
                    queue.append(p)
        ambit_cache[uuid] = res
        return res

    for u in list(orgs.keys()):
        orgs[u]["ambit"] = resolve_ambit(u)
    return orgs, ambit_cache


def load_catalan_agents(db) -> tuple[dict[str, dict[str, Any]], dict[str, Any]]:
    """Carrega i filtra ExternalOrganizations al territori català."""
    catalan: dict[str, dict[str, Any]] = {}
    stats = {
        "total_eo": 0,
        "placeholders_exclosos": 0,
        "uab_autoreferencies_excloses": 0,
        "espanya": 0,
        "catalunya_total": 0,
        "metodes": collections.Counter(),
        "provincies": collections.Counter(),
        "caracter": collections.Counter(),
    }

    proj = {"uuid": 1, "name": 1, "address": 1, "type": 1, "keywordGroups": 1, "identifiers": 1}
    for doc in db["ExternalOrganizations"].find({}, proj):
        stats["total_eo"] += 1
        u = doc.get("uuid")
        if not u:
            continue
        name = pick_lang(doc.get("name"))
        name_norm = normalize_text(name)
        if name_norm in PLACEHOLDER_ORG_NAMES or name_norm.startswith("sense entitat"):
            stats["placeholders_exclosos"] += 1
            continue
        if is_uab_self_reference(u, name, name_norm):
            stats["uab_autoreferencies_excloses"] += 1
            continue

        addr = doc.get("address") or {}
        ctry_uri = str(((addr.get("country") or {}).get("uri")) or "").lower()
        if ctry_uri.endswith("/es"):
            stats["espanya"] += 1

        is_cat, metode, provincia = detect_catalan_territory(doc)
        if not is_cat:
            continue

        caracter, origen_car, tipus_int = extract_caracter_and_interaction(doc)
        ror, nif = extract_eo_identifiers(doc)
        tipus_eo = pick_lang((doc.get("type") or {}).get("term")) or "Desconegut"
        geo = str(((addr.get("geoLocation") or {}).get("point")) or "").strip()
        cp = str(addr.get("postalCode") or "").strip()
        city = str(addr.get("city") or "").strip()
        if city == "-":
            city = ""

        catalan[u] = {
            "uuid": u,
            "nom": name,
            "caracter": caracter,
            "origen_caracter": origen_car,
            "tipus_interaccio": tipus_int,
            "tipologia": tipus_eo,
            "municipi": city,
            "codi_postal": cp,
            "provincia": provincia or "Catalunya (general)",
            "coordenades": geo,
            "metode": metode,
            "ror_id": ror,
            "nif_cif": nif,
        }
        stats["catalunya_total"] += 1
        stats["metodes"][metode] += 1
        stats["provincies"][catalan[u]["provincia"]] += 1
        stats["caracter"][caracter] += 1

    return catalan, stats


def load_projects_by_cluster(db) -> tuple[dict[str, list[dict[str, Any]]], int]:
    """Carrega Projects indexats per awardClusters.uuid."""
    by_cluster: dict[str, list[dict[str, Any]]] = collections.defaultdict(list)
    total_projects = 0
    proj = {"uuid": 1, "pureId": 1, "title": 1, "descriptions": 1, "collaborators": 1, "awardClusters": 1, "workflow": 1, "type": 1}
    for doc in db["Projects"].find({}, proj):
        total_projects += 1
        clusters = [c.get("uuid") for c in (doc.get("awardClusters") or []) if isinstance(c, dict) and c.get("uuid")]
        ext_collabs = []
        internal_lead = None
        has_collabs = bool(doc.get("collaborators"))
        for c in doc.get("collaborators") or []:
            if not isinstance(c, dict):
                continue
            disc = c.get("typeDiscriminator")
            if disc == "InternalCollaboratorAssociation" and c.get("leadCollaborator"):
                internal_lead = True
            elif disc == "ExternalCollaboratorAssociation":
                eo_u = ((c.get("externalOrganization") or {}).get("uuid"))
                if eo_u:
                    ext_collabs.append({
                        "uuid": eo_u,
                        "role": pick_lang((c.get("type") or {}).get("term")) or "Col·laborador",
                        "is_lead": bool(c.get("leadCollaborator")),
                    })
        entry = {
            "project_uuid": doc.get("uuid") or "",
            "project_pure_id": doc.get("pureId") or "",
            "description": clean_description(doc.get("descriptions")),
            "ext_collabs": ext_collabs,
            "has_collabs": has_collabs,
            "internal_lead": internal_lead,
        }
        for cl_u in clusters:
            by_cluster[cl_u].append(entry)
    return by_cluster, total_projects


def load_applications_and_fopps(db) -> tuple[dict[str, dict[str, Any]], dict[str, dict[str, str]]]:
    """Carrega Applications i FundingOpportunities per obtenir convocatòria, programa i socis."""
    fopps: dict[str, dict[str, str]] = {}
    for doc in db["FundingOpportunities"].find({}, {"uuid": 1, "title": 1, "type": 1, "keywordGroups": 1}):
        u = doc.get("uuid")
        if not u:
            continue
        programes = []
        for kg in doc.get("keywordGroups") or []:
            if kg.get("logicalName") == "/uab/fundingopportunities/programes":
                for kc in kg.get("keywordContainers") or []:
                    term = pick_lang(((kc.get("structuredKeyword") or {}).get("term")))
                    if term:
                        programes.append(term)
                for cl in kg.get("classifications") or []:
                    term = pick_lang(cl.get("term"))
                    if term:
                        programes.append(term)
        fopps[u] = {
            "title": pick_lang(doc.get("title")),
            "type": pick_lang((doc.get("type") or {}).get("term")),
            "programa": "; ".join(dict.fromkeys(programes)),
        }

    apps: dict[str, dict[str, Any]] = {}
    for doc in db["Applications"].find({}, {"uuid": 1, "fundingOpportunity": 1, "collaborators": 1, "externalOrganizations": 1}):
        u = doc.get("uuid")
        if not u:
            continue
        ext_collabs = []
        for c in doc.get("collaborators") or []:
            if isinstance(c, dict) and c.get("typeDiscriminator") == "ExternalCollaboratorAssociation":
                eo_u = ((c.get("externalOrganization") or {}).get("uuid"))
                if eo_u:
                    ext_collabs.append({
                        "uuid": eo_u,
                        "role": pick_lang((c.get("type") or {}).get("term")) or "Col·laborador sol·licitud",
                        "is_lead": bool(c.get("leadCollaborator")),
                    })
        ext_orgs = [
            eo.get("uuid") for eo in (doc.get("externalOrganizations") or [])
            if isinstance(eo, dict) and eo.get("uuid")
        ]
        apps[u] = {
            "fopp_uuid": ((doc.get("fundingOpportunity") or {}).get("uuid")) or "",
            "ext_collabs": ext_collabs,
            "ext_orgs": ext_orgs,
        }
    return apps, fopps


# ---------------------------------------------------------------------------
# Processament d'Awards (Convenis i Projectes de Recerca)
# ---------------------------------------------------------------------------

def extract_award_metadata(
    aw: dict[str, Any],
    internal_orgs: dict[str, dict[str, Any]],
    projects_by_cluster: dict[str, list[dict[str, Any]]],
    apps: dict[str, dict[str, Any]],
    fopps: dict[str, dict[str, str]],
) -> dict[str, Any]:
    """Extreu camps homogenis i nets d'un document Award."""
    # Codi de referència
    ref_code = ""
    fenix_id = ""
    primary_id = ""
    for ident in aw.get("identifiers") or []:
        if not isinstance(ident, dict):
            continue
        val = str(ident.get("id") or ident.get("value") or "").strip()
        if not val:
            continue
        t_uri = str(((ident.get("type") or {}).get("uri")) or "").lower()
        disc = str(ident.get("typeDiscriminator") or "").lower()
        if "reference" in t_uri or "referencia" in t_uri:
            ref_code = val
        elif "id_fenix" in t_uri:
            fenix_id = val
        elif disc == "primaryid" and not primary_id:
            primary_id = val
    codi_ref = ref_code or fenix_id or primary_id

    # Dates
    d_award = parse_date(aw.get("awardDate"))
    period = aw.get("actualPeriod") or {}
    d_start = parse_date(period.get("startDate"))
    d_end = parse_date(period.get("endDate"))
    any_concessio = d_award.year if d_award else (d_start.year if d_start else None)

    # Unitat gestora UAB (seguint la convenció nuevoUuid: coManagingOrganizations[0] si existeix, sinó managingOrganization)
    mo_uuid = ((aw.get("managingOrganization") or {}).get("uuid")) or ""
    co_orgs = [c.get("uuid") for c in (aw.get("coManagingOrganizations") or []) if isinstance(c, dict) and c.get("uuid")]
    effective_uab_uuid = co_orgs[0] if co_orgs else mo_uuid
    mo_info = internal_orgs.get(effective_uab_uuid, {}) or internal_orgs.get(mo_uuid, {})
    unitat_uab = mo_info.get("nom") or ""
    tipus_unitat_uab = mo_info.get("tipus") or ""
    ambit_uab = mo_info.get("ambit") or ""

    # IP i equip UAB
    holders = aw.get("awardHolders") or []
    ips: list[str] = []
    coips: list[str] = []
    first_holder = ""
    person_ids: set[str] = set()
    for h in holders:
        if not isinstance(h, dict):
            continue
        pu = ((h.get("person") or {}).get("uuid"))
        if pu:
            person_ids.add(pu)
        nm = h.get("name") or {}
        fn = str(nm.get("firstName") or "").strip()
        ln = str(nm.get("lastName") or "").strip()
        full = f"{ln}, {fn}".strip(", ") if (ln or fn) else ""
        if full and not first_holder:
            first_holder = full
        role_str = normalize_text(pick_lang((h.get("role") or {}).get("term")))
        if full:
            if any(k in role_str for k in ("co investigador", "co principal")):
                coips.append(full)
            elif "investigador a principal" in role_str or "principal investigator" in role_str:
                ips.append(full)
    ip_uab = "; ".join(dict.fromkeys(ips)) or "; ".join(dict.fromkeys(coips)) or first_holder
    n_membres = len(person_ids) if person_ids else len(holders)

    # Naturalesa EGRETA
    natures = [pick_lang(n.get("term")) for n in (aw.get("natureTypes") or []) if isinstance(n, dict)]
    naturalesa = "; ".join(x for x in natures if x)

    # Imports (total vs part institucional UAB)
    import_total = 0.0
    import_uab = 0.0
    for f in aw.get("fundings") or []:
        if not isinstance(f, dict):
            continue
        import_total += parse_float((f.get("awardedAmount") or {}).get("value"))
        for fc in f.get("fundingCollaborators") or []:
            if not isinstance(fc, dict):
                continue
            collab_u = ((fc.get("collaborator") or {}).get("uuid"))
            if collab_u == UAB_ORG_UUID:
                import_uab += parse_float((fc.get("institutionalPart") or {}).get("value"))

    # Projectes enllaçats via cluster
    cluster_uuid = ((aw.get("cluster") or {}).get("uuid")) or ""
    linked_projects = projects_by_cluster.get(cluster_uuid, [])
    project_uuid = linked_projects[0]["project_uuid"] if linked_projects else ""

    # Descripció (Award o Project)
    desc = clean_description(aw.get("descriptions"))
    if not desc:
        for lp in linked_projects:
            if lp["description"]:
                desc = lp["description"]
                break

    # Convocatòria i Programa (via Applications -> FundingOpportunities)
    fopp_titles: list[str] = []
    fopp_types: list[str] = []
    fopp_progs: list[str] = []
    app_ext_collabs: list[dict[str, Any]] = []
    app_ext_orgs: list[str] = []
    for a_ref in aw.get("applications") or []:
        if not isinstance(a_ref, dict):
            continue
        app_doc = apps.get(a_ref.get("uuid") or "")
        if not app_doc:
            continue
        app_ext_collabs.extend(app_doc["ext_collabs"])
        app_ext_orgs.extend(app_doc["ext_orgs"])
        fopp = fopps.get(app_doc["fopp_uuid"])
        if fopp:
            if fopp["title"]:
                fopp_titles.append(fopp["title"])
            if fopp["type"]:
                fopp_types.append(fopp["type"])
            if fopp["programa"]:
                fopp_progs.append(fopp["programa"])

    return {
        "award_uuid": aw.get("uuid") or "",
        "pure_id": aw.get("pureId") or "",
        "project_uuid": project_uuid,
        "codi_referencia": codi_ref,
        "titol": pick_lang(aw.get("title")),
        "tipus_award": pick_lang((aw.get("type") or {}).get("term")),
        "categoria": aw.get("categoria") or "",
        "naturalesa_egreta": naturalesa,
        "estat_workflow": ((aw.get("workflow") or {}).get("step")) or "",
        "any_concessio": any_concessio,
        "data_inici": d_start.isoformat() if d_start else "",
        "data_fi": d_end.isoformat() if d_end else "",
        "import_total_eur": round(import_total, 2),
        "import_uab_eur": round(import_uab, 2),
        "unitat_gestora_uab": unitat_uab,
        "tipus_unitat_uab": tipus_unitat_uab,
        "ambit_uab_esfera": ambit_uab,
        "ip_uab": ip_uab,
        "equip_uab_n_membres": n_membres,
        "descripcio_resum": desc,
        "convocatoria_nom": "; ".join(dict.fromkeys(fopp_titles)),
        "convocatoria_tipus": "; ".join(dict.fromkeys(fopp_types)),
        "programa_convocatoria": "; ".join(dict.fromkeys(fopp_progs)),
        "linked_projects": linked_projects,
        "app_ext_collabs": app_ext_collabs,
        "app_ext_orgs": app_ext_orgs,
    }


def collect_award_external_roles(
    aw: dict[str, Any],
    meta: dict[str, Any],
) -> tuple[dict[str, dict[str, Any]], str, int]:
    """
    Recull totes les entitats externes vinculades a un Award (i als seus Projects/Applications)
    amb el seu rol (Finançador vs Soci/Col·laborador) i determina si la UAB lidera el consorci.
    """
    roles_by_ext: dict[str, dict[str, Any]] = {}

    def ensure_entry(u: str) -> dict[str, Any]:
        return roles_by_ext.setdefault(u, {
            "is_funder": False,
            "is_partner": False,
            "partner_roles": [],
            "is_external_lead": False,
        })

    # 1. Funders i External fundingCollaborators
    all_partner_uuids: set[str] = set()
    for f in aw.get("fundings") or []:
        if not isinstance(f, dict):
            continue
        fu = ((f.get("funder") or {}).get("uuid"))
        if fu:
            ensure_entry(fu)["is_funder"] = True
        for fc in f.get("fundingCollaborators") or []:
            if not isinstance(fc, dict):
                continue
            collab = fc.get("collaborator") or {}
            if collab.get("systemName") == "ExternalOrganization" and collab.get("uuid"):
                eu = collab["uuid"]
                all_partner_uuids.add(eu)
                e = ensure_entry(eu)
                e["is_partner"] = True
                if "Soci / Col·laborador finançament" not in e["partner_roles"]:
                    e["partner_roles"].append("Soci / Col·laborador finançament")

    # 2. Award.collaborators & externalOrganizations
    has_any_collab_list = bool(aw.get("collaborators"))
    internal_lead = False
    external_lead = False

    for c in aw.get("collaborators") or []:
        if not isinstance(c, dict):
            continue
        disc = c.get("typeDiscriminator")
        if disc == "InternalCollaboratorAssociation" and c.get("leadCollaborator"):
            internal_lead = True
        elif disc == "ExternalCollaboratorAssociation":
            eu = ((c.get("externalOrganization") or {}).get("uuid"))
            if eu:
                all_partner_uuids.add(eu)
                e = ensure_entry(eu)
                e["is_partner"] = True
                r_txt = pick_lang((c.get("type") or {}).get("term")) or "Col·laborador"
                if r_txt not in e["partner_roles"]:
                    e["partner_roles"].append(r_txt)
                if c.get("leadCollaborator"):
                    e["is_external_lead"] = True
                    external_lead = True

    for eo in aw.get("externalOrganizations") or []:
        if isinstance(eo, dict) and eo.get("uuid"):
            eu = eo["uuid"]
            all_partner_uuids.add(eu)
            e = ensure_entry(eu)
            e["is_partner"] = True
            if "Entitat col·laboradora" not in e["partner_roles"]:
                e["partner_roles"].append("Entitat col·laboradora")

    # 3. Linked Projects collaborators
    for lp in meta["linked_projects"]:
        if lp["has_collabs"]:
            has_any_collab_list = True
        if lp["internal_lead"]:
            internal_lead = True
        for ec in lp["ext_collabs"]:
            eu = ec["uuid"]
            all_partner_uuids.add(eu)
            e = ensure_entry(eu)
            e["is_partner"] = True
            if ec["role"] not in e["partner_roles"]:
                e["partner_roles"].append(ec["role"])
            if ec["is_lead"]:
                e["is_external_lead"] = True
                external_lead = True

    # 4. Linked Applications collaborators & externalOrganizations
    for ec in meta["app_ext_collabs"]:
        eu = ec["uuid"]
        all_partner_uuids.add(eu)
        e = ensure_entry(eu)
        e["is_partner"] = True
        if ec["role"] not in e["partner_roles"]:
            e["partner_roles"].append(ec["role"])
        if ec["is_lead"]:
            e["is_external_lead"] = True
            external_lead = True

    for eu in meta["app_ext_orgs"]:
        all_partner_uuids.add(eu)
        e = ensure_entry(eu)
        e["is_partner"] = True
        if "Entitat col·laboradora (sol·licitud)" not in e["partner_roles"]:
            e["partner_roles"].append("Entitat col·laboradora (sol·licitud)")

    if not has_any_collab_list and not all_partner_uuids:
        uab_lider = "Individual / Sense consorci extern"
    elif internal_lead:
        uab_lider = "Sí (UAB coordinadora/líder)"
    elif external_lead:
        uab_lider = "No (Coordinat per soci extern)"
    elif all_partner_uuids:
        uab_lider = "Consorci sense líder marcat"
    else:
        uab_lider = "Sí"

    return roles_by_ext, uab_lider, len(all_partner_uuids)


def is_resigned_award(aw: dict[str, Any]) -> bool:
    """Comprova si l'Award està declinat o marcat com a renunciat."""
    st = ((aw.get("status") or {}).get("typeDiscriminator")) or ""
    if st == "DeclinedAwardStatus":
        return True
    for kg in aw.get("keywordGroups") or []:
        for kc in kg.get("keywordContainers") or []:
            uri = ((kc.get("structuredKeyword") or {}).get("uri")) or ""
            if "resigned" in uri.lower():
                return True
    return False


# ---------------------------------------------------------------------------
# Resum complementari de Prestació de Serveis (Excel RC0025R)
# ---------------------------------------------------------------------------

def summarize_rc0025r_prestacions() -> dict[str, Any]:
    """Llegeix el resum agregat de Prestació de Serveis de l'Excel RC0025R."""
    summary = {"disponible": False, "anys": {}, "total_contractes": 0, "total_import": 0.0}
    if not RC0025R_PATH.exists():
        return summary
    wb = openpyxl.load_workbook(RC0025R_PATH, read_only=True, data_only=True)
    ws = wb.worksheets[0]
    seen_ids_by_year: dict[int, dict[str, float]] = collections.defaultdict(dict)
    for idx, row in enumerate(ws.iter_rows(values_only=True)):
        if idx < 6 or len(row) <= 21:
            continue
        if row[5] != "Prestació de Serveis":
            continue
        yr = row[1]
        if not isinstance(yr, (int, float)):
            continue
        yr_int = int(yr)
        cid = str(row[18] or f"row_{idx}").strip()
        imp = parse_float(row[21])
        prev = seen_ids_by_year[yr_int].get(cid, 0.0)
        if imp > prev:
            seen_ids_by_year[yr_int][cid] = imp
    wb.close()

    summary["disponible"] = True
    for yr_int in sorted(seen_ids_by_year.keys()):
        cnt = len(seen_ids_by_year[yr_int])
        tot = round(sum(seen_ids_by_year[yr_int].values()), 2)
        summary["anys"][yr_int] = {"count": cnt, "import": tot}
        summary["total_contractes"] += cnt
        summary["total_import"] = round(summary["total_import"] + tot, 2)
    return summary


# ---------------------------------------------------------------------------
# Exportació CSV i Excel formatat
# ---------------------------------------------------------------------------

def write_csv(path: Path, headers: list[str], rows: list[list[Any]]) -> None:
    """Escriu un fitxer CSV UTF-8 amb BOM per compatibilitat directa amb Excel i GIS."""
    with path.open("w", encoding="utf-8-sig", newline="") as f:
        writer = csv.writer(f, delimiter=",", quoting=csv.QUOTE_MINIMAL)
        writer.writerow(headers)
        writer.writerows(rows)


def style_data_sheet(
    ws,
    headers: list[str],
    rows: list[list[Any]],
    currency_cols: set[int] | None = None,
    int_cols: set[int] | None = None,
) -> None:
    """Escriu capçalera corporativa UAB, dades, filtres automàtics i amplades ajustades."""
    currency_cols = currency_cols or set()
    int_cols = int_cols or set()

    header_fill = PatternFill("solid", fgColor="006B3F")  # Verd corporatiu UAB
    header_font = Font(name="Calibri", size=10, bold=True, color="FFFFFF")
    body_font = Font(name="Calibri", size=10, color="111827")
    zebra_fill = PatternFill("solid", fgColor="F3F8F5")
    thin_border = Border(
        left=Side(style="thin", color="D1D5DB"),
        right=Side(style="thin", color="D1D5DB"),
        top=Side(style="thin", color="D1D5DB"),
        bottom=Side(style="thin", color="D1D5DB"),
    )

    ws.append(headers)
    ws.row_dimensions[1].height = 26
    for col_idx in range(1, len(headers) + 1):
        c = ws.cell(row=1, column=col_idx)
        c.fill = header_fill
        c.font = header_font
        c.alignment = Alignment(horizontal="center", vertical="center", wrap_text=True)
        c.border = thin_border

    col_max_len = [len(h) for h in headers]
    for r_idx, row_data in enumerate(rows, start=2):
        ws.append(row_data)
        is_even = (r_idx % 2 == 0)
        for col_idx, val in enumerate(row_data, start=1):
            c = ws.cell(row=r_idx, column=col_idx)
            c.font = body_font
            c.border = thin_border
            if is_even:
                c.fill = zebra_fill
            if (col_idx - 1) in currency_cols:
                c.number_format = "#,##0.00"
                c.alignment = Alignment(horizontal="right", vertical="top")
            elif (col_idx - 1) in int_cols:
                c.number_format = "#,##0"
                c.alignment = Alignment(horizontal="right", vertical="top")
            else:
                c.alignment = Alignment(horizontal="left", vertical="top", wrap_text=False)

            s_len = len(str(val or ""))
            if s_len > col_max_len[col_idx - 1]:
                col_max_len[col_idx - 1] = min(s_len, 48)

    ws.freeze_panes = "A2"
    if rows:
        ws.auto_filter.ref = f"A1:{get_column_letter(len(headers))}{len(rows) + 1}"

    for i, max_len in enumerate(col_max_len, start=1):
        ws.column_dimensions[get_column_letter(i)].width = max(12, min(max_len + 4, 50))


def build_summary_sheet(
    wb: openpyxl.Workbook,
    eo_stats: dict[str, Any],
    diag: dict[str, Any],
    conv_rows: list[list[Any]],
    proj_rows: list[list[Any]],
    agent_rows: list[list[Any]],
    rc0025r_summary: dict[str, Any],
) -> None:
    """Construeix la pestanya 0_Resum_i_Metodologia amb taules diagnòstiques i metodològiques."""
    ws = wb.create_sheet("0_Resum_i_Metodologia", 0)
    title_font = Font(name="Calibri", size=14, bold=True, color="006B3F")
    sec_font = Font(name="Calibri", size=11, bold=True, color="1F3864")
    hdr_fill = PatternFill("solid", fgColor="1F3864")
    hdr_font = Font(name="Calibri", size=10, bold=True, color="FFFFFF")
    bold_font = Font(name="Calibri", size=10, bold=True)
    norm_font = Font(name="Calibri", size=10)
    thin = Border(
        left=Side(style="thin", color="D1D5DB"),
        right=Side(style="thin", color="D1D5DB"),
        top=Side(style="thin", color="D1D5DB"),
        bottom=Side(style="thin", color="D1D5DB"),
    )

    def write_table(start_row: int, title: str, headers: list[str], data: list[list[Any]]) -> int:
        ws.cell(row=start_row, column=1, value=title).font = sec_font
        r = start_row + 1
        for ci, h in enumerate(headers, start=1):
            c = ws.cell(row=r, column=ci, value=h)
            c.fill = hdr_fill
            c.font = hdr_font
            c.border = thin
            c.alignment = Alignment(horizontal="center", vertical="center")
        r += 1
        for row_vals in data:
            for ci, v in enumerate(row_vals, start=1):
                c = ws.cell(row=r, column=ci, value=v)
                c.font = norm_font
                c.border = thin
                if isinstance(v, (int, float)):
                    c.number_format = "#,##0.00" if isinstance(v, float) else "#,##0"
                    c.alignment = Alignment(horizontal="right")
            r += 1
        return r + 1

    ws.cell(row=1, column=1, value="Mapa d'Interaccions UAB — Agents del Territori Català (Dades EGRETA / Kraken)").font = title_font
    ws.cell(row=2, column=1, value=f"Data d'extracció: {dt.date.today().isoformat()} | Base de dades: kraken (ymir.uab.cat:8000)").font = norm_font

    r = 4
    r = write_table(
        r,
        "1. Resum Executiu de Cobertura i Volum",
        ["Indicador", "Valor", "Notes / Abast"],
        [
            ["Total Organitzacions Externes a EGRETA (kraken)", eo_stats["total_eo"], "Registres totals a ExternalOrganizations"],
            ["Placeholders exclosos ('Sense entitat', 'Clients varis', etc.)", eo_stats["placeholders_exclosos"], "Exclosos abans de la detecció territorial"],
            ["Autoreferències internes UAB excloses", eo_stats["uab_autoreferencies_excloses"], "Perfil mirall UAB i òrgans interns dins ExternalOrganizations (mantenint fundacions i consorcis)"],
            ["Agents detectats al Territori Català", eo_stats["catalunya_total"], "Codi postal (08/17/25/43), municipi o indicador inequívoc"],
            ["Agents catalans amb almenys 1 conveni o projecte UAB", len(agent_rows), "Inclosos a la pestanya 3_Mapa_Agents_Territori"],
            ["Relacions Conveni – Agent català (files netes)", len(conv_rows), f"{diag['unique_convenis_cat']} convenis únics (validated/approved, no declinats)"],
            ["Relacions Projecte – Agent català (files netes)", len(proj_rows), f"{diag['unique_projects_cat']} projectes únics (consorcis i/o finançadors catalans)"],
            ["Projects sense Award vinculat detectats", diag["orphan_projects"], "Registres a Projects sense correspondència a Awards.cluster.uuid"],
        ],
    )

    # Taula 2: Convenis per Publico-Públic vs Publico-Privat (amb recompte únic i imports deduplicats per award_uuid)
    conv_int = collections.Counter()
    conv_aw_ids: dict[str, set[str]] = collections.defaultdict(set)
    conv_imp_uab: dict[str, float] = collections.defaultdict(float)
    conv_imp_tot: dict[str, float] = collections.defaultdict(float)
    for row in conv_rows:
        ti = row[11]  # tipus_interaccio
        aw_id = row[0]
        conv_int[ti] += 1
        if aw_id not in conv_aw_ids[ti]:
            conv_aw_ids[ti].add(aw_id)
            conv_imp_tot[ti] += float(row[9] or 0)
            conv_imp_uab[ti] += float(row[10] or 0)
    r = write_table(
        r,
        "2. Convenis al Territori Català per Caràcter Publico-Públic / Publico-Privat",
        ["Tipus d'Interacció", "N Relacions (Conveni-Agent)", "Convenis Únics", "Import Total (€)", "Import Part UAB (€)"],
        [[k, v, len(conv_aw_ids[k]), round(conv_imp_tot[k], 2), round(conv_imp_uab[k], 2)] for k, v in conv_int.most_common()],
    )

    # Taula 3: Projectes per Rol i Tipus d'Interacció (deduplicant imports per award_uuid dins de cada grup)
    proj_rol = collections.Counter()
    proj_aw_ids: dict[tuple[str, str], set[str]] = collections.defaultdict(set)
    proj_imp_tot: dict[tuple[str, str], float] = collections.defaultdict(float)
    proj_imp_uab: dict[tuple[str, str], float] = collections.defaultdict(float)
    for row in proj_rows:
        key = (row[13], row[12])  # (rol_agent_projecte, tipus_interaccio)
        aw_id = row[0]
        proj_rol[key] += 1
        if aw_id not in proj_aw_ids[key]:
            proj_aw_ids[key].add(aw_id)
            proj_imp_tot[key] += float(row[10] or 0)
            proj_imp_uab[key] += float(row[11] or 0)
    r = write_table(
        r,
        "3. Projectes de Recerca amb Agents del Territori Català (per Rol i Caràcter)",
        ["Rol de l'Agent Territorial", "Tipus d'Interacció", "N Relacions", "Projectes Únics", "Import Total (€)", "Import Part UAB (€)"],
        [[k[0], k[1], v, len(proj_aw_ids[k]), round(proj_imp_tot[k], 2), round(proj_imp_uab[k], 2)] for k, v in proj_rol.most_common()],
    )

    # Taula 4: Distribució territorial d'agents catalans actius
    prov_cnt = collections.Counter(row[7] for row in agent_rows)  # provincia
    r = write_table(
        r,
        "4. Agents Catalans Actius per Província",
        ["Província", "Agents Catalans Actius (amb conveni o projecte)"],
        [[k, v] for k, v in prov_cnt.most_common()],
    )

    # Taula 5: Distribució per tipologia d'agent (ExternalOrganization.type)
    tip_cnt = collections.Counter(row[4] for row in agent_rows)  # agent_tipologia_egreta
    r = write_table(
        r,
        "5. Agents Catalans Actius per Tipologia d'Entitat (EGRETA)",
        ["Tipologia d'Agent", "Agents Actius", "Convenis (N relacions)", "Projectes (N relacions)"],
        [
            [
                k,
                v,
                sum(1 for row in conv_rows if row[16] == k),
                sum(1 for row in proj_rows if row[22] == k),
            ]
            for k, v in tip_cnt.most_common()
        ],
    )

    # Taula 6: Evolució temporal (2020-2026 + històric anterior) deduplicant per award_uuid
    def bucket_year(y: Any) -> str:
        if y is None:
            return "Sense any"
        if y >= 2020:
            return str(y)
        return "Anterior a 2020"

    yr_conv_ids: dict[str, set[str]] = collections.defaultdict(set)
    yr_conv_imp: dict[str, float] = collections.defaultdict(float)
    for row in conv_rows:
        b = bucket_year(row[6])
        if row[0] not in yr_conv_ids[b]:
            yr_conv_ids[b].add(row[0])
            yr_conv_imp[b] += float(row[10] or 0)

    yr_proj_ids: dict[str, set[str]] = collections.defaultdict(set)
    yr_proj_imp: dict[str, float] = collections.defaultdict(float)
    for row in proj_rows:
        b = bucket_year(row[7])
        if row[0] not in yr_proj_ids[b]:
            yr_proj_ids[b].add(row[0])
            yr_proj_imp[b] += float(row[11] or 0)

    all_buckets = sorted(
        set(yr_conv_ids.keys()) | set(yr_proj_ids.keys()),
        key=lambda x: (0 if x.isdigit() else 1, -int(x) if x.isdigit() else x),
    )
    r = write_table(
        r,
        "6. Evolució Temporal d'Interaccions Úniques amb el Territori (2020–2026 i Històric)",
        ["Període / Any", "Convenis Únics", "Import UAB Convenis (€)", "Projectes Únics", "Import UAB Projectes (€)"],
        [
            [b, len(yr_conv_ids[b]), round(yr_conv_imp[b], 2), len(yr_proj_ids[b]), round(yr_proj_imp[b], 2)]
            for b in all_buckets
        ],
    )

    # Taula 7: Mètode de detecció territorial sobre tot el catàleg català
    r = write_table(
        r,
        "7. Qualitat de la Detecció Territorial (Catàleg complet d'ExternalOrganizations a Catalunya)",
        ["Mètode de Detecció", "Organitzacions"],
        [[k, v] for k, v in eo_stats["metodes"].most_common()],
    )

    # Taula 8: Resum complementari Prestació de Serveis (Excel RC0025R)
    if rc0025r_summary.get("disponible"):
        rc_rows = [
            [yr, d["count"], d["import"]]
            for yr, d in sorted(rc0025r_summary["anys"].items(), reverse=True)
        ]
        rc_rows.append(["TOTAL RC0025R", rc0025r_summary["total_contractes"], rc0025r_summary["total_import"]])
        r = write_table(
            r,
            "8. Font Complementària: Facturació per Prestació de Serveis (Excel RC0025R — sense entitat externa estructurada)",
            ["Any", "N Prestacions de Serveis", "Import Real (€)"],
            rc_rows,
        )

    # Notes metodològiques per al mapa d'interaccions
    ws.cell(row=r, column=1, value="9. Guia Metodològica: Com llegir Demanda -> Resposta UAB -> Concreció de la Relació").font = sec_font
    notes = [
        "• Demanda del territori: columnes 'titol_conveni / titol_projecte', 'descripcio_resum', 'convocatoria_nom' i 'naturalesa_egreta'.",
        "• Agent que genera o co-crea la demanda: 'agent_nom', 'tipus_interaccio' (Publico-Públic vs Publico-Privat), 'agent_tipologia_egreta', 'municipi', 'provincia', 'coordenades' i 'ror_id'.",
        "• Resposta de la UAB: 'unitat_gestora_uab' (departament, institut, centre), 'ambit_uab_esfera' (UAB vs Esfera UAB), 'ip_uab' i 'equip_uab_n_membres'.",
        "• Concreció de la relació: 'tipus_award', 'rol_agent' / 'rol_agent_projecte' (Soci de consorci vs Finançador), 'uab_es_lider', 'any_concessio', 'data_inici'/'data_fi' i 'import_uab_eur'.",
        "• Nota sobre duplicats en imports: quan un mateix conveni o projecte té més d'un agent català (p. ex. un consorci multi-soci), cada fila representa un parell (Interacció, Agent). Per sumar imports sense duplicar, agrupeu o feu distinct per 'award_uuid'.",
    ]
    for i, note in enumerate(notes, start=1):
        c = ws.cell(row=r + i, column=1, value=note)
        c.font = bold_font if i == 5 else norm_font

    ws.column_dimensions["A"].width = 62
    ws.column_dimensions["B"].width = 32
    ws.column_dimensions["C"].width = 34
    ws.column_dimensions["D"].width = 24
    ws.column_dimensions["E"].width = 24
    ws.column_dimensions["F"].width = 24


# ---------------------------------------------------------------------------
# Flux principal
# ---------------------------------------------------------------------------

def main() -> int:
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    uri = get_mongo_uri()
    print("[1/6] Connectant a MongoDB (kraken)...")
    client = pymongo.MongoClient(uri, serverSelectionTimeoutMS=15000)
    db = client["kraken"]

    print("[2/6] Carregant catàlegs d'Organizations, ExternalOrganizations, Projects, Applications i FundingOpportunities...")
    internal_orgs, _ = load_internal_orgs(db)
    catalan_agents, eo_stats = load_catalan_agents(db)
    projects_by_cluster, total_projects = load_projects_by_cluster(db)
    apps, fopps = load_applications_and_fopps(db)
    print(f"      -> {len(internal_orgs)} unitats UAB | {eo_stats['catalunya_total']} agents catalans identificats (sobre {eo_stats['total_eo']} EO)")

    # Comprovar si hi ha Projects sense cap Award corresponent
    award_clusters = set(db["Awards"].distinct("cluster.uuid"))
    orphan_projects = sum(
        1 for cl_u, plist in projects_by_cluster.items() if cl_u not in award_clusters for _ in plist
    )

    print("[3/6] Processant Awards (Convenis i Projectes de Recerca)...")
    conv_headers = [
        "award_uuid", "pure_id", "codi_referencia", "tipus_conveni", "naturalesa_egreta",
        "estat_workflow", "any_concessio", "data_inici", "data_fi", "import_total_eur",
        "import_uab_eur", "tipus_interaccio", "agent_caracter", "origen_caracter",
        "agent_uuid", "agent_nom", "agent_tipologia_egreta", "rol_agent", "municipi",
        "codi_postal", "provincia", "coordenades", "metode_deteccio_territori",
        "agent_ror", "agent_nif_cif", "titol_conveni", "descripcio_resum",
        "convocatoria_nom", "unitat_gestora_uab", "tipus_unitat_uab", "ambit_uab_esfera",
        "ip_uab", "equip_uab_n_membres",
    ]
    proj_headers = [
        "award_uuid", "project_uuid", "pure_id", "codi_referencia", "tipus_projecte",
        "naturalesa_egreta", "estat_workflow", "any_concessio", "data_inici", "data_fi",
        "import_total_eur", "import_uab_eur", "tipus_interaccio", "rol_agent_projecte",
        "rol_detall_consorci", "uab_es_lider", "total_socis_externs", "altres_socis_territori",
        "agent_caracter", "origen_caracter", "agent_uuid", "agent_nom",
        "agent_tipologia_egreta", "municipi", "codi_postal", "provincia", "coordenades",
        "metode_deteccio_territori", "agent_ror", "agent_nif_cif", "titol_projecte",
        "descripcio_resum", "convocatoria_nom", "programa_convocatoria",
        "unitat_gestora_uab", "tipus_unitat_uab", "ambit_uab_esfera", "ip_uab", "equip_uab_n_membres",
    ]

    conv_rows: list[list[Any]] = []
    proj_rows: list[list[Any]] = []
    unique_convenis_cat: set[str] = set()
    unique_projects_cat: set[str] = set()

    # Agregats per construir la taula mestra d'agents catalans
    agent_agg: dict[str, dict[str, Any]] = {}

    def ensure_agent_agg(agent: dict[str, Any]) -> dict[str, Any]:
        u = agent["uuid"]
        if u not in agent_agg:
            agent_agg[u] = {
                "info": agent,
                "conv_uuids": set(),
                "conv_import_uab": 0.0,
                "conv_years": [],
                "proj_consorci_uuids": set(),
                "proj_funder_uuids": set(),
                "proj_all_uuids": set(),
                "proj_import_uab": 0.0,
                "proj_years": [],
                "uab_units": collections.Counter(),
                "sample_titles": [],
            }
        return agent_agg[u]

    target_types = CONVENI_TYPES | PROJECT_TYPES
    aw_query = {
        "workflow.step": {"$in": ["validated", "approved"]},
        "$or": [
            {"type.term.ca_ES": {"$in": list(target_types)}},
            {"categoria": "Ajudes no competitives"},
        ],
    }
    aw_proj = {
        "uuid": 1, "pureId": 1, "title": 1, "type": 1, "categoria": 1, "workflow": 1,
        "status": 1, "keywordGroups": 1, "awardDate": 1, "actualPeriod": 1, "identifiers": 1,
        "natureTypes": 1, "descriptions": 1, "managingOrganization": 1, "coManagingOrganizations": 1,
        "awardHolders": 1, "fundings": 1, "collaborators": 1, "externalOrganizations": 1,
        "cluster": 1, "applications": 1,
    }

    for aw in db["Awards"].find(aw_query, aw_proj):
        if is_resigned_award(aw):
            continue
        tipus_ca = pick_lang((aw.get("type") or {}).get("term"))
        cat = aw.get("categoria") or ""
        is_conveni = tipus_ca in CONVENI_TYPES or cat == "Ajudes no competitives"
        is_project = (not is_conveni) and (tipus_ca in PROJECT_TYPES)
        if not is_conveni and not is_project:
            continue

        meta = extract_award_metadata(aw, internal_orgs, projects_by_cluster, apps, fopps)
        roles_by_ext, uab_lider, total_socis_ext = collect_award_external_roles(aw, meta)

        cat_uuids_in_award = [u for u in roles_by_ext if u in catalan_agents]
        if not cat_uuids_in_award:
            continue

        if is_conveni:
            unique_convenis_cat.add(meta["award_uuid"])
            for eu in cat_uuids_in_award:
                ag = catalan_agents[eu]
                r_info = roles_by_ext[eu]
                if r_info["is_funder"] and r_info["is_partner"]:
                    rol_str = "Finançador i Col·laborador"
                elif r_info["is_funder"]:
                    rol_str = "Finançador / Contractant"
                else:
                    rol_str = "Col·laborador"

                conv_rows.append([
                    meta["award_uuid"], meta["pure_id"], meta["codi_referencia"],
                    meta["tipus_award"], meta["naturalesa_egreta"], meta["estat_workflow"],
                    meta["any_concessio"], meta["data_inici"], meta["data_fi"],
                    meta["import_total_eur"], meta["import_uab_eur"],
                    ag["tipus_interaccio"], ag["caracter"], ag["origen_caracter"],
                    ag["uuid"], ag["nom"], ag["tipologia"], rol_str,
                    ag["municipi"], ag["codi_postal"], ag["provincia"], ag["coordenades"],
                    ag["metode"], ag["ror_id"], ag["nif_cif"], meta["titol"],
                    meta["descripcio_resum"], meta["convocatoria_nom"],
                    meta["unitat_gestora_uab"], meta["tipus_unitat_uab"], meta["ambit_uab_esfera"],
                    meta["ip_uab"], meta["equip_uab_n_membres"],
                ])

                agg = ensure_agent_agg(ag)
                if meta["award_uuid"] not in agg["conv_uuids"]:
                    agg["conv_uuids"].add(meta["award_uuid"])
                    agg["conv_import_uab"] += meta["import_uab_eur"]
                    if meta["any_concessio"]:
                        agg["conv_years"].append(meta["any_concessio"])
                if meta["unitat_gestora_uab"]:
                    agg["uab_units"][meta["unitat_gestora_uab"]] += 1
                if meta["titol"] and len(agg["sample_titles"]) < 3 and meta["titol"] not in agg["sample_titles"]:
                    agg["sample_titles"].append(meta["titol"])

        elif is_project:
            unique_projects_cat.add(meta["award_uuid"])
            for eu in cat_uuids_in_award:
                ag = catalan_agents[eu]
                r_info = roles_by_ext[eu]
                if r_info["is_funder"] and r_info["is_partner"]:
                    rol_proj = "Finançador i Soci de consorci"
                elif r_info["is_partner"]:
                    rol_proj = "Soci / Col·laborador de consorci"
                else:
                    rol_proj = "Entitat finançadora / Promotora"

                altres_cat = "; ".join(
                    catalan_agents[ou]["nom"] for ou in cat_uuids_in_award if ou != eu
                )
                rol_detall = "; ".join(r_info["partner_roles"]) if r_info["partner_roles"] else ("Finançador" if r_info["is_funder"] else "")

                proj_rows.append([
                    meta["award_uuid"], meta["project_uuid"], meta["pure_id"],
                    meta["codi_referencia"], meta["tipus_award"], meta["naturalesa_egreta"],
                    meta["estat_workflow"], meta["any_concessio"], meta["data_inici"], meta["data_fi"],
                    meta["import_total_eur"], meta["import_uab_eur"], ag["tipus_interaccio"],
                    rol_proj, rol_detall, uab_lider, total_socis_ext, altres_cat,
                    ag["caracter"], ag["origen_caracter"], ag["uuid"], ag["nom"],
                    ag["tipologia"], ag["municipi"], ag["codi_postal"], ag["provincia"],
                    ag["coordenades"], ag["metode"], ag["ror_id"], ag["nif_cif"],
                    meta["titol"], meta["descripcio_resum"], meta["convocatoria_nom"],
                    meta["programa_convocatoria"], meta["unitat_gestora_uab"],
                    meta["tipus_unitat_uab"], meta["ambit_uab_esfera"], meta["ip_uab"],
                    meta["equip_uab_n_membres"],
                ])

                agg = ensure_agent_agg(ag)
                if meta["award_uuid"] not in agg["proj_all_uuids"]:
                    agg["proj_all_uuids"].add(meta["award_uuid"])
                    agg["proj_import_uab"] += meta["import_uab_eur"]
                    if meta["any_concessio"]:
                        agg["proj_years"].append(meta["any_concessio"])
                if r_info["is_partner"]:
                    agg["proj_consorci_uuids"].add(meta["award_uuid"])
                if r_info["is_funder"]:
                    agg["proj_funder_uuids"].add(meta["award_uuid"])
                if meta["unitat_gestora_uab"]:
                    agg["uab_units"][meta["unitat_gestora_uab"]] += 1
                if meta["titol"] and len(agg["sample_titles"]) < 3 and meta["titol"] not in agg["sample_titles"]:
                    agg["sample_titles"].append(meta["titol"])

    # Ordenar files per any descendent, import descendent
    conv_rows.sort(key=lambda r: (r[6] or 0, r[10] or 0.0), reverse=True)
    proj_rows.sort(key=lambda r: (r[7] or 0, r[11] or 0.0), reverse=True)

    print("[4/6] Construint taula mestra d'Agents del Territori Català...")
    agent_headers = [
        "agent_uuid", "agent_nom", "agent_caracter", "tipus_interaccio",
        "agent_tipologia_egreta", "municipi", "codi_postal", "provincia",
        "coordenades", "ror_id", "nif_cif", "metode_deteccio_territori",
        "n_convenis", "import_convenis_uab_eur", "anys_convenis",
        "n_projectes_soci_consorci", "n_projectes_financador",
        "import_projectes_uab_eur", "anys_projectes",
        "total_interaccions", "principals_unitats_uab", "mostra_titols_interaccions",
    ]
    agent_rows: list[list[Any]] = []
    for u, agg in agent_agg.items():
        ag = agg["info"]
        n_conv = len(agg["conv_uuids"])
        n_pc = len(agg["proj_consorci_uuids"])
        n_pf = len(agg["proj_funder_uuids"])
        n_p_tot = len(agg["proj_all_uuids"])
        cy = agg["conv_years"]
        py = agg["proj_years"]
        cy_str = f"{min(cy)}-{max(cy)}" if cy else ""
        py_str = f"{min(py)}-{max(py)}" if py else ""
        top_units = "; ".join(f"{name} ({cnt})" for name, cnt in agg["uab_units"].most_common(3))
        samples = " | ".join(agg["sample_titles"])
        agent_rows.append([
            ag["uuid"], ag["nom"], ag["caracter"], ag["tipus_interaccio"],
            ag["tipologia"], ag["municipi"], ag["codi_postal"], ag["provincia"],
            ag["coordenades"], ag["ror_id"], ag["nif_cif"], ag["metode"],
            n_conv, round(agg["conv_import_uab"], 2), cy_str,
            n_pc, n_pf, round(agg["proj_import_uab"], 2), py_str,
            n_conv + n_p_tot, top_units, samples,
        ])
    agent_rows.sort(key=lambda r: (r[19], r[13] + r[17]), reverse=True)

    print("[5/6] Llegint resum complementari RC0025R (Prestació de Serveis)...")
    rc0025r_summary = summarize_rc0025r_prestacions()

    print("[6/6] Escrivint fitxers Excel i CSV a exports/mapa_territori/...")
    write_csv(OUTPUT_DIR / "1_convenis_territori_catala.csv", conv_headers, conv_rows)
    write_csv(OUTPUT_DIR / "2_projectes_recerca_territori_catala.csv", proj_headers, proj_rows)
    write_csv(OUTPUT_DIR / "3_agents_territori_catala_resum.csv", agent_headers, agent_rows)

    wb = openpyxl.Workbook()
    # Eliminar full per defecte després de crear els nous
    default_ws = wb.active

    diag = {
        "unique_convenis_cat": len(unique_convenis_cat),
        "unique_projects_cat": len(unique_projects_cat),
        "orphan_projects": orphan_projects,
        "total_projects": total_projects,
    }
    build_summary_sheet(wb, eo_stats, diag, conv_rows, proj_rows, agent_rows, rc0025r_summary)

    ws_conv = wb.create_sheet("1_Convenis_Territori")
    style_data_sheet(ws_conv, conv_headers, conv_rows, currency_cols={9, 10}, int_cols={1, 6, 32})

    ws_proj = wb.create_sheet("2_Projectes_Recerca_Territori")
    style_data_sheet(ws_proj, proj_headers, proj_rows, currency_cols={10, 11}, int_cols={2, 7, 16, 38})

    ws_ag = wb.create_sheet("3_Mapa_Agents_Territori")
    style_data_sheet(ws_ag, agent_headers, agent_rows, currency_cols={13, 17}, int_cols={12, 15, 16, 19})

    wb.remove(default_ws)
    xlsx_path = OUTPUT_DIR / "mapa_interaccions_uab_territori_catala.xlsx"
    wb.save(xlsx_path)

    # Imprimir resum de verificació
    print("\n=== EXPORTACIÓ COMPLETADA ===")
    print(f"Fitxer Excel: {xlsx_path}")
    print(f"Total ExternalOrganizations: {eo_stats['total_eo']} | Excloses (placeholders): {eo_stats['placeholders_exclosos']} | Autoreferències UAB excloses: {eo_stats['uab_autoreferencies_excloses']}")
    print(f"Agents catalans detectats: {eo_stats['catalunya_total']} (mètodes: {dict(eo_stats['metodes'])})")
    print(f"Agents catalans actius (amb convenis/projectes): {len(agent_rows)}")
    print(f"Convenis Territori: {len(conv_rows)} relacions ({len(unique_convenis_cat)} convenis únics)")
    conv_by_int = collections.Counter(r[11] for r in conv_rows)
    print(f"  -> Convenis per tipus d'interacció: {dict(conv_by_int)}")
    print(f"Projectes Recerca Territori: {len(proj_rows)} relacions ({len(unique_projects_cat)} projectes únics)")
    proj_by_rol = collections.Counter(r[13] for r in proj_rows)
    print(f"  -> Projectes per rol de l'agent: {dict(proj_by_rol)}")
    if rc0025r_summary["disponible"]:
        print(f"RC0025R Prestació de Serveis (complementari): {rc0025r_summary['total_contractes']} contractes | {rc0025r_summary['total_import']:,.2f} €")
    client.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
