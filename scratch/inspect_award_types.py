import pymongo
from collections import defaultdict

client = pymongo.MongoClient("mongodb://myUserAdmin:Mongoplex.00@ymir.uab.cat:8000/admin")
db = client["kraken"]

print("=== UNIQUE TYPES AND CATEGORIES IN MONGO AWARDS ===")
cursor = db["Awards"].find({}, {"type.term.ca_ES": 1, "categoria": 1})
types_cats = defaultdict(set)
for doc in cursor:
    type_val = doc.get("type", {}).get("term", {}).get("ca_ES", "Unknown")
    cat_val = doc.get("categoria", "Unknown")
    types_cats[type_val].add(cat_val)

for t, cats in sorted(types_cats.items()):
    print(f"Type: {t} -> Categories: {list(cats)}")

client.close()
