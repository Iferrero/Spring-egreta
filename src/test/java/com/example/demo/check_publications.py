import pymongo
from datetime import datetime, date

client = pymongo.MongoClient("mongodb://myUserAdmin:Mongoplex.00@ymir.uab.cat:8000/kraken?authSource=admin")
db = client["kraken"]

def get_vigent_criteria():
    # period.endDate is null, exists=False, or > today
    # simplified for python:
    today_str = date.today().isoformat()
    return {
        "$or": [
            {"period.endDate": {"$in": [None, ""]}},
            {"period.endDate": {"$exists": False}},
            {"period.endDate": {"$gt": today_str}},
            {"period.endDate": {"$gt": datetime.utcnow()}}
        ]
    }

def get_periode_criteria(year):
    # startDate <= Dec-31-year AND (endDate >= Jan-1-year or missing)
    # simplified for python:
    start_bound = f"{year}-12-31T23:59:59"
    end_bound = f"{year}-01-01T00:00:00"
    return {
        "$and": [
            {"period.startDate": {"$lte": start_bound}},
            {"$or": [
                {"period.endDate": {"$in": [None, ""]}},
                {"period.endDate": {"$exists": False}},
                {"period.endDate": {"$gte": end_bound}}
            ]}
        ]
    }

for filter_name, use_periode in [("vigent", False), ("periode", True)]:
    print(f"\n=== FILTER MODE: {filter_name} ===")
    for year in [2022, 2023]:
        assoc_criteria = get_periode_criteria(year) if use_periode else get_vigent_criteria()
        
        # 1. Get person uuids
        person_filter = {
            "staffOrganizationAssociations": {"$elemMatch": assoc_criteria}
        }
        person_uuids = [doc["uuid"] for doc in db["Persons"].find(person_filter, {"uuid": 1}) if "uuid" in doc]
        
        print(f"Year {year}: Found {len(person_uuids)} active persons")
        
        if not person_uuids:
            print(f"Year {year}: Q1/Q2 count = 0 (no persons found)")
            continue
            
        # 2. Get publications matching these person uuids
        pub_year_criteria = {
            "$or": [
                {"publicationDate.year": year},
                {"$and": [
                    {"publicationDate.year": {"$in": [None, ""]}},
                    {"submissionYear": year}
                ]}
            ]
        }
        
        pub_filter = {
            "workflow.step": "approved",
            "type.term.ca_ES": "Article",
            "contributors.person.uuid": {"$in": person_uuids}
        }
        # merge pub_year_criteria
        pub_filter.update(pub_year_criteria)
        
        pub_uuids = [doc["uuid"] for doc in db["Researchoutputs"].find(pub_filter, {"uuid": 1}) if "uuid" in doc]
        
        # 3. Find Q1/Q2 articles
        # Let's inspect journal quartiles for these publications
        print(f"Year {year}: Found {len(pub_uuids)} publications total")
        
        # For simplicity, count how many have Q1/Q2 in their journal relationship
        # Let's look up how quartiles are resolved for a sample of these publications
        sample_pubs = list(db["Researchoutputs"].find({"uuid": {"$in": pub_uuids[:100]}}))
        # (This is just a diagnostic script)
