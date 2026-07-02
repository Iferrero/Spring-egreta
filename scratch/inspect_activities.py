import pymongo
from collections import defaultdict

client = pymongo.MongoClient("mongodb://myUserAdmin:Mongoplex.00@ymir.uab.cat:8000/admin")
db = client["kraken"]

print("=== INSPECT ACTIVITIES ===")
one_activity = db["Activities"].find_one()
print(one_activity)

# Let's count unique types of activities
activity_types = defaultdict(int)
for doc in db["Activities"].find({}, {"type": 1}).limit(1000):
    term = doc.get("type", {}).get("term", {}).get("ca_ES", "Unknown")
    activity_types[term] += 1

print("\n=== SAMPLE ACTIVITY TYPES (FIRST 1000) ===")
for t, c in sorted(activity_types.items(), key=lambda x: -x[1]):
    print(f"  {t}: {c}")

client.close()
