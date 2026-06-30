import pymongo
from collections import defaultdict

client = pymongo.MongoClient("mongodb://myUserAdmin:Mongoplex.00@ymir.uab.cat:8000/admin")
db = client["kraken"]

print("=== GENDER VALUES IN PERSONS ===")
gender_counts = defaultdict(int)
for doc in db["Persons"].find({}, {"gender": 1}):
    g_val = doc.get("gender")
    if isinstance(g_val, dict):
        g_val = g_val.get("term", {}).get("ca_ES", "Dict but no ca_ES")
    gender_counts[str(g_val)] += 1

for g, c in sorted(gender_counts.items(), key=lambda x: -x[1]):
    print(f"  {g}: {c}")

print("\n=== UNIQUE EMPLOYMENT TYPES IN PERSONS ===")
emp_types = defaultdict(int)
for doc in db["Persons"].find({}, {"staffOrganizationAssociations.employmentType": 1}):
    associations = doc.get("staffOrganizationAssociations", [])
    if isinstance(associations, dict):
        associations = [associations]
    for assoc in associations:
        emp_type = assoc.get("employmentType", {})
        if emp_type:
            term_ca = emp_type.get("term", {}).get("ca_ES")
            if term_ca:
                emp_types[term_ca] += 1
            else:
                emp_types["No ca_ES term"] += 1

for t, c in sorted(emp_types.items(), key=lambda x: -x[1])[:50]:
    print(f"  {t}: {c}")

client.close()
