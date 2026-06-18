import pymongo

client = pymongo.MongoClient("mongodb://myUserAdmin:Mongoplex.00@ymir.uab.cat:8000/admin")
db = client["kraken"]

print("=== COLLECTIONS ===")
print(db.list_collection_names())

# Inspect one Award document with "Beques"
award_sample = db["Awards"].find_one({"workflow.step": "validated", "type.term.ca_ES": "Beques"})
print("\n=== SAMPLE BEQUES AWARD ===")
if award_sample:
    for k, v in award_sample.items():
        print(f"{k}: {str(v)[:200]}")
else:
    print("No Beques award found")

# Let's search keys of one FundingOpportunities and Applications
fo_sample = db["FundingOpportunities"].find_one()
print("\n=== SAMPLE FUNDING OPPORTUNITY ===")
if fo_sample:
    for k, v in fo_sample.items():
        print(f"{k}: {str(v)[:200]}")

app_sample = db["Applications"].find_one()
print("\n=== SAMPLE APPLICATION ===")
if app_sample:
    for k, v in app_sample.items():
        print(f"{k}: {str(v)[:200]}")

# Function to search for keys containing "nature" in any document in a collection
def search_nature_keys(col_name):
    print(f"\n=== Searching keys in {col_name} ===")
    keys_found = set()
    # Let's inspect first 1000 documents
    for doc in db[col_name].find().limit(1000):
        for k in doc.keys():
            if "nature" in k.lower():
                keys_found.add(k)
            # Recurse one level for dicts
            if isinstance(doc[k], dict):
                for sub_k in doc[k].keys():
                    if "nature" in sub_k.lower():
                        keys_found.add(f"{k}.{sub_k}")
            elif isinstance(doc[k], list):
                for item in doc[k]:
                    if isinstance(item, dict):
                        for sub_k in item.keys():
                            if "nature" in sub_k.lower():
                                keys_found.add(f"{k}.[].{sub_k}")
    print(f"Keys containing 'nature' in {col_name}:", keys_found)

search_nature_keys("Awards")
search_nature_keys("FundingOpportunities")
search_nature_keys("Applications")

client.close()
