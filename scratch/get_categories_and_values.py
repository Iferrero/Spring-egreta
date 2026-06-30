import pymongo
import json
from collections import defaultdict

client = pymongo.MongoClient("mongodb://myUserAdmin:Mongoplex.00@ymir.uab.cat:8000/admin")
db = client["kraken"]

print("=== UNIQUE CATEGORIES IN AWARDS ===")
categories = db["Awards"].distinct("categoria")
print(categories)

# Let's inspect some documents to see what values we have
print("\n=== TOTALS BY YEAR AND CATEGORIA ===")
pipeline = [
    {
        "$match": {
            "$or": [
                {
                    "type.term.ca_ES": "Conveni extern a la UAB",
                    "workflow.step": "approved"
                },
                {
                    "type.term.ca_ES": { "$ne": "Conveni extern a la UAB" },
                    "workflow.step": "validated"
                }
            ]
        }
    },
    {
        "$match": {
            "type.term.ca_ES": {
                "$ne": "Grups i Xarxes de Recerca externs a la UAB"
            }
        }
    },
    {
        "$project": {
            "anio": {
                "$cond": [
                    { "$ifNull": ["$awardDate", False] },
                    { "$year": "$awardDate" },
                    None
                ]
            },
            "categoria": { "$ifNull": ["$categoria", "Sense categoria"] },
            "type_term": "$type.term.ca_ES",
            "fundings": 1
        }
    },
    {
        "$unwind": "$fundings"
    },
    {
        "$unwind": "$fundings.fundingCollaborators"
    },
    {
        "$match": {
            "fundings.fundingCollaborators.collaborator.uuid": "84443078-1a60-462d-9d0a-b04312afd9eb"
        }
    },
    {
        "$project": {
            "anio": 1,
            "categoria": 1,
            "type_term": 1,
            "importe": {
                "$convert": {
                    "input": "$fundings.fundingCollaborators.institutionalPart.value",
                    "to": "double",
                    "onError": 0,
                    "onNull": 0
                }
            }
        }
    },
    {
        "$group": {
            "_id": {
                "anio": "$anio",
                "categoria": "$categoria",
                "type_term": "$type_term"
            },
            "total": { "$sum": "$importe" }
        }
    }
]

results = list(db["Awards"].aggregate(pipeline))

# Group by year and category
by_year_cat = defaultdict(lambda: defaultdict(float))
for r in results:
    year = r["_id"]["anio"]
    cat = r["_id"]["categoria"]
    total = r["total"]
    by_year_cat[year][cat] += total

for year in sorted(by_year_cat.keys()):
    if year and 2018 <= year <= 2025:
        print(f"\nYear {year}:")
        for cat, total in sorted(by_year_cat[year].items()):
            print(f"  {cat}: {total/1_000_000:.2f} M€ ({total:,.2f} €)")

client.close()
