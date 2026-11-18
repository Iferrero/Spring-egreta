#!/usr/bin/env python3
"""
Genera `src/main/webapp/mapa-territori-data.js` a partir dels CSV nets i l'Excel
de `exports/mapa_territori/`, amb coordenades geocodificades per municipi català
i estructura normalitzada per índex d'agent perquè la pàgina web funcioni tant
en `file://` com servida des de Spring Boot sense dependències de backend.
"""

from __future__ import annotations

import collections
import csv
import json
import re
import sys
import unicodedata
from pathlib import Path
from typing import Any

import openpyxl


REPO_ROOT = Path(__file__).resolve().parent.parent
EXPORTS_DIR = REPO_ROOT / "exports" / "mapa_territori"
OUT_JS_PATH = REPO_ROOT / "src" / "main" / "webapp" / "mapa-territori-data.js"

# Coordenades (lat, lon) de municipis catalans principals
MUNICIPI_COORDS: dict[str, tuple[float, float]] = {
    # Barcelona i àrea metropolitana / província
    "barcelona": (41.3874, 2.1686),
    "bellaterra": (41.4997, 2.0942),
    "bellaterra cerdanyola del valles": (41.4997, 2.0942),
    "cerdanyola del valles": (41.4911, 2.1408),
    "cerdanyola": (41.4911, 2.1408),
    "sabadell": (41.5463, 2.1086),
    "terrassa": (41.5632, 2.0089),
    "sant cugat del valles": (41.4735, 2.0852),
    "sant cugat": (41.4735, 2.0852),
    "l hospitalet de llobregat": (41.3597, 2.1003),
    "hospitalet de llobregat": (41.3597, 2.1003),
    "hospitalet": (41.3597, 2.1003),
    "badalona": (41.4500, 2.2474),
    "mataro": (41.5381, 2.4445),
    "manresa": (41.7282, 1.8240),
    "vic": (41.9304, 2.2546),
    "granollers": (41.6080, 2.2877),
    "esplugues de llobregat": (41.3769, 2.0869),
    "esplugues": (41.3769, 2.0869),
    "cornella de llobregat": (41.3574, 2.0713),
    "cornella": (41.3574, 2.0713),
    "rubi": (41.4923, 2.0331),
    "el prat de llobregat": (41.3278, 2.0947),
    "el prat": (41.3278, 2.0947),
    "vilanova i la geltru": (41.2244, 1.7259),
    "santa perpetua de mogoda": (41.5349, 2.1820),
    "sant joan despi": (41.3671, 2.0549),
    "castelldefels": (41.2800, 1.9766),
    "mollet del valles": (41.5403, 2.2131),
    "mollet": (41.5403, 2.2131),
    "igualada": (41.5789, 1.6172),
    "sant just desvern": (41.3833, 2.0749),
    "sant feliu de llobregat": (41.3833, 2.0445),
    "gava": (41.3060, 2.0011),
    "barbera del valles": (41.5159, 2.1269),
    "viladecans": (41.3144, 2.0147),
    "vilafranca del penedes": (41.3461, 1.6971),
    "sant quirze del valles": (41.5335, 2.0825),
    "caldes de montbui": (41.6345, 2.1619),
    "santa coloma de gramenet": (41.4515, 2.2081),
    "martorell": (41.4744, 1.9306),
    "castellbisbal": (41.4754, 1.9818),
    "viladecavalls": (41.5579, 1.9554),
    "sant boi de llobregat": (41.3436, 2.0366),
    "sant boi": (41.3436, 2.0366),
    "montcada i reixac": (41.4836, 2.1879),
    "ripollet": (41.4967, 2.1558),
    "sant adria de besos": (41.4307, 2.2216),
    "sitges": (41.2372, 1.8059),
    "molins de rei": (41.4139, 2.0156),
    "parets del valles": (41.5734, 2.2319),
    "castellar del valles": (41.6186, 2.0875),
    "cardedeu": (41.6406, 2.3569),
    "sant celoni": (41.6894, 2.4894),
    "la garriga": (41.6844, 2.2869),
    "canovelles": (41.6182, 2.2817),
    "les franqueses del valles": (41.6373, 2.2966),
    "polinya": (41.5542, 2.1556),
    "palau solita i plegamans": (41.5880, 2.1783),
    "premia de mar": (41.4919, 2.3621),
    "el masnou": (41.4817, 2.3175),
    "vilassar de mar": (41.5037, 2.3924),
    "cabrils": (41.5276, 2.3694),
    "cabrera de mar": (41.5259, 2.3935),
    "alella": (41.4946, 2.2947),
    "tiana": (41.4833, 2.2667),
    "montgat": (41.4667, 2.2833),
    "arenys de mar": (41.5819, 2.5503),
    "calella": (41.6133, 2.6606),
    "pineda de mar": (41.6275, 2.6889),
    "malgrat de mar": (41.6458, 2.7422),
    "berga": (42.1044, 1.8458),
    "manlleu": (42.0006, 2.2836),
    "torello": (42.0494, 2.2625),
    "abrera": (41.5169, 1.9011),
    "esparreguera": (41.5394, 1.8700),
    "olesa de montserrat": (41.5450, 1.8944),
    "sant andreu de la barca": (41.4481, 1.9772),
    "cubelles": (41.2097, 1.6728),
    "sant pere de ribes": (41.2603, 1.7731),
    "vilanova del cami": (41.5731, 1.6414),
    "masquefa": (41.5033, 1.8136),
    "sant vicenc dels horts": (41.3928, 2.0086),
    "matadepera": (41.6025, 2.0258),
    "vacarisses": (41.6075, 1.9183),
    "llica d amunt": (41.6108, 2.2389),
    "vallromanes": (41.5314, 2.2981),
    "sant pol de mar": (41.6025, 2.6242),
    # Girona
    "girona": (41.9794, 2.8214),
    "figueres": (42.2666, 2.9616),
    "olot": (42.1810, 2.4901),
    "blanes": (41.6742, 2.7904),
    "lloret de mar": (41.6999, 2.8457),
    "salt": (41.9742, 2.7931),
    "banyoles": (42.1194, 2.7667),
    "palafrugell": (41.9167, 3.1636),
    "sant feliu de guixols": (41.7833, 3.0333),
    "palamos": (41.8483, 3.1292),
    "roses": (42.2620, 3.1769),
    "ripoll": (42.2008, 2.1903),
    "puigcerda": (42.4317, 1.9281),
    "santa coloma de farners": (41.8625, 2.6656),
    "la bisbal d emporda": (41.9606, 3.0378),
    "torroella de montgri": (42.0419, 3.1267),
    "castello d empuries": (42.2597, 3.0764),
    "cassa de la selva": (41.8894, 2.8744),
    "arbucies": (41.8161, 2.5161),
    "tossa de mar": (41.7219, 2.9317),
    "platja d aro": (41.8172, 3.0681),
    "l escala": (42.1244, 3.1319),
    "besalu": (42.1989, 2.6986),
    "begur": (41.9542, 3.2089),
    "cadaques": (42.2886, 3.2797),
    "camprodon": (42.3125, 2.3653),
    # Lleida
    "lleida": (41.6176, 0.6200),
    "tarrega": (41.6469, 1.1394),
    "balaguer": (41.7911, 0.8056),
    "mollerussa": (41.6303, 0.8961),
    "la seu d urgell": (42.3589, 1.4614),
    "seu d urgell": (42.3589, 1.4614),
    "cervera": (41.6708, 1.2711),
    "solsona": (41.9942, 1.5172),
    "guissona": (41.7847, 1.2883),
    "alcarras": (41.5636, 0.5239),
    "tremp": (42.1672, 0.8950),
    "almacelles": (41.7322, 0.4369),
    "agramunt": (41.7864, 1.0964),
    "les borges blanques": (41.5206, 0.8692),
    "borges blanques": (41.5206, 0.8692),
    "bellpuig": (41.6264, 1.0128),
    "alpicat": (41.6675, 0.5567),
    "vielha": (42.7022, 0.7964),
    "sort": (42.4119, 1.1303),
    "el pont de suert": (42.4083, 0.7408),
    "artesa de segre": (41.8950, 1.0456),
    # Tarragona
    "tarragona": (41.1189, 1.2445),
    "reus": (41.1561, 1.1069),
    "tortosa": (40.8125, 0.5214),
    "el vendrell": (41.2200, 1.5353),
    "vendrell": (41.2200, 1.5353),
    "cambrils": (41.0669, 1.0592),
    "salou": (41.0766, 1.1416),
    "valls": (41.2861, 1.2494),
    "calafell": (41.2014, 1.5683),
    "amposta": (40.7128, 0.5797),
    "vila seca": (41.1114, 1.1456),
    "vilaseca": (41.1114, 1.1456),
    "sant carles de la rapita": (40.6186, 0.5928),
    "la rapita": (40.6186, 0.5928),
    "torredembarra": (41.1456, 1.3967),
    "cunit": (41.1975, 1.6347),
    "montblanc": (41.3764, 1.1619),
    "deltebre": (40.7192, 0.7086),
    "alcanar": (40.5433, 0.4811),
    "la senia": (40.6336, 0.2856),
    "mora d ebre": (41.0928, 0.6408),
    "falset": (41.1469, 0.8203),
    "constanti": (41.1544, 1.2131),
    "riudoms": (41.1392, 1.0519),
    "roquetes": (40.8206, 0.5019),
    "ulldecona": (40.5975, 0.4472),
    "vandellos": (41.0211, 0.8314),
    "l hospitalet de l infant": (40.9917, 0.9225),
}

