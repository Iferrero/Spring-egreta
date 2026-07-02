import pymongo
from collections import defaultdict

client = pymongo.MongoClient("mongodb://myUserAdmin:Mongoplex.00@ymir.uab.cat:8000/admin")
db = client["kraken"]

# 1. Load organizations to determine if they belong to Esfera
sphere_types = {
    "Centres amb conveni de participació en l'esfera UAB",
    "Empresa Esfera",
    "Centres de recerca en el campus de la UAB",
    "Centres de recerca participats",
    "Centres del CSIC amb conveni amb la UAB"
}

all_orgs = list(db["Organizations"].find({}, {"uuid": 1, "type.term.ca_ES": 1, "parents": 1, "lifecycle": 1}))
all_orgs_map = {o["uuid"]: o for o in all_orgs if "uuid" in o}

def belongs_to_esfera(org_doc):
    if not org_doc:
        return False
    # Check type
    org_type = org_doc.get("type", {}).get("term", {}).get("ca_ES", "")
    if org_type in sphere_types:
        return True
    
    # Check parents hierarchy
    parents = org_doc.get("parents", [])
    visited = set()
    while parents:
        p_uuid = parents[0].get("uuid")
        if p_uuid in visited:
            break
        visited.add(p_uuid)
        
        p_doc = all_orgs_map.get(p_uuid)
        if p_doc:
            p_type = p_doc.get("type", {}).get("term", {}).get("ca_ES", "")
            if p_type in sphere_types:
                return True
            parents = p_doc.get("parents", [])
        else:
            break
    return False

org_esfera_map = {}
for uuid, doc in all_orgs_map.items():
    org_esfera_map[uuid] = belongs_to_esfera(doc)

# 2. Fetch validated awards
awards_cursor = db["Awards"].find(
    {
        "$or": [
            { "type.term.ca_ES": "Conveni extern a la UAB", "workflow.step": "approved" },
            { "type.term.ca_ES": { "$ne": "Conveni extern a la UAB" }, "workflow.step": "validated" }
        ]
    },
    {
        "uuid": 1,
        "awardDate": 1,
        "categoria": 1,
        "type.term.ca_ES": 1,
        "managingOrganization.uuid": 1,
        "coManagingOrganizations.uuid": 1,
        "fundings": 1
    }
)

stats = defaultdict(lambda: {
    "total_uab": 0.0, "total_esfera": 0.0,
    "comp_uab": 0.0, "comp_esfera": 0.0,
    "nocomp_uab": 0.0, "nocomp_esfera": 0.0,
    "estatals": 0.0, "internacionals": 0.0,
    "convenis": 0.0, "serveis": 0.0
})

for aw in awards_cursor:
    # Get Year
    date_val = aw.get("awardDate")
    if not date_val:
        continue
    year = date_val.year
    if not (2020 <= year <= 2025):
        continue
        
    # Get institutional part for UAB
    uab_part = 0.0
    fundings = aw.get("fundings", [])
    if isinstance(fundings, dict):
        fundings = [fundings]
    for f in fundings:
        collabs = f.get("fundingCollaborators", [])
        if isinstance(collabs, dict):
            collabs = [collabs]
        for col in collabs:
            collab_ref = col.get("collaborator", {})
            if collab_ref.get("uuid") == "84443078-1a60-462d-9d0a-b04312afd9eb": # UAB
                inst_part = col.get("institutionalPart", {})
                if inst_part:
                    val = inst_part.get("value", 0.0)
                    try:
                        uab_part += float(val)
                    except ValueError:
                        pass

    # Determine if award is Esfera or UAB based on managing organization
    managing_uuid = aw.get("managingOrganization", {}).get("uuid")
    is_esfera = False
    if managing_uuid and org_esfera_map.get(managing_uuid):
        is_esfera = True
    else:
        co_orgs = aw.get("coManagingOrganizations", [])
        if isinstance(co_orgs, dict):
            co_orgs = [co_orgs]
        for co in co_orgs:
            co_uuid = co.get("uuid")
            if co_uuid and org_esfera_map.get(co_uuid):
                is_esfera = True
                break

    # Determine category
    cat = aw.get("categoria", "Sense categoria")
    type_term = aw.get("type", {}).get("term", {}).get("ca_ES", "")
    
    is_comp = cat.startswith("Ajudes competitives")
    is_nocomp = cat.startswith("Ajudes no competitives") or cat == "Externs UAB"

    # Update stats
    year_stats = stats[year]
    
    if is_esfera:
        year_stats["total_esfera"] += uab_part
        if is_comp:
            year_stats["comp_esfera"] += uab_part
        if is_nocomp:
            year_stats["nocomp_esfera"] += uab_part
    else:
        year_stats["total_uab"] += uab_part
        if is_comp:
            year_stats["comp_uab"] += uab_part
        if is_nocomp:
            year_stats["nocomp_uab"] += uab_part

    # Subcategories totals (combined UAB + Esfera)
    if is_comp:
        if "internacionals" in cat:
            year_stats["internacionals"] += uab_part
        elif "nacionals" in cat:
            year_stats["estatals"] += uab_part
    elif is_nocomp:
        if type_term in ["Concessió conveni", "Conveni extern a la UAB"]:
            year_stats["convenis"] += uab_part
        else:
            year_stats["serveis"] += uab_part

# Print results
for year in sorted(stats.keys()):
    ys = stats[year]
    tot = ys["total_uab"] + ys["total_esfera"]
    comp = ys["comp_uab"] + ys["comp_esfera"]
    nocomp = ys["nocomp_uab"] + ys["nocomp_esfera"]
    
    print(f"\nYear {year}:")
    print(f"  Recursos totals: {tot/1e6:.2f} M€ (UAB: {ys['total_uab']/1e6:.2f} M€, Esfera: {ys['total_esfera']/1e6:.2f} M€)")
    print(f"    UAB %: {ys['total_uab']/tot*100:.1f}%, Esfera %: {ys['total_esfera']/tot*100:.1f}%")
    print(f"  Via competitiva: {comp/1e6:.2f} M€ (UAB: {ys['comp_uab']/1e6:.2f} M€, Esfera: {ys['comp_esfera']/1e6:.2f} M€)")
    print(f"    Estatals: {ys['estatals']/1e6:.2f} M€")
    print(f"    Internacionals: {ys['internacionals']/1e6:.2f} M€")
    print(f"  Via no competitiva: {nocomp/1e6:.2f} M€ (UAB: {ys['nocomp_uab']/1e6:.2f} M€, Esfera: {ys['nocomp_esfera']/1e6:.2f} M€)")
    print(f"    Convenis: {ys['convenis']/1e6:.2f} M€")
    print(f"    Altres / Serveis: {ys['serveis']/1e6:.2f} M€")

client.close()
