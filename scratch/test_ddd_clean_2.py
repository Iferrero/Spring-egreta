import pymongo
import re

client = pymongo.MongoClient("mongodb://myUserAdmin:Mongoplex.00@ymir.uab.cat:8000/admin")
db = client["kraken"]

thesis = db["StudentTheses"].find_one({
    "workflow.step": "approved", 
    "links.url": {"$regex": "ddd.uab.cat"}
})

if thesis:
    title = thesis.get("title", {}).get("value", "")
    print(f"Original Title: {title}")
    
    # Let's clean the title
    clean_title = title.replace("\ufffd", " ")
    print(f"Clean Title: {clean_title}")
    
    # Extract words
    words = re.findall(r'\b\w{4,}\b', clean_title)
    print("Words extracted:", words)

client.close()
