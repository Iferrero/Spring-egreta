import pymongo
from collections import defaultdict
from datetime import datetime

client = pymongo.MongoClient("mongodb://myUserAdmin:Mongoplex.00@ymir.uab.cat:8000/admin")
db = client["kraken"]

print("=== SEARCHING PRESTACIO DE SERVEIS IN AWARDS ===")
cursor = db["Awards"].find(
    {
        "categoria": { "$regex": "prestaci", "$options": "i" }
    },
    {
        "awardDate": 1,
        "categoria": 1,
        "type.term.ca_ES": 1,
        "fundings": 1,
        "workflow": 1
    }
).limit(10)

for doc in cursor:
    print(doc)

print("\n=== UNIQUE CATEGORIES IN AWARDS ===")
cats = db["Awards"].distinct("categoria")
for c in cats:
    print(f"  Category: {c}")

client.close()
