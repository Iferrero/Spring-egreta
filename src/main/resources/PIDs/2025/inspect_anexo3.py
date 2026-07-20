import os
import pandas as pd

BASE = r"C:\Users\1000788\OneDrive - UAB\Documentos\GitHub\Spring-egreta\src\main\resources\PIDs\2025"

df3 = pd.read_excel(os.path.join(BASE, "Anexo_III_Solicitudes_sin_ayuda.xlsx"), sheet_name="Sheet1")
print("=== Anexo_III columns ===")
for i, col in enumerate(df3.columns):
    print(f"  [{i}] {col}")

print(f"\nTotal rows: {len(df3)}")
print("\nSample of ENTIDAD SOLICITANTE values:")
print(df3.iloc[:, 4].value_counts().head(10))
