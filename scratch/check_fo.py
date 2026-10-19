import pymongo

client = pymongo.MongoClient("mongodb://myUserAdmin:Mongoplex.00@ymir.uab.cat:8000/kraken?authSource=admin")
db = client["kraken"]
fo = db["FundingOpportunities"]

all_keys = set()
for d in fo.find({}, {"_id": 1}):
    pass

sample_keys = {}
for d in fo.find().limit(2000):
    for k in d.keys():
        sample_keys[k] = sample_keys.get(k, 0) + 1

print("Total documents in FundingOpportunities:", fo.count_documents({}))
print("\nKeys found in sample of 2000:")
for k, cnt in sorted(sample_keys.items(), key=lambda x: -x[1]):
    print(f"  {k:35} : {cnt} / 2000 docs")
