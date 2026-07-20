import os
import pandas as pd
import openpyxl

BASE = os.path.dirname(os.path.abspath(__file__))

def load_excel(name):
    path = os.path.join(BASE, f"{name}.xlsx")
    return pd.read_excel(path, sheet_name="Sheet1")

df1 = load_excel("Anexo_I_Datos_Generales")
df2 = load_excel("Anexo_II_Datos_Economicos")
df3 = load_excel("Anexo_III_Solicitudes_sin_ayuda")

UNIS = {
    "UB":  "UNIVERSIDAD DE BARCELONA",
    "UAB": "UNIVERSIDAD AUTONOMA DE BARCELONA",
    "UPC": "UNIVERSITAT POLITECNICA DE CATALUNYA",
    "UPF": "UNIVERSITAT POMPEU FABRA CCT",
    "UdL": "UNIVERSIDAD DE LLEIDA",
    "UdG": "UNIVERSITAT DE GIRONA",
    "URV": "UNIVERSITAT ROVIRA I VIRGILI",
}

ACUP_PCT = {
    "UB":  0.33,
    "UAB": 0.19,
    "UPC": 0.20,
    "UPF": 0.06,
    "UdL": 0.07,
    "UdG": 0.07,
    "URV": 0.08,
}

def eur_to_float(s):
    if pd.isna(s) or s == "":
        return 0.0
    s = str(s).replace(".", "").replace(",", ".")
    try:
        return float(s)
    except Exception:
        return 0.0

df2["TOTAL_num"] = df2["TOTAL concedido (EUR)"].apply(eur_to_float)
merged = df1.merge(df2[["REFERENCIA", "TOTAL_num"]], on="REFERENCIA", how="left")

rows = []
for code, name in UNIS.items():
    sol = int((df1["ENTIDAD SOLICITANTE"] == name).sum()) + \
          int((df3["ENTIDAD SOLICITANTE"] == name).sum())
    con = int((merged["ENTIDAD SOLICITANTE"] == name).sum())
    total_eur = merged.loc[merged["ENTIDAD SOLICITANTE"] == name, "TOTAL_num"].sum()
    rows.append({"code": code, "name": name, "sol": sol, "con": con, "total_eur": total_eur})

tot_sol = sum(r["sol"] for r in rows)
tot_con = sum(r["con"] for r in rows)
tot_eur = sum(r["total_eur"] for r in rows)

print(f"tot_sol={tot_sol}, tot_con={tot_con}, tot_eur={tot_eur}")

data = []
for r in rows:
    data.append([
        r["code"],
        ACUP_PCT[r["code"]],
        r["sol"],
        r["sol"] / tot_sol if tot_sol else 0,
        r["con"],
        r["con"] / tot_con if tot_con else 0,
        r["con"] / r["sol"] if r["sol"] else 0,
        round(r["total_eur"] / 1_000_000, 1),
        r["total_eur"] / tot_eur if tot_eur else 0,
        r["total_eur"] / r["con"] if r["con"] else 0,
    ])

for row in data:
    print(row)
