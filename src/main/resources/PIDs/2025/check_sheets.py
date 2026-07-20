import os
import openpyxl

BASE = r"C:\Users\1000788\OneDrive - UAB\Documentos\GitHub\Spring-egreta\src\main\resources\PIDs\2025"

files = [
    "Anexo_I_Datos_Generales.xlsx",
    "Anexo_II_Datos_Economicos.xlsx",
    "Anexo_III_Solicitudes_sin_ayuda.xlsx"
]

for file in files:
    path = os.path.join(BASE, file)
    print(f"\n=== {file} ===")
    if not os.path.exists(path):
        print("Not found")
        continue
    wb = openpyxl.load_workbook(path, read_only=True)
    print("Sheets:", wb.sheetnames)