CP3_COORDS: dict[str, tuple[float, float]] = {
    "080": (41.3874, 2.1686),  # Barcelona ciutat
    "081": (41.5200, 2.1200),  # Vallès Occidental / Cerdanyola / Sabadell
    "082": (41.5600, 1.9700),  # Vallès Occ. / Bages
    "083": (41.5500, 2.4600),  # Maresme
    "084": (41.6300, 2.3100),  # Vallès Oriental
    "085": (41.9300, 2.2500),  # Osona
    "086": (41.9800, 1.8500),  # Berguedà / Bages nord
    "087": (41.4400, 1.7700),  # Penedès / Anoia / Baix Llobregat nord
    "088": (41.2600, 1.8800),  # Baix Llobregat sud / Garraf
    "089": (41.3900, 2.1100),  # Hospitalet / Esplugues / Cornellà / Badalona
    "170": (41.9794, 2.8214),  # Girona
    "171": (41.9500, 2.8600),  # Gironès / Baix Empordà
    "172": (41.8800, 3.0900),  # Baix Empordà / Costa Brava
    "173": (41.7000, 2.8200),  # La Selva litoral
    "174": (42.1400, 2.8400),  # Selva / Alt Empordà
    "175": (42.2600, 2.1600),  # Ripollès / Cerdanya
    "176": (42.2666, 2.9616),  # Figueres
    "177": (42.3200, 2.9600),  # Alt Empordà
    "178": (42.1600, 2.5500),  # Garrotxa / Pla de l'Estany
    "250": (41.6176, 0.6200),  # Lleida
    "251": (41.6400, 0.6500),  # Segrià
    "252": (41.6800, 1.1500),  # Urgell / Segarra / Pla d'Urgell
    "253": (41.7000, 1.0700),  # Urgell / Noguera
    "254": (41.5200, 0.8700),  # Garrigues
    "255": (42.4200, 0.9000),  # Pallars / Alta Ribagorça / Aran
    "256": (41.8000, 0.8200),  # Noguera
    "257": (42.2400, 1.4600),  # Alt Urgell / Solsonès
    "430": (41.1189, 1.2445),  # Tarragona
    "431": (41.1600, 1.2100),  # Tarragonès
    "432": (41.1561, 1.1069),  # Reus
    "433": (41.1500, 1.0300),  # Baix Camp
    "434": (41.2900, 1.2000),  # Alt Camp / Conca de Barberà
    "435": (40.7800, 0.5600),  # Terres de l'Ebre
    "437": (41.1400, 0.8000),  # Priorat / Ribera d'Ebre
    "438": (41.1800, 1.4400),  # Baix Penedès / Costa Daurada
}

