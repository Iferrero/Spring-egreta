import pymongo

client = pymongo.MongoClient("mongodb://myUserAdmin:Mongoplex.00@ymir.uab.cat:8000/admin")
db = client["kraken"]

print("=== ALL COLLECTIONS ===")
collections = db.list_collection_names()
print(collections)

for col in collections:
    count = db[col].count_documents({})
    print(f"  Collection '{col}' has {count} documents.")
    
client.close()
