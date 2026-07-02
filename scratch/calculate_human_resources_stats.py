import pymongo
from collections import defaultdict
from datetime import datetime

client = pymongo.MongoClient("mongodb://myUserAdmin:Mongoplex.00@ymir.uab.cat:8000/admin")
db = client["kraken"]

# Let's retrieve all persons with their associations and gender
persons = list(db["Persons"].find(
    {},
    {
        "gender": 1,
        "staffOrganizationAssociations": 1
    }
))

def is_male(g_str):
    if not g_str: return False
    g_str = g_str.lower()
    return "home" in g_str or "male" in g_str or "hombre" in g_str or "masculi" in g_str or g_str == "m"

def is_female(g_str):
    if not g_str: return False
    g_str = g_str.lower()
    return "dona" in g_str or "female" in g_str or "mujer" in g_str or "femeni" in g_str or g_str == "f"

def parse_date(date_val):
    if not date_val:
        return None
    if isinstance(date_val, datetime):
        return date_val
    if isinstance(date_val, str):
        try:
            return datetime.strptime(date_val[:10], "%Y-%m-%d")
        except ValueError:
            pass
    return None

# We will calculate statistics for years 2020 to 2025
years = [2020, 2021, 2022, 2023, 2024, 2025]

# Category definitions based on ca_ES term
def get_category(term):
    if not term:
        return None
    term = term.lower()
    
    # 1. PDI estable (Catedratics, Titulars, Agregats)
    if "catedr" in term or "titular" in term or "agregat" in term or "agregad" in term:
        return "pdi_estable"
        
    # 2. Postdoctoral
    if "postdoctoral" in term or "cajal" in term or "beatriu" in term or "cierva" in term or "doctor distingit" in term:
        return "postdoc"
        
    # 3. Predoctoral / Comunitat doctoral
    if "predoctoral" in term or "en formaci" in term or "fpi" in term or "fpu" in term or "novell" in term or "la caixa" in term or "pif" in term or "estudiant de doctorat" in term or "dgu" in term:
        return "predoc"
        
    # 4. PTGAS / Suport recerca
    if "suport a la recerca" in term or "tècnic/a" in term or "tecnic/a" in term or "suport recerca" in term:
        return "ptgas"
        
    return "other"

stats = {y: defaultdict(lambda: {"hombres": 0, "mujeres": 0, "total": 0}) for y in years}

for p in persons:
    gender_obj = p.get("gender")
    gender_str = ""
    if isinstance(gender_obj, dict):
        gender_str = gender_obj.get("term", {}).get("ca_ES", "")
    elif isinstance(gender_obj, str):
        gender_str = gender_obj
        
    is_m = is_male(gender_str)
    is_f = is_female(gender_str)
    
    associations = p.get("staffOrganizationAssociations", [])
    if isinstance(associations, dict):
        associations = [associations]
        
    # Find unique active category per person per year
    for yr in years:
        year_start = datetime(yr, 1, 1)
        year_end = datetime(yr, 12, 31)
        
        active_categories = set()
        for assoc in associations:
            # Check period validity
            period = assoc.get("period", {})
            start_date = parse_date(period.get("startDate"))
            end_date = parse_date(period.get("endDate"))
            
            # Check if active in this year
            is_active = True
            if start_date and start_date > year_end:
                is_active = False
            if end_date and end_date < year_start:
                is_active = False
                
            if is_active:
                emp_type = assoc.get("employmentType", {})
                if emp_type:
                    term_ca = emp_type.get("term", {}).get("ca_ES")
                    cat = get_category(term_ca)
                    if cat and cat != "other":
                        active_categories.add(cat)
                        
        # We assign the person to their active categories for this year
        for cat in active_categories:
            cat_stats = stats[yr][cat]
            cat_stats["total"] += 1
            if is_m:
                cat_stats["hombres"] += 1
            elif is_f:
                cat_stats["mujeres"] += 1

print("=== CALCULATED STATS BY YEAR ===")
for yr in sorted(stats.keys()):
    print(f"\nYear {yr}:")
    for cat in ["pdi_estable", "postdoc", "predoc", "ptgas"]:
        ys = stats[yr][cat]
        pct_f = (ys["mujeres"] / ys["total"] * 100.0) if ys["total"] > 0 else 0.0
        print(f"  {cat.upper()}: {ys['total']} (Dones: {ys['mujeres']}, Homes: {ys['hombres']}, {pct_f:.1f}% dones)")

client.close()
