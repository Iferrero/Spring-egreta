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

for col in ["2026 (EUR)", "2027 (EUR)", "2028 (EUR)", "2029 (EUR)"]:
    df2[col + "_num"] = df2[col].apply(eur_to_float)

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

targets = {
    "UB": 22.636731,
    "UAB": 11.217375,
    "UPC": 9.902962,
    "UPF": 4.676375,
    "UdL": 3.153375,
    "UdG": 2.577000,
    "URV": 2.964625
}

print("Code | Target_M | Sum_26_28_M | Diff_M")
print("-" * 50)
for code, name in UNIS.items():
    m = merged[merged["ENTIDAD SOLICITANTE"] == name]
    sum_26_28 = (m["2026 (EUR)_num"].sum() + m["2027 (EUR)_num"].sum() + m["2028 (EUR)_num"].sum()) / 1e6
    print(f"{code:4} | {targets[code]:8.3f} | {sum_26_28:11.3f} | {targets[code] - sum_26_28:8.3f}")
