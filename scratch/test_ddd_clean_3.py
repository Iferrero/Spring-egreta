import pymongo
import urllib.request
import urllib.parse
import re

client = pymongo.MongoClient("mongodb://myUserAdmin:Mongoplex.00@ymir.uab.cat:8000/admin")
db = client["kraken"]

thesis = db["StudentTheses"].find_one({
    "workflow.step": "approved", 
    "links.url": {"$regex": "ddd.uab.cat"}
})

if thesis:
    title = thesis.get("title", {}).get("value", "")
    author = thesis.get("contributors", [{}])[0].get("name", {})
    last_name = author.get("lastName", "")
    
    print(f"Original Title: {title}")
    
    # Let's clean the title: keep only ascii alphanumeric characters
    clean_title = re.sub(r'[^a-zA-Z0-9]', ' ', title)
    # Extract words with length >= 4
    words = re.findall(r'\b\w{4,}\b', clean_title)
    
    # 3. Create a query
    query_parts = []
    if last_name:
        # Keep only ascii letters in the last name
        clean_ln = re.sub(r'[^a-zA-Z]', '', last_name)
        if clean_ln:
            query_parts.append(clean_ln)
    
    query_parts.extend(words[:3]) # Take first 3 significant title words
    search_query = " ".join(query_parts)
    
    print(f"Constructed Query: {search_query}")
    
    encoded_query = urllib.parse.quote(search_query)
    url = f"https://ddd.uab.cat/search?p={encoded_query}"
    print(f"Searching on DDD: {url}")
    
    try:
        req = urllib.request.Request(
            url, 
            headers={'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)'}
        )
        with urllib.request.urlopen(req) as response:
            html = response.read().decode('utf-8')
            
            record_ids = re.findall(r'/record/(\d+)', html)
            if record_ids:
                seen = set()
                unique_ids = [rid for rid in record_ids if not (rid in seen or seen.add(rid))]
                suggested_links = [f"https://ddd.uab.cat/record/{rid}" for rid in unique_ids]
                print(f"Suggested DDD Link(s) from search: {suggested_links[:3]}")
            else:
                print("No record IDs found in search results.")
    except Exception as e:
        print("Error during request:", e)

client.close()
