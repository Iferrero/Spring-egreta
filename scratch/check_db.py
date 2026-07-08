from pymongo import MongoClient

client = MongoClient("mongodb://myUserAdmin:Mongoplex.00@ymir.uab.cat:8000/kraken?authSource=admin")
db = client["kraken"]

pipeline = [
    {
        "$match": {
            "budgets.costCode": { "$regex": "^PO", "$options": "i" }
        }
    },
    {
        "$unwind": "$budgets"
    },
    {
        "$match": {
            "budgets.costCode": { "$regex": "^PO", "$options": "i" }
        }
    },
    {
        "$group": {
            "_id": "$uuid",
            "title": { "$first": "$title.value" },
            "costCode": { "$first": "$budgets.costCode" },
            "type": { "$first": "$type.term.ca_ES" }
        }
    }
]
results = list(db["Awards"].aggregate(pipeline))
print(f"Found {len(results)} awards starting with PO:")
for r in results[:10]:
    title = r.get('title') or "No Title"
    print(f" - Code: {r.get('costCode')}, Type: {r.get('type')}, Title: {title[:60]}")


