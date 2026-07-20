import os
import pandas as pd

BASE = r"C:\Users\1000788\OneDrive - UAB\Documentos\GitHub\Spring-egreta\src\main\resources\PIDs\2025"

def load_excel(name):
    path = os.path.join(BASE, f"{name}.xlsx")
    return pd.read_excel(path, sheet_name="Sheet1")

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

df2["y2026"] = df2["2026 (EUR)"].apply(eur_to_float)
df2["y2027"] = df2["2027 (EUR)"].apply(eur_to_float)
df2["y2028"] = df2["2028 (EUR)"].apply(eur_to_float)
df2["y2029"] = df2["2029 (EUR)"].apply(eur_to_float)
df2["years_sum"] = df2["y2026"] + df2["y2027"] + df2["y2028"] + df2["y2029"]

diff_cd_ci = df2[abs(df2["TOTAL_num"] - (df2["CD_num"] + df2["CI_num"])) > 0.01]
print("Projects where TOTAL != CD + CI:", len(diff_cd_ci))

diff_years = df2[abs(df2["TOTAL_num"] - df2["years_sum"]) > 0.01]
print("Projects where TOTAL != sum of years:", len(diff_years))
if len(diff_years) > 0:
    print(diff_years[["REFERENCIA", "TOTAL_num", "years_sum"]].head(10))