PROV_CENTROIDS: dict[str, tuple[float, float]] = {
    "Barcelona": (41.3874, 2.1686),
    "Girona": (41.9794, 2.8214),
    "Lleida": (41.6176, 0.6200),
    "Tarragona": (41.1189, 1.2445),
    "Catalunya (general)": (41.7200, 1.8200),
}


def normalize_text(value: Any) -> str:
    s = unicodedata.normalize("NFD", str(value or ""))
    s = "".join(ch for ch in s if not unicodedata.category(ch).startswith("M"))
    s = s.lower()
    s = re.sub(r"[^a-z0-9\s]", " ", s, flags=re.ASCII)
    return re.sub(r"\s+", " ", s, flags=re.ASCII).strip()


def resolve_coords(coords_str: str, municipi: str, cp: str, provincia: str) -> tuple[float, float, str]:
    """Resol (lat, lon, origen) amb prioritat: EGRETA -> municipi -> CP3 -> província."""
    if coords_str and "," in coords_str:
        parts = coords_str.split(",")
        try:
            lat = float(parts[0].strip())
            lon = float(parts[1].strip())
            if 40.3 <= lat <= 43.0 and 0.0 <= lon <= 3.5:
                return round(lat, 5), round(lon, 5), "egreta"
        except ValueError:
            pass

    m_norm = normalize_text(municipi)
    if m_norm:
        if m_norm in MUNICIPI_COORDS:
            lat, lon = MUNICIPI_COORDS[m_norm]
            return lat, lon, "municipi"
        for k, (lat, lon) in MUNICIPI_COORDS.items():
            if f" {k} " in f" {m_norm} ":
                return lat, lon, "municipi"

    cp_digits = re.sub(r"[^0-9]", "", cp or "")
    if len(cp_digits) >= 3 and cp_digits[:3] in CP3_COORDS:
        lat, lon = CP3_COORDS[cp_digits[:3]]
        return lat, lon, "cp3"

    lat, lon = PROV_CENTROIDS.get(provincia, PROV_CENTROIDS["Catalunya (general)"])
    return lat, lon, "provincia"


