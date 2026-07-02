import pymongo
from datetime import datetime, date

client = pymongo.MongoClient("mongodb://myUserAdmin:Mongoplex.00@ymir.uab.cat:8000/kraken?authSource=admin")
db = client["kraken"]

def get_vigent_criteria():
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

# Pre-load all journals and JCR data
# (This is just a simplified version to count raw articles matching year criteria)
for filter_name, use_periode in [("vigent", False), ("periode", True)]:
    print(f"\n=== FILTER MODE: {filter_name} ===")
    for year in [2021, 2022, 2023, 2024, 2025]:
        assoc_criteria = get_periode_criteria(year) if use_periode else get_vigent_criteria()
        
        person_filter = {
            "staffOrganizationAssociations": {"$elemMatch": assoc_criteria}
        }
        person_uuids = [doc["uuid"] for doc in db["Persons"].find(person_filter, {"uuid": 1}) if "uuid" in doc]
        
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
        pub_filter.update(pub_year_criteria)
        
        pub_count = db["Researchoutputs"].count_documents(pub_filter)
        print(f"Year {year}: Found {pub_count} approved articles for active persons")
