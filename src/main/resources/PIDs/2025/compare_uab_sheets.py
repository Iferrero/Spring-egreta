import os
import pandas as pd

BASE = r"C:\Users\1000788\OneDrive - UAB\Documentos\GitHub\Spring-egreta\src\main\resources\PIDs\2025"
path = os.path.join(BASE, "Anexo_I_Datos_Generales.xlsx")

df_sheet1 = pd.read_excel(path, sheet_name="Sheet1")
df_uab_sheet = pd.read_excel(path, sheet_name="UAB")

filtered_refs = set(df_sheet1[df_sheet1["ENTIDAD SOLICITANTE"] == "UNIVERSIDAD AUTONOMA DE BARCELONA"]["REFERENCIA"])
uab_sheet_refs = set(df_uab_sheet["REFERENCIA"])

print("Filtered from Sheet1 count:", len(filtered_refs))
print("UAB sheet count:", len(uab_sheet_refs))

diff1 = filtered_refs - uab_sheet_refs
diff2 = uab_sheet_refs - filtered_refs
print("In Filtered but not in UAB sheet:", diff1)
print("In UAB sheet but not in Filtered:", diff2)
