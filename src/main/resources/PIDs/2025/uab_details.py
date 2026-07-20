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
df2["CD_num"] = df2["CD Costes directos (EUR)"].apply(eur_to_float)
df2["CI_num"] = df2["CI Costes indirectos (EUR)"].apply(eur_to_float)

merged = df1.merge(df2, on="REFERENCIA", how="left")

uab = merged[merged["ENTIDAD SOLICITANTE"] == "UNIVERSIDAD AUTONOMA DE BARCELONA"]

print(f"UAB projects count: {len(uab)}")
# Print first 20 projects with their budgets
print("First 20 projects:")
for idx, r in uab.head(20).iterrows():
    print(f"Ref: {r['REFERENCIA']} | Total: {r['TOTAL_num']:,} | CD: {r['CD_num']:,} | CI: {r['CI_num']:,}")
