import pymongo
import json

client = pymongo.MongoClient("mongodb://myUserAdmin:Mongoplex.00@ymir.uab.cat:8000/admin")
db = client["kraken"]

# Let's run the powertable pipeline for 2024
with open("src/main/resources/mongodb/awards/powertable.json") as f:
    pipeline_def = json.load(f)

# Modify match for award date years
pipeline = pipeline_def["pipeline"]

# Let's run it directly for years 2020-2024
# We find where we match awardDate and add year boundaries if needed, but we can also just match after grouping.
# In the original Java code, it does a builder replace, but let's just run it as is and filter in python.
results = list(db["Awards"].aggregate(pipeline))

# Filter years 2020-2024
powertable_data = [r for r in results if r.get("anio") and 2020 <= r["anio"] <= 2024]

print("=== POWERTABLE TOTALS BY YEAR ===")
by_year = {}
for r in powertable_data:
    y = r["anio"]
    if y not in by_year:
        by_year[y] = {"competitiva": 0.0, "no_competitiva": 0.0, "estatals": 0.0, "internacionals": 0.0}
    
    cat = r.get("categoria", "")
    imp = r.get("import", 0.0)
    
    if cat.startswith("Ajudes competitives"):
        by_year[y]["competitiva"] += imp
        if "nacionals" in cat:
            by_year[y]["estatals"] += imp
        elif "internacionals" in cat:
            by_year[y]["internacionals"] += imp
    elif cat.startswith("Prestaci") or cat == "Ajudes no competitives":
        by_year[y]["no_competitiva"] += imp

for y in sorted(by_year.keys()):
    vals = by_year[y]
    total = vals["competitiva"] + vals["no_competitiva"]
    print(f"Year {y}:")
    print(f"  Competitiva: {vals['competitiva']/1e6:.2f} M€")
    print(f"    Estatals: {vals['estatals']/1e6:.2f} M€")
    print(f"    Internacionals: {vals['internacionals']/1e6:.2f} M€")
    print(f"  No Competitiva: {vals['no_competitiva']/1e6:.2f} M€")
    print(f"  Total: {total/1e6:.2f} M€")

client.close()
