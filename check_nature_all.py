import pymongo
client = pymongo.MongoClient("mongodb://myUserAdmin:Mongoplex.00@ymir.uab.cat:8000/admin")
db = client["kraken"]

def search_text_in_collections(text):
    print(f"=== SEARCHING FOR '{text}' ===")
    for col_name in db.list_collection_names():
        # Search in all fields
        count = db[col_name].count_documents({"$or": [
            {"type.term.ca_ES": {"$regex": text, "$options": "i"}},
            {"type.term.text.value": {"$regex": text, "$options": "i"}},
            {"natureTypes.term.ca_ES": {"$regex": text, "$options": "i"}},
            {"natureTypes.term.text.value": {"$regex": text, "$options": "i"}},
            {"fundingOpportunity.type.term.ca_ES": {"$regex": text, "$options": "i"}},
            {"fundingOpportunity.type.term.text.value": {"$regex": text, "$options": "i"}},
            {"fOpp.type.term.ca_ES": {"$regex": text, "$options": "i"}},
            {"fOpp.type.term.text.value": {"$regex": text, "$options": "i"}}
        ]})
        if count > 0:
            print(f"Found {count} matches in '{col_name}'")
            # print one sample type field
            doc = db[col_name].find_one({"$or": [
                {"type.term.ca_ES": {"$regex": text, "$options": "i"}},
                {"type.term.text.value": {"$regex": text, "$options": "i"}},
                {"natureTypes.term.ca_ES": {"$regex": text, "$options": "i"}},
                {"natureTypes.term.text.value": {"$regex": text, "$options": "i"}}
            ]})
            if doc:
                if "type" in doc:
                    print("  type:", doc["type"])
                if "natureTypes" in doc:
                    print("  natureTypes:", doc["natureTypes"])

search_text_in_collections("Otros convenios")
client.close()
