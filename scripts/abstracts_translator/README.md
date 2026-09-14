# Abstracts Translator, Benchmark & Sync con LLM Salamandra y Egreta (Pure API)

Módulo integral de procesamiento lingüístico, traducción automática mediante **BSC Salamandra LLM** (vía LM Studio, vLLM o Transformers), benchmarking de rendimiento y sincronización bidireccional con **MongoDB** (`kraken.Researchoutputs`) y **Egreta Test/Prod** (Pure REST API).

---

## 📋 Reglas de Negocio y Árbol de Decisión

Para cada Research Output (RO):
1. **Sin Abstract**: Se omite el documento (no realiza ninguna acción).
2. **1 Solo Abstract Único**:
   - Detecta el idioma del texto.
   - Si es **Inglés (`en_GB`)**, se ubica en su casilla y se traduce a **Español (`es_ES`)** y **Catalán (`ca_ES`)**.
   - Si es **Español (`es_ES`)**, se ubica en su casilla y se traduce a **Inglés (`en_GB`)** y **Catalán (`ca_ES`)**.
   - Si es **Catalán (`ca_ES`)**, se ubica en su casilla y se traduce a **Inglés (`en_GB`)** y **Español (`es_ES`)**.
   - Si es de **otro idioma** (ej. Francés, Alemán): se reserva el texto original en la casilla de español (`es_ES`) y se traduce desde ese texto al inglés (`en_GB`) y catalán (`ca_ES`).
3. **2 Abstracts Únicos**:
   - Si son idénticos, se deduplican y se procesan como 1 solo.
   - Si son distintos, se ubican en sus casillas correspondientes y se traduce el faltante (priorizando traducción desde inglés si está disponible).
4. **3 Abstracts**:
   - Si los 3 son idénticos, se deduplican y se procesa como 1 solo.
   - Si los 3 son distintos y corresponden a los 3 idiomas, se consideran correctos y no se traduce nada.
   - Si 2 son idénticos y 1 distinto, se mantiene el genuino en su sitio y se traduce el que falta.

> [!IMPORTANT]
> **Tratamiento de abstracts en inglés mal ubicados (ej. en la casilla de español `es_ES` o catalán `ca_ES`)**:
> - Si el detector lingüístico determina que el texto ubicado en `es_ES` (o `ca_ES`) es en realidad **inglés**, ese texto se reubica en la casilla correcta **`en_GB`**.
> - Se **elimina** el texto en inglés de la casilla `es_ES` y se genera una **traducción real al español** a partir del inglés.
> - Se genera asimismo la traducción al catalán (`ca_ES`) si tampoco existía una versión genuina en catalán.

---

## ⚙️ Referencia Completa de Parámetros de Línea de Comandos (CLI)

Ejecución base:
```bash
python -m scripts.abstracts_translator.main [PARÁMETROS]
```

| Parámetro | Tipo / Opciones | Valor por Defecto | Descripción |
| :--- | :--- | :--- | :--- |
| `--mode` | `benchmark` \| `dry-run` \| `apply` | `benchmark` | **Modo de ejecución**:<br>• `benchmark`: Mide latencias, throughput de tokens y muestra proyecciones.<br>• `dry-run`: Muestra la simulación/previsualización de traducciones sin escribir en bases de datos.<br>• `apply`: Guarda los abstracts en MongoDB y sincroniza opcionalmente con Egreta. |
| `--engine` | `lm-studio` \| `vllm-server` \| `vllm-local` \| `transformers` \| `mock` | `lm-studio` | **Motor de inferencia LLM**:<br>• `lm-studio`: Conecta a LM Studio local (`http://127.0.0.1:1234`).<br>• `vllm-server`: Servidor HTTP vLLM OpenAI-compatible.<br>• `vllm-local`: Motor nativo vLLM en servidor GPU (Linux).<br>• `transformers`: Pipeline HuggingFace Transformers.<br>• `mock`: Simulación inmediata de tiempos y métricas. |
| `--model` | `str` | `bsc/salamandra-7b-instruct` | Nombre o ruta del modelo. En LM Studio detecta automáticamente el modelo cargado si no se especifica. |
| `--lm-studio-url` | `str` (URL) | `http://127.0.0.1:1234/v1` | URL base del servidor local de LM Studio. |
| `--vllm-url` | `str` (URL) | `http://localhost:8000/v1` | URL base del servidor HTTP vLLM. |
| `--limit` | `int` | `10` | Número máximo de Research Outputs a procesar (`0` = procesar todos sin límite). |
| `--year` | `int` | `None` | Filtro por año de publicación (`submissionYear`), ej: `--year 2023`. |
| `--only-with-abstract` | `bool` | `True` | Si está activo, procesa únicamente ROs que ya tengan algún texto en el campo `abstract` de MongoDB. |
| `--sync-egreta` | `flag` | `False` | Activa la sincronización y actualización de los abstracts traducidos directamente en **Egreta (Pure REST API)**. |
| `--egreta-env` | `test` \| `prod` | `test` | Entorno de Egreta a actualizar (`test` $\rightarrow$ `https://egretat.uab.cat`, `prod` $\rightarrow$ `https://egreta.uab.cat`). |
| `--egreta-api-key` | `str` | `None` | Clave de API para Egreta (por defecto toma el valor de `EGRETA_API_KEY`). |
| `--report-file` | `str` (Ruta) | `None` | Ruta del archivo `.json` donde guardar el reporte detallado con métricas, estadísticas y todos los ROs procesados. |

---

## 🌐 Variables de Entorno de Configuración

