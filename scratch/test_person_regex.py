import pymongo

client = pymongo.MongoClient("mongodb://myUserAdmin:Mongoplex.00@ymir.uab.cat:8000/admin")
db = client["kraken"]

print("=== PREDOC FILTER TEST ===")
# Regex for predoc
predoc_regex = "predoctoral|en formaci|fpi|fpu|novell|la caixa|pif|estudiant de doctorat|dgu"

count_all = db["Persons"].count_documents({})
count_filtered = db["Persons"].count_documents({
    "staffOrganizationAssociations.employmentType.term.ca_ES": { "$regex": predoc_regex, "$options": "i" }
})

print(f"Total Persons: {count_all}")
print(f"Predoctoral Persons (Filtered): {count_filtered}")

client.close()
