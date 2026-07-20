import os
import pandas as pd

BASE = r"C:\Users\1000788\OneDrive - UAB\Documentos\GitHub\Spring-egreta\src\main\resources\PIDs\2025"

def load_excel(name):
    path = os.path.join(BASE, f"{name}.xlsx")
    return pd.read_excel(path, sheet_name="Sheet1")

df2 = load_excel("Anexo_II_Datos_Economicos")

print("Data types in 'TOTAL concedido (EUR)':")
print(df2["TOTAL concedido (EUR)"].apply(lambda x: type(x)).value_counts())

# Let's print some rows where the type is float/int
numeric_rows = df2[df2["TOTAL concedido (EUR)"].apply(lambda x: isinstance(x, (int, float)) and not pd.isna(x))]
print(f"\nNumber of numeric rows: {len(numeric_rows)}")
if len(numeric_rows) > 0:
    print("\nExamples of numeric rows:")
    for idx, r in numeric_rows.head(10).iterrows():
        val = r["TOTAL concedido (EUR)"]
        print(f"Original: {val} (type: {type(val)})")
        
        # Apply the user's function
        s = str(val).replace(".", "").replace(",", ".")
        try:
            parsed = float(s)
        except Exception:
            parsed = 0.0
        print(f"Parsed by user's function: {parsed}")