Puedes sobreescribir la configuración por defecto mediante variables de entorno en el sistema o en un archivo `.env`:

| Variable | Valor por Defecto | Descripción |
| :--- | :--- | :--- |
| `MONGO_URI` | `mongodb://myUserAdmin:Mongoplex.00@ymir.uab.cat:8000/admin` | Cadena de conexión a MongoDB. |
| `MONGO_DB_NAME` | `kraken` | Nombre de la base de datos MongoDB. |
| `MONGO_COLLECTION` | `Researchoutputs` | Colección principal de Research Outputs. |
| `MONGO_AI_METADATA_COLLECTION` | `ResearchoutputsAiAbstracts` | Colección dedicada para trazabilidad y metadatos de IA. |
| `EGRETA_API_KEY` | `9971c3cc-b3e0-48e3-9ff9-e990c795e92f` | Clave API para la autenticación en Pure / Egreta. |
| `EGRETA_URL_TEST` | `https://egretat.uab.cat/ws/api/` | Endpoint base para Egreta Test. |
| `EGRETA_URL_PROD` | `https://egreta.uab.cat/ws/api/` | Endpoint base para Egreta Producción. |
| `LM_STUDIO_URL` | `http://127.0.0.1:1234/v1` | Endpoint por defecto de LM Studio. |
| `VLLM_SERVER_URL` | `http://localhost:8000/v1` | Endpoint por defecto de vLLM. |
| `SALAMANDRA_MODEL` | `bsc/salamandra-7b-instruct` | Identificador por defecto del modelo. |

---

## 🎛️ Parámetros de Inferencia del LLM Salamandra

| Parámetro | Valor | Justificación |
| :--- | :--- | :--- |
| `temperature` | `0.1` | Muestreo a baja temperatura para máxima fidelidad y rigor terminológico académico. |
| `max_tokens` | `2048` | Longitud máxima para cubrir abstracts extensos sin truncamiento. |
| `stop` | `["<|im_end|>", "<|im_start|>", "</s>", "<|endoftext|>"]` | Delimitadores de parada para evitar generaciones espurias o repeticiones de turnos. |
| `language_names` | `español`, `catalán`, `inglés` | Nombres canónicos de idioma alineados con el preentrenamiento del BSC. |

---

## 🗄️ Esquema de Datos en MongoDB

1. **Colección `Researchoutputs`**:
   Se actualiza el objeto `abstract` limpio con las 3 variantes normalizadas:
   ```json
   {
     "uuid": "bb7e8717-7f64-4215-9151-a8995f2c15fd",
     "abstract": {
       "en_GB": "Optimal elective surgery for splenic flexure...",
       "es_ES": "La cirugía electiva óptima para el cáncer de ángulo esplénico...",
       "ca_ES": "La cirurgia electiva òptima per al càncer de flexura esplènica..."
     }
   }
   ```

2. **Colección `ResearchoutputsAiAbstracts` (Desacoplada)**:
   Almacena el detalle de trazabilidad, latencias y sincronización con Egreta:
   ```json
   {
     "_id": ObjectId("..."),
     "uuid": "bb7e8717-7f64-4215-9151-a8995f2c15fd",
     "title": "Analysis of the structure and use of digital resources",
     "submissionYear": 2023,
     "abstract_ai_metadata": {
       "generated_languages": ["ca_ES", "es_ES"],
       "source_language": "en_GB",
       "model": "bsc/salamandra-7b-instruct",
       "generated_at": "2026-09-07T12:30:00Z",
       "total_latency_ms": 6240.5,
       "translations_detail": [
         {
           "from_lang": "en_GB",
           "to_lang": "es_ES",
           "latency_ms": 3120.2,
           "prompt_tokens": 280,
           "completion_tokens": 240,
           "source_preview": "...",
           "translation_preview": "..."
         },
         {
           "from_lang": "en_GB",
           "to_lang": "ca_ES",
           "latency_ms": 3120.3,
           "prompt_tokens": 280,
           "completion_tokens": 245,
           "source_preview": "...",
           "translation_preview": "..."
         }
       ],
       "egreta_sync": {
         "synced": true,
         "env": "test",
         "synced_at": "2026-09-07T12:40:00Z",
         "message": "Abstracts sincronitzats correctament a Egreta (test) [HTTP 200]"
       }
     },
     "updated_at": "2026-09-07T12:30:00Z"
   }
   ```

---

## 💡 Ejemplos de Uso Práctico

### 1. Benchmark de tiempo con LM Studio (10 abstracts):
```bash
python -m scripts.abstracts_translator.main \
  --mode benchmark \
  --engine lm-studio \
  --limit 10 \
  --report-file benchmark_lmstudio.json
```

### 2. Simulación Dry-Run para el año 2023:
```bash
python -m scripts.abstracts_translator.main \
  --mode dry-run \
  --engine lm-studio \
  --year 2023 \
  --limit 5
```

### 3. Aplicar cambios en MongoDB y sincronizar con Egreta Test:
```bash
python -m scripts.abstracts_translator.main \
  --mode apply \
  --engine lm-studio \
  --limit 20 \
  --sync-egreta \
  --egreta-env test
```

### 4. Benchmark en servidor GPU con vLLM:
```bash
python -m scripts.abstracts_translator.main \
  --mode benchmark \
  --engine vllm-server \
  --vllm-url http://localhost:8000/v1 \
  --limit 50
```

---

## 🧪 Ejecución de Tests Unitarios

Para verificar que las 9 ramas y casos de prueba del árbol de decisión funcionan correctamente:
```bash
python -m unittest scripts.abstracts_translator.test_translator
```
