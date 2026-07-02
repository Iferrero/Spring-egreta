import pymongo
from collections import defaultdict
from datetime import datetime

client = pymongo.MongoClient("mongodb://myUserAdmin:Mongoplex.00@ymir.uab.cat:8000/admin")
db = client["kraken"]

years = [2020, 2021, 2022, 2023, 2024, 2025]
stats = {y: defaultdict(int) for y in years}

# 1. Query Awards
awards_cursor = db["Awards"].find(
    {
        "$or": [
            { "type.term.ca_ES": "Conveni extern a la UAB", "workflow.step": "approved" },
            { "type.term.ca_ES": { "$ne": "Conveni extern a la UAB" }, "workflow.step": "validated" }
        ]
    },
    {
        "awardDate": 1,
        "categoria": 1,
        "type.term.ca_ES": 1
    }
)

for aw in awards_cursor:
    date_val = aw.get("awardDate")
    if not date_val:
        continue
    year = date_val.year if isinstance(date_val, datetime) else None
    if year in stats:
        cat = aw.get("categoria", "")
        type_ca = aw.get("type", {}).get("term", {}).get("ca_ES", "")
        
        is_comp = cat.startswith("Ajudes competitives")
        is_nocomp = cat.startswith("Ajudes no competitives") or cat == "Externs UAB"
        
        if is_comp:
            if "nacionals" in cat:
                stats[year]["estatals"] += 1
            elif "internacionals" in cat:
                stats[year]["internacionals"] += 1
        elif is_nocomp:
            if "conveni" in type_ca.lower():
                stats[year]["convenis"] += 1
            else:
                stats[year]["serveis"] += 1

# 2. Query StudentTheses (defended theses)
for doc in db["StudentTheses"].find({}, {"awardDate.year": 1}):
    yr = doc.get("awardDate", {}).get("year") if doc.get("awardDate") else None
    if yr in stats:
        stats[yr]["theses_defended"] += 1

# 3. Query Persons for new entry predocs
def parse_date(date_val):
    if not date_val: return None
    if isinstance(date_val, datetime): return date_val
    if isinstance(date_val, str):
        try:
            return datetime.strptime(date_val[:10], "%Y-%m-%d")
        except ValueError:
            pass
    return None

persons = list(db["Persons"].find({}, {"staffOrganizationAssociations": 1}))
for p in persons:
    associations = p.get("staffOrganizationAssociations", [])
    if isinstance(associations, dict):
        associations = [associations]
        
    for assoc in associations:
        emp_type = assoc.get("employmentType", {})
        if emp_type:
            term_ca = emp_type.get("term", {}).get("ca_ES", "")
            norm = term_ca.lower()
            # Is predoctoral?
            if "predoctoral" in norm or "en formaci" in norm or "fpi" in norm or "fpu" in norm or "novell" in norm or "la caixa" in norm or "pif" in norm or "estudiant de doctorat" in norm or "dgu" in norm:
                period = assoc.get("period", {})
                start_date = parse_date(period.get("startDate"))
                if start_date:
                    start_year = start_date.year
                    if start_year in stats:
                        stats[start_year]["new_predocs"] += 1
                        break # count person at most once for their first start year

print("=== CALCULATED ACTIVITAT STATS BY YEAR ===")
for yr in sorted(stats.keys()):
    print(f"\nYear {yr}:")
    for k, v in sorted(stats[yr].items()):
        print(f"  {k}: {v}")

client.close()
