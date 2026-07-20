import os
import pandas as pd

BASE = r"C:\Users\1000788\OneDrive - UAB\Documentos\GitHub\Spring-egreta\src\main\resources\PIDs\2025"

def load_excel(name):
    path = os.path.join(BASE, f"{name}.xlsx")
    return pd.read_excel(path, sheet_name="Sheet1")

df1 = load_excel("Anexo_I_Datos_Generales")

print("Prefixes in REFERENCIA:")
print(df1["REFERENCIA"].apply(lambda x: str(x).split("-")[0] if "-" in str(x) else str(x)).value_counts())

# Check for specific university (UAB)
uab = df1[df1["ENTIDAD SOLICITANTE"] == "UNIVERSIDAD AUTONOMA DE BARCELONA"]
print("\nUAB prefixes:")
print(uab["REFERENCIA"].apply(lambda x: str(x).split("-")[0] if "-" in str(x) else str(x)).value_counts())
