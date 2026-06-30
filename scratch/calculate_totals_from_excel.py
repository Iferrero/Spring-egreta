import openpyxl
from collections import defaultdict

wb = openpyxl.load_workbook("src/main/resources/TABLON-Balanç de recursos - detallat (RC0025R).xlsx", data_only=True)
sheet = wb.active

# Let's read the headers from row 5 (1-indexed, which is row 5 in Excel, 0-indexed row 5 is index 5)
# Row 5 (0-indexed) has index 5: ('Grup UAB-Esfera', "Any de l'ajut", 'Unitat de recerca', None, 'Capítol', 'Tipus', 'Sexe', 'Investigador', None, None, None, 'Codi comptable', "Data d'inici", 'Data de fi', 'Data de resolucio')
# Let's inspect the entire row 5
header_row = [cell.value for cell in sheet[6]] # row 6 is index 6
print("=== HEADERS ===")
for idx, val in enumerate(header_row):
    print(f"{idx}: {val}")

# We will aggregate by year, Grup UAB-Esfera, Capítol, and Tipus.
# The amount column seems to be index 21 based on Java's `row.getCell(21)`.
# Let's print unique values and sums for 2024.
aggr = defaultdict(lambda: defaultdict(float))
unique_groups = set()
unique_capitols = set()
unique_tipus = set()

for r_idx in range(7, sheet.max_row + 1):
    row = [cell.value for cell in sheet[r_idx]]
    if len(row) < 22:
        continue
    
    group = row[0] # UAB or Esfera
    year = row[1] # Year
    capitol = row[4]
    tipus = row[5]
    
    # Amount is at index 21 (Institutional Part)
    amount = row[21]
    if amount is None:
        amount = 0.0
    try:
        amount = float(amount)
    except ValueError:
        amount = 0.0
        
    unique_groups.add(group)
    unique_capitols.add(capitol)
    unique_tipus.add(tipus)
    
    if year == 2024:
        key = (group, capitol, tipus)
        aggr[key] += amount

print("\n=== UNIQUE GROUPS ===")
print(unique_groups)

print("\n=== UNIQUE CAPITOLS ===")
print(unique_capitols)

print("\n=== UNIQUE TIPUS ===")
print(unique_tipus)

print("\n=== 2024 AGGREGATED AMOUNTS (M€) ===")
total_2024 = 0.0
for (group, capitol, tipus), amt in sorted(aggr.items(), key=lambda x: -x[1]):
    total_2024 += amt
    print(f"  {group} | {capitol} | {tipus} : {amt/1e6:.2f} M€ ({amt:,.2f} €)")

print(f"\nTotal 2024: {total_2024/1e6:.2f} M€")
wb.close()