def to_int_or_none(val: str) -> int | None:
    s = (val or "").strip()
    if not s:
        return None
    try:
        return int(float(s))
    except ValueError:
        return None


def to_float(val: str) -> float:
    s = (val or "").strip()
    if not s:
        return 0.0
    try:
        return round(float(s), 2)
    except ValueError:
        return 0.0


def load_summary_meta() -> dict[str, Any]:
    """Llegeix estadístiques globals i taula RC0025R des de l'Excel resum (amb fallback verificat si l'Excel és obert)."""
    fallback_meta: dict[str, Any] = {
        "dataExtraccio": "2026-11-16",
        "stats": {
            "totalEo": 60703,
            "placeholdersExclosos": 29,
            "autoreferenciesExcloses": 1614,
            "agentsCatalunya": 9804,
            "agentsActius": 3820,
            "relacionsConveni": 14260,
            "relacionsProjecte": 14965,
        },
        "rc0025r": [
            [2026, 2590, 3585863.48],
            [2025, 3471, 4610167.27],
            [2024, 3455, 4678637.26],
            [2023, 3522, 4168345.23],
            [2022, 3967, 4616800.42],
            [2021, 3472, 4229343.5],
            [2020, 2017, 3276079.39],
            [2019, 2413, 3354092.19],
            [2018, 2478, 3218054.57],
            [2017, 2332, 3514398.04],
            [2016, 2098, 3155428.28],
            [2015, 1937, 4417103.66],
            ["TOTAL RC0025R", 33752, 46824313.29],
        ],
    }
    xlsx_path = EXPORTS_DIR / "mapa_interaccions_uab_territori_catala.xlsx"
    try:
        wb = openpyxl.load_workbook(xlsx_path, read_only=True, data_only=True)
    except (PermissionError, OSError) as exc:
        print(f"[Avís] No s'ha pogut llegir {xlsx_path.name} ({exc}); s'usen les metadades verificades del resum.")
        return fallback_meta

    ws = wb["0_Resum_i_Metodologia"]
    meta: dict[str, Any] = {
        "dataExtraccio": "",
        "stats": {},
        "rc0025r": [],
    }
    in_rc = False
    for row in ws.iter_rows(values_only=True):
        vals = [v for v in row if v is not None]
        if not vals:
            continue
        first = str(vals[0])
        if first.startswith("Data d'extracció:"):
            meta["dataExtraccio"] = first.split("|")[0].replace("Data d'extracció:", "").strip()
        elif first == "Total Organitzacions Externes a EGRETA (kraken)":
            meta["stats"]["totalEo"] = int(vals[1])
        elif first.startswith("Placeholders exclosos"):
            meta["stats"]["placeholdersExclosos"] = int(vals[1])
        elif first == "Autoreferències internes UAB excloses":
            meta["stats"]["autoreferenciesExcloses"] = int(vals[1])
        elif first == "Agents detectats al Territori Català":
            meta["stats"]["agentsCatalunya"] = int(vals[1])
        elif first.startswith("Agents catalans amb almenys"):
            meta["stats"]["agentsActius"] = int(vals[1])
        elif first.startswith("Relacions Conveni"):
            meta["stats"]["relacionsConveni"] = int(vals[1])
        elif first.startswith("Relacions Projecte"):
            meta["stats"]["relacionsProjecte"] = int(vals[1])
        elif first.startswith("8. Font Complementària"):
            in_rc = True
        elif in_rc and first.startswith("9. Guia Metodològica"):
            in_rc = False
        elif in_rc and len(vals) >= 3 and (isinstance(vals[0], int) or first == "TOTAL RC0025R"):
            meta["rc0025r"].append([vals[0], int(vals[1]), round(float(vals[2]), 2)])
    wb.close()
    return meta


