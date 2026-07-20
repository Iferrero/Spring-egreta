import os
import pandas as pd

BASE = r"C:\Users\1000788\OneDrive - UAB\Documentos\GitHub\Spring-egreta\src\main\resources\PIDs\2025"

UNIS = {
    "UB":  "UNIVERSIDAD DE BARCELONA",
    "UAB": "UNIVERSIDAD AUTONOMA DE BARCELONA",
    "UPC": "UNIVERSITAT POLITECNICA DE CATALUNYA",
    "UPF": "UNIVERSITAT POMPEU FABRA CCT",
    "UdL": "UNIVERSIDAD DE LLEIDA",
    "UdG": "UNIVERSITAT DE GIRONA",
    "URV": "UNIVERSITAT ROVIRA I VIRGILI",
}

ACUP_PCT = {"UB":0.33,"UAB":0.19,"UPC":0.20,"UPF":0.06,"UdL":0.07,"UdG":0.07,"URV":0.08}

df1 = pd.read_excel(os.path.join(BASE, "Anexo_I_Datos_Generales.xlsx"), sheet_name="Sheet1")
df2 = pd.read_excel(os.path.join(BASE, "Anexo_II_Datos_Economicos.xlsx"), sheet_name="Sheet1")
df3 = pd.read_excel(os.path.join(BASE, "Anexo_III_Solicitudes_sin_ayuda.xlsx"), sheet_name="Sheet1")

# Strip strings
for df in [df1, df2, df3]:
    for col in df.select_dtypes(include='object').columns:
        df[col] = df[col].astype(str).str.strip()

# Build refToTotal from Anexo_II (col B=REFERENCIA, col C=TOTAL)
col_ref2 = df2.columns[1]   # REFERENCIA
col_tot2 = df2.columns[2]   # TOTAL concedido

# Parse numeric (may be formatted as "128.750,00")
def parse_eur(val):
    try:
        s = str(val).replace('.','').replace(',','.').replace(' ','')
        return float(s)
    except:
        return 0.0

df2['_total'] = df2[col_tot2].apply(parse_eur)
refToTotal = dict(zip(df2[col_ref2].astype(str).str.strip(), df2['_total']))

col_ref1  = df1.columns[1]   # REFERENCIA
col_ent1  = df1.columns[4]   # ENTIDAD SOLICITANTE
col_ent3  = df3.columns[4]   # ENTIDAD SOLICITANTE

results = {}
for code, name in UNIS.items():
    # Concedidos: Anexo I where ENTIDAD SOLICITANTE == name
    con_rows = df1[df1[col_ent1].str.upper() == name.upper()]
    con = len(con_rows)
    # Total euros
    refs = con_rows[col_ref1].astype(str).str.strip()
    total_eur = sum(refToTotal.get(r, 0.0) for r in refs)
    # Sin ayuda: Anexo III where ENTIDAD SOLICITANTE == name
    sin = len(df3[df3[col_ent3].str.upper() == name.upper()])
    sol = con + sin
    results[code] = {'con': con, 'sin': sin, 'sol': sol, 'total_eur': total_eur}

totSol = sum(v['sol'] for v in results.values())
totCon = sum(v['con'] for v in results.values())
totEur = sum(v['total_eur'] for v in results.values())

print(f"{'Code':<5} {'Sol':>5} {'Con':>5} {'Sin':>5} {'Total EUR':>15} {'%Sol':>7} {'%Con':>7} {'%Exit':>7} {'Total M€':>9} {'%ACUP M€':>9}")
print("-"*80)
for code, v in results.items():
    con, sin, sol = v['con'], v['sin'], v['sol']
    eur = v['total_eur']
    pctSol = sol/totSol if totSol else 0
    pctCon = con/totCon if totCon else 0
    pctExit = con/sol if sol else 0
    totalMEur = round(eur/1_000_000, 1)
    pctAcupMEur = eur/totEur if totEur else 0
    print(f"{code:<5} {sol:>5} {con:>5} {sin:>5} {eur:>15,.0f} {pctSol:>7.1%} {pctCon:>7.1%} {pctExit:>7.1%} {totalMEur:>9.1f} {pctAcupMEur:>9.1%}")

print("-"*80)
totMEur = round(totEur/1_000_000, 1)
print(f"{'TOTAL':<5} {totSol:>5} {totCon:>5} {'':>5} {totEur:>15,.0f} {'100%':>7} {'100%':>7} {totCon/totSol:>7.1%} {totMEur:>9.1f} {'100%':>9}")
