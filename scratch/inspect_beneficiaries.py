import pymongo

client = pymongo.MongoClient("mongodb://myUserAdmin:Mongoplex.00@ymir.uab.cat:8000/admin")
db = client["kraken"]

col = db["Applications"]

# Let's write a query to see some applications with beneficiary roles
sample_apps = list(col.find({
    "applicants.role.uri": {
        "$in": [
            "/dk/atira/pure/application/roles/application/ben",
            "/dk/atira/pure/application/roles/application/bec",
            "/dk/atira/pure/application/roles/application/can",
            "/dk/atira/pure/application/roles/application/applicant"
        ]
    }
}).limit(5))

print("Sample Applications with Beneficiary roles:")
for app in sample_apps:
    print("App UUID:", app.get("uuid"))
    print("Funding Opp UUID:", app.get("fundingOpportunity", {}).get("uuid") if app.get("fundingOpportunity") else "None")
    print("Reply:", app.get("funderReply"))
    for applicant in app.get("applicants", []):
        print("  - Role:", applicant.get("role", {}).get("uri"), "|", applicant.get("role", {}).get("term", {}).get("ca_ES"))
        print("    Person UUID:", applicant.get("person", {}).get("uuid"))
    print()

client.close()
