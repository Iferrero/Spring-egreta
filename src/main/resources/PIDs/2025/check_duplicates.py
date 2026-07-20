import os
import pandas as pd

BASE = r"C:\Users\1000788\OneDrive - UAB\Documentos\GitHub\Spring-egreta\src\main\resources\PIDs\2025"

def load_excel(name):
    path = os.path.join(BASE, f"{name}.xlsx")
    return pd.read_excel(path, sheet_name="Sheet1")

df1 = load_excel("Anexo_I_Datos_Generales")
df2 = load_excel("Anexo_II_Datos_Economicos")

print("df1 shape:", df1.shape)
print("df1 unique refs count:", df1["REFERENCIA"].nunique())
print("df2 shape:", df2.shape)
print("df2 unique refs count:", df2["REFERENCIA"].nunique())

# Check duplicates in df1
dup_df1 = df1[df1.duplicated(subset=["REFERENCIA"], keep=False)]
print(f"Duplicates in df1: {len(dup_df1)}")

# Check duplicates in df2
dup_df2 = df2[df2.duplicated(subset=["REFERENCIA"], keep=False)]
print(f"Duplicates in df2: {len(dup_df2)}")
