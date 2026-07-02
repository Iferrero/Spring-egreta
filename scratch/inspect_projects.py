import pymongo
from collections import defaultdict

client = pymongo.MongoClient("mongodb://myUserAdmin:Mongoplex.00@ymir.uab.cat:8000/admin")
db = client["kraken"]

print("=== INSPECT PROJECTS ===")
one_project = db["Projects"].find_one()
print(one_project)

# Let's count unique types of projects
project_types = defaultdict(int)
for doc in db["Projects"].find({}, {"type": 1}).limit(1000):
    term = doc.get("type", {}).get("term", {}).get("ca_ES", "Unknown")
    project_types[term] += 1

print("\n=== SAMPLE PROJECT TYPES (FIRST 1000) ===")
for t, c in sorted(project_types.items(), key=lambda x: -x[1]):
    print(f"  {t}: {c}")

client.close()