def main() -> int:
    meta = load_summary_meta()

    strings: list[str] = [""]
    str_to_id: dict[str, int] = {"": 0}

    def intern(val: str) -> int:
        s = val or ""
        idx = str_to_id.get(s)
        if idx is None:
            idx = len(strings)
            strings.append(s)
            str_to_id[s] = idx
        return idx

    agents_list: list[list[Any]] = []
    uuid_to_idx: dict[str, int] = {}
    coord_origins = collections.Counter()

    with (EXPORTS_DIR / "3_agents_territori_catala_resum.csv").open("r", encoding="utf-8-sig") as f:
        for row in csv.DictReader(f):
            u = row["agent_uuid"].strip()
            idx = len(agents_list)
            uuid_to_idx[u] = idx
            lat, lon, orig = resolve_coords(row["coordenades"], row["municipi"], row["codi_postal"], row["provincia"])
            coord_origins[orig] += 1
            agents_list.append([
                u,
                row["agent_nom"],
                intern(row["agent_caracter"]),
                intern(row["tipus_interaccio"]),
                intern(row["agent_tipologia_egreta"]),
                intern(row["municipi"]),
                row["codi_postal"],
                intern(row["provincia"]),
                lat,
                lon,
                row["ror_id"],
                row["nif_cif"],
                intern(row["metode_deteccio_territori"]),
                to_int_or_none(row["n_convenis"]) or 0,
                to_float(row["import_convenis_uab_eur"]),
                row["anys_convenis"],
                to_int_or_none(row["n_projectes_soci_consorci"]) or 0,
                to_int_or_none(row["n_projectes_financador"]) or 0,
                to_float(row["import_projectes_uab_eur"]),
                row["anys_projectes"],
                to_int_or_none(row["total_interaccions"]) or 0,
                row["principals_unitats_uab"],
            ])

    conv_awards: list[list[Any]] = []
    conv_aw_map: dict[str, int] = {}
    conv_rels: list[list[Any]] = []
    with (EXPORTS_DIR / "1_convenis_territori_catala.csv").open("r", encoding="utf-8-sig") as f:
        for row in csv.DictReader(f):
            ag_idx = uuid_to_idx.get(row["agent_uuid"].strip())
            if ag_idx is None:
                continue
            aw_u = row["award_uuid"]
            aw_idx = conv_aw_map.get(aw_u)
            if aw_idx is None:
                aw_idx = len(conv_awards)
                conv_aw_map[aw_u] = aw_idx
                conv_awards.append([
                    aw_u,
                    to_int_or_none(row["pure_id"]) or row["pure_id"],
                    row["codi_referencia"],
                    intern(row["tipus_conveni"]),
                    intern(row["naturalesa_egreta"]),
                    to_int_or_none(row["any_concessio"]),
                    row["data_inici"],
                    row["data_fi"],
                    to_float(row["import_total_eur"]),
                    to_float(row["import_uab_eur"]),
                    row["titol_conveni"],
                    row["descripcio_resum"],
                    intern(row["convocatoria_nom"]),
                    intern(row["unitat_gestora_uab"]),
                    intern(row["tipus_unitat_uab"]),
                    intern(row["ambit_uab_esfera"]),
                    intern(row["ip_uab"]),
                    to_int_or_none(row["equip_uab_n_membres"]) or 0,
                ])
            conv_rels.append([aw_idx, ag_idx, intern(row["rol_agent"])])

    proj_awards: list[list[Any]] = []
    proj_aw_map: dict[str, int] = {}
    proj_rels: list[list[Any]] = []
    with (EXPORTS_DIR / "2_projectes_recerca_territori_catala.csv").open("r", encoding="utf-8-sig") as f:
        for row in csv.DictReader(f):
            ag_idx = uuid_to_idx.get(row["agent_uuid"].strip())
            if ag_idx is None:
                continue
            aw_u = row["award_uuid"]
            aw_idx = proj_aw_map.get(aw_u)
            if aw_idx is None:
                aw_idx = len(proj_awards)
                proj_aw_map[aw_u] = aw_idx
                proj_awards.append([
                    aw_u,
                    row["project_uuid"],
                    to_int_or_none(row["pure_id"]) or row["pure_id"],
                    row["codi_referencia"],
                    intern(row["tipus_projecte"]),
                    intern(row["naturalesa_egreta"]),
                    to_int_or_none(row["any_concessio"]),
                    row["data_inici"],
                    row["data_fi"],
                    to_float(row["import_total_eur"]),
                    to_float(row["import_uab_eur"]),
                    intern(row["uab_es_lider"]),
                    to_int_or_none(row["total_socis_externs"]) or 0,
                    row["titol_projecte"],
                    row["descripcio_resum"],
                    intern(row["convocatoria_nom"]),
                    intern(row["programa_convocatoria"]),
                    intern(row["unitat_gestora_uab"]),
                    intern(row["tipus_unitat_uab"]),
                    intern(row["ambit_uab_esfera"]),
                    intern(row["ip_uab"]),
                    to_int_or_none(row["equip_uab_n_membres"]) or 0,
                ])
            proj_rels.append([
                aw_idx,
                ag_idx,
                intern(row["rol_agent_projecte"]),
                intern(row["rol_detall_consorci"]),
                row["altres_socis_territori"],
            ])

    meta["stats"]["convenisUnics"] = len(conv_awards)
    meta["stats"]["projectesUnics"] = len(proj_awards)
    meta["coordOrigins"] = dict(coord_origins)

    payload = {
        "meta": meta,
        "S": strings,
        "agents": agents_list,
        "convAwards": conv_awards,
        "convRels": conv_rels,
        "projAwards": proj_awards,
        "projRels": proj_rels,
    }

    OUT_JS_PATH.parent.mkdir(parents=True, exist_ok=True)
    with OUT_JS_PATH.open("w", encoding="utf-8") as f:
        f.write("// Bundle estàtic normalitzat generat per scripts/build_mapa_territori_web_data.py\n")
        f.write("(function (root) {\n")
        f.write("  var raw = ")
        json.dump(payload, f, ensure_ascii=False, separators=(",", ":"))
        f.write(";\n")
        f.write("""  var S = raw.S;
  var titleBuckets = raw.agents.map(function () { return []; });
  var agents = raw.agents.map(function (a) {
    return [a[0], a[1], S[a[2]], S[a[3]], S[a[4]], S[a[5]], a[6], S[a[7]], a[8], a[9], a[10], a[11], S[a[12]], a[13], a[14], a[15], a[16], a[17], a[18], a[19], a[20], a[21], ""];
  });
  function pushTitle(agIdx, title) {
    var b = titleBuckets[agIdx];
    if (title && b.length < 3 && b.indexOf(title) === -1) b.push(title);
  }
  var convenis = raw.convRels.map(function (r) {
    var aw = raw.convAwards[r[0]];
    pushTitle(r[1], aw[10]);
    return [aw[0], aw[1], aw[2], S[aw[3]], S[aw[4]], aw[5], aw[6], aw[7], aw[8], aw[9], r[1], S[r[2]], aw[10], aw[11], S[aw[12]], S[aw[13]], S[aw[14]], S[aw[15]], S[aw[16]], aw[17]];
  });
  var projectes = raw.projRels.map(function (r) {
    var aw = raw.projAwards[r[0]];
    pushTitle(r[1], aw[13]);
    return [aw[0], aw[1], aw[2], aw[3], S[aw[4]], S[aw[5]], aw[6], aw[7], aw[8], aw[9], aw[10], r[1], S[r[2]], S[r[3]], S[aw[11]], aw[12], r[4], aw[13], aw[14], S[aw[15]], S[aw[16]], S[aw[17]], S[aw[18]], S[aw[19]], S[aw[20]], aw[21]];
  });
  for (var i = 0; i < agents.length; i++) {
    agents[i][22] = titleBuckets[i].join(" | ");
  }
  root.MAPA_TERRITORI_DATA = { meta: raw.meta, agents: agents, convenis: convenis, projectes: projectes };
})(typeof window !== "undefined" ? window : globalThis);
""")

    size_mb = OUT_JS_PATH.stat().st_size / (1024 * 1024)
    print(f"Generat {OUT_JS_PATH} -> {size_mb:.2f} MB (diccionari S: {len(strings)} strings)")
    print(f"Agents: {len(agents_list)} | Coordenades resoltes: {dict(coord_origins)}")
    print(f"Convenis: {len(conv_rels)} ({len(conv_awards)} únics) | Projectes: {len(proj_rels)} ({len(proj_awards)} únics)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
