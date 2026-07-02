import pymongo

client = pymongo.MongoClient("mongodb://myUserAdmin:Mongoplex.00@ymir.uab.cat:8000/admin")
db = client["kraken"]

print("=== SEARCHING 'prestaci' EVERYWHERE ===")
collections = db.list_collection_names()

for col in collections:
    # Try searching for category or type or title containing prestaci
    # To be fast, let's query first 10000 docs
    sample_docs = list(db[col].find().limit(5000))
    found = False
    for doc in sample_docs:
        doc_str = str(doc).lower()
        if "prestaci" in doc_str:
            print(f"Found in collection '{col}':")
            print(f"  Sample doc: {doc}")
            found = True
            break
            
client.close()
