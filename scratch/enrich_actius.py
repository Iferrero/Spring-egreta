import csv
import json
from pymongo import MongoClient

client = MongoClient('mongodb://myUserAdmin:Mongoplex.00@ymir.uab.cat:8000/kraken?authSource=admin')
db = client['kraken']
persons_col = db['Persons']

with open('src/main/resources/actius-equipaments-cientifics.csv', encoding='utf-8') as f:
    rows = list(csv.DictReader(f))

js_items = []
resolved_count = 0

for idx, r in enumerate(rows, 1):
    niu = r.get('NIU', '').strip()
    resp = r.get('Responsable projecte / CC', '').strip()
    orgUuid = r.get('UUID organització', '').strip()

    p = None
    if niu:
        p = persons_col.find_one({'identifiers.value': niu})
        if not p:
            p = persons_col.find_one({'identifiers.id': niu})
    if not p and resp:
        parts = resp.split(',')
        if len(parts) >= 2:
            p = persons_col.find_one({'name.lastName': {'$regex': '^' + parts[0].strip(), '$options': 'i'}})

    person_uuid = ''
    person_email = ''
    if p:
        resolved_count += 1
        person_uuid = p.get('uuid', '')
        for assoc in p.get('staffOrganizationAssociations', []):
            for e in assoc.get('emails', []):
                val = e.get('value', '').strip()
                if val and '@' in val:
                    person_email = val.lower()
                    break
            if person_email:
                break

    item = {
        'rowIndex': idx,
        'assetClass': r.get('Descripció classe d\'actiu', ''),
        'assetNumber': r.get('Número actiu fix', ''),
        'titleCat': r.get('Denominació actiu fix(CA)', ''),
        'titleCas': r.get('Denominació actiu fix(ES)', ''),
        'titleAng': r.get('Denominació actiu fix(EN)', ''),
        'location': r.get('Emplaçament', ''),
        'locationDesc': r.get('Descripció emplaçament', ''),
        'costCenter': r.get('Centre de cost', ''),
        'costCenterDesc': r.get('Descripció centre de cost', ''),
        'project': r.get('Projecte', ''),
        'projectDesc': r.get('Descripció Projecte', ''),
        'capYear': r.get('Any capitalització', ''),
        'responsible': resp,
        'currentValue': r.get('Valor actual', ''),
        'accumAmort': r.get('Amortització acumulada', ''),
        'netValue': r.get('Valor net comptable actual', ''),
        'niu': niu,
        'personUuid': person_uuid,
        'email': person_email,
        'orgName': r.get('Nom oficial organització', '') or r.get('Descripció centre de cost', '') or 'Universitat Autònoma de Barcelona',
        'orgAcronym': r.get('Nom oficial organització', '') or r.get('Centre de cost', ''),
        'orgUuid': orgUuid,
        'orgMatched': bool(orgUuid),
        'descCat': r.get('Descripció de l\'equip(CA)', ''),
        'descCas': r.get('Descripción del equipo (ES)', ''),
        'descAng': r.get('Equipment description (EN)', ''),
        'financament': f"Projecte {r.get('Projecte')}: {r.get('Descripció Projecte')}" if r.get('Projecte') and r.get('Projecte') != '#' else '',
        'tipusPrestacioRaw': 'Sense servei',
        'tipusPrestacioUri': '/dk/atira/pure/equipment/loantypes/sense_servei'
    }
    js_items.append(item)

with open('src/main/webapp/actius-data.js', 'w', encoding='utf-8') as f:
    f.write('// Fitxer generat dinàmicament amb els actius fixos científics de la UAB i persones resoltes de Pure\n')
    f.write('window.ACTIUS_DATA = ' + json.dumps(js_items, indent=2, ensure_ascii=False) + ';\n')

print(f'Successfully wrote {len(js_items)} items to actius-data.js. Resolved {resolved_count} persons with UUID and email.')
