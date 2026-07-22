import pymongo

client = pymongo.MongoClient('mongodb://myUserAdmin:Mongoplex.00@ymir.uab.cat:8000/admin')
db = client['kraken']

print("=== All distinct status.reason in Awards ===")
pipeline = [
    {"$match": {"status.reason": {"$exists": True, "$ne": None, "$ne": ""}}},
    {"$group": {
        "_id": {
            "reason": "$status.reason",
            "typeDiscriminator": "$status.typeDiscriminator"
        },
        "count": {"$sum": 1},
        "sampleTitle": {"$first": "$title.ca_ES"},
        "sampleUuid": {"$first": "$uuid"},
        "sampleType": {"$first": "$type.term.ca_ES"},
        "sampleCategory": {"$first": "$categoria"}
    }},
    {"$sort": {"count": -1}}
]

results = list(db.Awards.aggregate(pipeline))
print(f"Total distinct (reason, statusType) pairs in Awards: {len(results)}")

print("\nTop 20 most frequent closure reasons:")
for r in results[:20]:
    reason = r['_id']['reason']
    st_type = r['_id']['typeDiscriminator']
    cnt = r['count']
    sample_t = r['sampleTitle'] or ''
    print(f"[{cnt}x] [{st_type}] {reason} (Ex: {sample_t[:40]})")

client.close()
