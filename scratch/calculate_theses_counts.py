import pymongo
from collections import defaultdict

client = pymongo.MongoClient("mongodb://myUserAdmin:Mongoplex.00@ymir.uab.cat:8000/admin")
db = client["kraken"]

print("=== DEFENSED THESES BY YEAR ===")
thesis_years = defaultdict(int)
for doc in db["StudentTheses"].find({}, {"awardDate.year": 1}):
    yr = doc.get("awardDate", {}).get("year") if doc.get("awardDate") else None
    if yr:
        thesis_years[yr] += 1

for y, count in sorted(thesis_years.items(), key=lambda x: x[0]):
    if 2015 <= y <= 2026:
        print(f"  Year {y}: {count} defended theses")

client.close()
