import pymongo
from collections import defaultdict
from datetime import datetime

client = pymongo.MongoClient("mongodb://myUserAdmin:Mongoplex.00@ymir.uab.cat:8000/admin")
db = client["kraken"]

print("=== INSPECT NON-COMPETITIVE AWARDS WITH VALUE ===")

# Fetch validated/approved awards
cursor = db["Awards"].find(
    {
        "$or": [
            { "type.term.ca_ES": "Conveni extern a la UAB", "workflow.step": "approved" },
            { "type.term.ca_ES": { "$ne": "Conveni extern a la UAB" }, "workflow.step": "validated" }
        ]
    },
    {
        "awardDate": 1,
        "categoria": 1,
        "type.term.ca_ES": 1,
        "fundings": 1
    }
)

years = [2020, 2021, 2022, 2023, 2024, 2025]
stats = {y: defaultdict(float) for y in years}

UAB_UUID = "84443078-1a60-462d-9d0a-b04312afd9eb"

for aw in cursor:
    date_val = aw.get("awardDate")
    if not date_val:
        continue
    year = date_val.year if isinstance(date_val, datetime) else None
    if year in stats:
        cat = aw.get("categoria", "")
        type_ca = aw.get("type", {}).get("term", {}).get("ca_ES", "")
        
        is_nocomp = cat.startswith("Ajudes no competitives") or (cat == "Externs UAB" and type_ca == "Conveni extern a la UAB")
        
        if is_nocomp:
            # Calculate UAB part
            uab_part = 0.0
            fundings = aw.get("fundings", [])
            if isinstance(fundings, dict):
                fundings = [fundings]
            for f in fundings:
                collaborators = f.get("fundingCollaborators", [])
                if isinstance(collaborators, dict):
                    collaborators = [collaborators]
                for col in collaborators:
                    collaborator = col.get("collaborator", {})
                    if collaborator and collaborator.get("uuid") == UAB_UUID:
                        inst_part = col.get("institutionalPart", {})
                        if inst_part:
                            val = inst_part.get("value")
                            if isinstance(val, (int, float)):
                                uab_part += float(val)
                            elif isinstance(val, str):
                                try:
                                    uab_part += float(val)
                                except ValueError:
                                    pass
            
            is_conveni = "conveni" in type_ca.lower() or "concessió conveni" in type_ca.lower()
            if is_conveni:
                stats[year]["convenis"] += uab_part
            else:
                stats[year]["serveis"] += uab_part
                if uab_part > 0:
                    print(f"Non-zero servei: year={year}, type={type_ca}, value={uab_part}")

for y in sorted(stats.keys()):
    print(f"\nYear {y}:")
    for k, v in stats[y].items():
        print(f"  {k}: {v / 1e6:.3f} M€")

client.close()
