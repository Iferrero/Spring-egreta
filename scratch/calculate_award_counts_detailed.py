import pymongo
from collections import defaultdict
from datetime import datetime

client = pymongo.MongoClient("mongodb://myUserAdmin:Mongoplex.00@ymir.uab.cat:8000/admin")
db = client["kraken"]

print("=== DETAILED AWARD COUNTS BY TYPE AND YEAR ===")
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
        type_ca = aw.get("type", {}).get("term", {}).get("ca_ES", "Unknown")
        counts[year][type_ca] += 1

for y in sorted(counts.keys()):
    print(f"\nYear {y}:")
    for t, c in sorted(counts[y].items(), key=lambda x: -x[1]):
        print(f"  {t}: {c}")

client.close()
