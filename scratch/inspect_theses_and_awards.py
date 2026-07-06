import pymongo
from collections import defaultdict
from datetime import datetime

client = pymongo.MongoClient("mongodb://myUserAdmin:Mongoplex.00@ymir.uab.cat:8000/admin")
db = client["kraken"]

print("=== INSPECT STUDENT THESES ===")
one_thesis = db["StudentTheses"].find_one()
print(one_thesis)

# Let's inspect unique steps/statuses of StudentTheses
theses_statuses = defaultdict(int)
for doc in db["StudentTheses"].find({}, {"workflow": 1}):
    step = doc.get("workflow", {}).get("step", "No step")
    theses_statuses[step] += 1
print("\n=== THESES WORKFLOW STEPS ===")
for s, count in theses_statuses.items():
    print(f"  {s}: {count}")

# Check date fields in StudentTheses
print("\n=== SAMPLE DATES IN THESES ===")
for doc in db["StudentTheses"].find({}, {"awardDate": 1, "created": 1, "defenceDate": 1, "period": 1}).limit(5):
    print("Thesis date sample:", doc)

print("\n=== INSPECT AWARDS COUNTS BY YEAR & CATEGORY ===")
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
        "type.term.ca_ES": 1
    }
)

counts = {y: defaultdict(int) for y in [2020, 2021, 2022, 2023, 2024, 2025]}
for aw in cursor:
    date_val = aw.get("awardDate")
    if not date_val:
        continue
    year = date_val.year if isinstance(date_val, datetime) else None
    if year in counts:
        cat = aw.get("categoria", "Unknown")
        type_term = aw.get("type", {}).get("term", {}).get("ca_ES", "")
        
        if cat == "Ajudes competitives nacionals":
            counts[year]["estatals"] += 1
        elif cat == "Ajudes competitives internacionals":
            counts[year]["internacionals"] += 1
            
        if type_term in ["Concessió conveni", "Conveni extern a la UAB"] or cat == "Ajudes no competitives":
            counts[year]["convenis"] += 1

for y in sorted(counts.keys()):
    print(f"Year {y}:")
    for k, v in counts[y].items():
        print(f"  {k}: {v}")

client.close()
