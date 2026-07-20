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
df2["Y_sum"] = df2["2026 (EUR)"].apply(eur_to_float) + df2["2027 (EUR)"].apply(eur_to_float) + df2["2028 (EUR)"].apply(eur_to_float) + df2["2029 (EUR)"].apply(eur_to_float)

merged = df1.merge(df2, on="REFERENCIA", how="left")

UNIS = {
    "UB":  "UNIVERSIDAD DE BARCELONA",
    "UAB": "UNIVERSIDAD AUTONOMA DE BARCELONA",
    "UPC": "UNIVERSITAT POLITECNICA DE CATALUNYA",
    "UPF": "UNIVERSITAT POMPEU FABRA CCT",
    "UdL": "UNIVERSIDAD DE LLEIDA",
    "UdG": "UNIVERSITAT DE GIRONA",
    "URV": "UNIVERSITAT ROVIRA I VIRGILI",
}

print("Code | Target_M | Sum_TOTAL_M | Sum_CD_M | Sum_CI_M | Sum_CD_CI_M | Sum_Years_M")
print("-" * 80)
for code, name in UNIS.items():
    m = merged[merged["ENTIDAD SOLICITANTE"] == name]
    total_m = m["TOTAL_num"].sum() / 1e6
    cd_m = m["CD_num"].sum() / 1e6
    ci_m = m["CI_num"].sum() / 1e6
    cd_ci_m = (m["CD_num"] + m["CI_num"]).sum() / 1e6
    years_m = m["Y_sum"].sum() / 1e6
    
    # Target values from screenshot:
    targets = {
        "UB": 22.6,
        "UAB": 11.2,
        "UPC": 9.9,
        "UPF": 4.7,
        "UdL": 3.2,
        "UdG": 2.6,
        "URV": 3.0
    }
    
    print(f"{code:4} | {targets[code]:8.2f} | {total_m:11.2f} | {cd_m:8.2f} | {ci_m:8.2f} | {cd_ci_m:11.2f} | {years_m:11.2f}")
