import os
import pandas as pd

BASE = r"C:\Users\1000788\OneDrive - UAB\Documentos\GitHub\Spring-egreta\src\main\resources\PIDs\2025"

def load_excel(name):
    path = os.path.join(BASE, f"{name}.xlsx")
    return pd.read_excel(path, sheet_name="Sheet1")

df1 = load_excel("Anexo_I_Datos_Generales")
df2 = load_excel("Anexo_II_Datos_Economicos")

def eur_to_float(s):
    if pd.isna(s) or s == "":
        return 0.0
    s = str(s).replace(".", "").replace(",", ".")
    try:
        return float(s)
    except Exception:
        return 0.0

df2["TOTAL_num"] = df2["TOTAL concedido (EUR)"].apply(eur_to_float)

merged = df1.merge(df2, on="REFERENCIA", how="left")

# Find column names for predoc
predoc_cols = [c for c in merged.columns if "PREDOC" in c]

uab_predocs = merged[(merged["ENTIDAD SOLICITANTE"] == "UNIVERSIDAD AUTONOMA DE BARCELONA") & (merged[predoc_cols[0]] > 0)]

print(f"UAB projects with predocs count: {len(uab_predocs)}")
for idx, r in uab_predocs.iterrows():
    print(f"Ref: {r['REFERENCIA']} | Total: {r['TOTAL_num']:,} | Predocs: {r[predoc_cols[0]]}")
