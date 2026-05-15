# Documentacion de endpoints (API canonica)

Este documento resume todos los endpoints expuestos en los controladores de `src/main/java/com/example/demo/controller`.

## Convenciones de resultado

- `Page<T>` o `PagedModel<T>`: respuesta paginada con items y metadatos (numero de pagina, tamano, total, etc.).
- `List<...>`: arreglo JSON.
- `Map<String, ...>`: objeto JSON.
- `ResponseEntity<...>`: objeto JSON con codigo HTTP controlado por el endpoint.
- `void` (en endpoints de reporte): descarga de fichero (normalmente Word `.docx`) en la respuesta HTTP.

## Applications (`/api/applications`)

| Metodo | Endpoint | Explicacion | Resultado |
|---|---|---|---|
| GET | `/api/applications` | Lista de solicitudes (applications) con paginacion. | `Page<Application>` |
| GET | `/api/applications/describe` | Describe estructura/campos de la coleccion (muestra de documentos). | `Map<String, Object>` |
| GET | `/api/applications/stats/by-funding-opportunity` | Estadisticas de solicitudes agrupadas por oportunidad de financiacion. | `List<Map<String, Object>>` |

## Awards (`/api/awards`)

| Metodo | Endpoint | Explicacion | Resultado |
|---|---|---|---|
| GET | `/api/awards` | Lista paginada de ayudas/premios. | `PagedModel<Award>` |
| GET | `/api/awards/stats/categories` | Devuelve categorias disponibles de ayudas/premios. | `List<String>` |
| GET | `/api/awards/stats/tipus` | Devuelve tipos de ayuda/premio. | `List<String>` |
| GET | `/api/awards/stats/tipus-per-categoria` | Estadistica de tipos por categoria. | `List<Document>` |
| GET | `/api/awards/stats/total` | Totales agregados globales de ayudas/premios. | `Map<String, Object>` |
| GET | `/api/awards/stats/powertable` | Tabla de analisis (powertable) de ayudas. | `List<Document>` |
| GET | `/api/awards/stats/llista-ajuts-institut` | Listado de ayudas por instituto. | `List<Document>` |
| GET | `/api/awards/stats/ips-institut` | IPs por instituto en ayudas/proyectos. | `List<Document>` |
| GET | `/api/awards/stats/powertable/category-debug` | Salida de depuracion para categorias de powertable. | `List<Map>` |
| GET | `/api/awards/stats/persona-resumen` | Resumen de ayudas por persona investigadora. | `List<Document>` |
| GET | `/api/awards/stats/persona-awards` | Detalle/estadistica de ayudas por persona. | `List<Document>` |
| GET | `/api/awards/stats/proyectos-anio` | Proyectos/ayudas agregados por anio. | `List<Document>` |
| GET | `/api/awards/debug/tesis` | Endpoint tecnico de depuracion relacionado con tesis/awards. | `Map<String, Object>` |
| GET | `/api/awards/countries` | Lista de paises detectados en ayudas/proyectos. | `List<Map<String, Object>>` |
| GET | `/api/awards/by-country` | Estadisticas de ayudas agrupadas por pais. | `Map<String, Object>` |
| GET | `/api/awards/reports/word/country` | Genera y descarga informe Word de ayudas por pais. | `void` (descarga de fichero) |

## External organizations (`/api/external-organizations`)

| Metodo | Endpoint | Explicacion | Resultado |
|---|---|---|---|
| GET | `/api/external-organizations` | Lista paginada de organizaciones externas. | `Page<ExternalOrganization>` |
| GET | `/api/external-organizations/unlinked` | Organizaciones externas sin enlace/relacion interna. | `Page<ExternalOrganization>` |
| GET | `/api/external-organizations/unlinked/count` | Conteo de organizaciones externas no enlazadas. | `Map<String, Long>` |
| GET | `/api/external-organizations/stats/by-type` | Estadisticas de organizaciones por tipo. | `List<Map<String, Object>>` |
| GET | `/api/external-organizations/stats/by-country` | Estadisticas de organizaciones por pais. | `List<Map<String, Object>>` |
| GET | `/api/external-organizations/debug/references` | Depuracion de referencias para una organizacion concreta. | `Map<String, Object>` |
| GET | `/api/external-organizations/debug/schema` | Depuracion de esquema/campos de una coleccion. | `Map<String, Object>` |

## Funding opportunities (`/api/funding-opportunities`)

| Metodo | Endpoint | Explicacion | Resultado |
|---|---|---|---|
| GET | `/api/funding-opportunities` | Lista paginada de oportunidades de financiacion. | `Page<FundingOpportunity>` |
| GET | `/api/funding-opportunities/describe` | Describe estructura/campos de la coleccion de oportunidades. | `Map<String, Object>` |

## Journals (`/api/journals`)

| Metodo | Endpoint | Explicacion | Resultado |
|---|---|---|---|
| GET | `/api/journals/{journalUuid}/jcr` | Obtiene informacion JCR para una revista por UUID. | `ResponseEntity<Map<String, Object>>` |
| GET | `/api/journals/jcr-by-issn` | Busca registros JCR por ISSN. | `List<Jcr>` |

## Mongo schema (`/api/mongo`)

| Metodo | Endpoint | Explicacion | Resultado |
|---|---|---|---|
| POST | `/api/mongo/run` | Ejecuta una operacion definida en request body sobre Mongo (uso tecnico). | `Map<String, Object>` |
| GET | `/api/mongo/schema` | Devuelve esquema inferido de una coleccion Mongo. | `Map<String, Object>` |

## Persons (`/api/persons`)

| Metodo | Endpoint | Explicacion | Resultado |
|---|---|---|---|
| GET | `/api/persons` | Lista paginada de personas. | `PagedModel<Persona>` |
| GET | `/api/persons/debug-identifiers` | Endpoint tecnico para revisar identificadores de personas. | `List<Object>` |
| GET | `/api/persons/vigentes` | Lista paginada de personas vigentes/activas. | `PagedModel<Persona>` |
| GET | `/api/persons/stats/orcid` | Totales de disponibilidad/estado de ORCID. | `Map<String, Long>` |
| GET | `/api/persons/associations/report` | Reporte de asociaciones organizativas/historicas de personas. | `List<Map>` |
| GET | `/api/persons/associations/latest` | Ultima asociacion conocida por persona. | `List<Map>` |
| GET | `/api/persons/by-dept` | Personas agrupadas/filtradas por departamento. | `List<Map<String, String>>` |
| GET | `/api/persons/departamentos` | Catalogo de departamentos. | `List<Map<String, String>>` |
| GET | `/api/persons/ambits` | Catalogo de ambitos. | `List<String>` |
| GET | `/api/persons/departamentos-by-ambit` | Departamentos filtrados por ambito. | `List<Map<String, String>>` |
| GET | `/api/persons/institutos` | Catalogo de institutos. | `List<Map<String, String>>` |
| GET | `/api/persons/organization-types` | Tipos de organizacion disponibles para personas. | `Map<String, List<String>>` |
| GET | `/api/persons/stats/employment` | Estadistica de empleo/vinculacion laboral. | `List<Map>` |
| GET | `/api/persons/with-projects` | Personas con proyectos asociados. | `List<Map>` |
| GET | `/api/persons/stats/age-pyramid` | Datos para piramide de edad por tramos y genero. | `List<Map<String, Object>>` |
| GET | `/api/persons/stats/sex-distribution` | Distribucion por sexo/genero. | `Map<String, Integer>` |
| GET | `/api/persons/stats/nationality` | Distribucion por nacionalidad. | `List<Map<String, Object>>` |
| GET | `/api/persons/stats/nationality/debug` | Depuracion de campos usados en nacionalidad. | `List<Map<String, Object>>` |
| GET | `/api/persons/stats/contract-type` | Distribucion por tipo de contrato. | `Map<String, Long>` |
| GET | `/api/persons/stats/personal-academic` | Totales de personal academico segun criterios internos. | `Map<String, Long>` |
| GET | `/api/persons/stats/vigentes-total` | Total de personas vigentes. | `Map<String, Long>` |
| GET | `/api/persons/stats/vigentes-por-categoria` | Vigentes agrupados por categoria. | `List<Map<String, Object>>` |
| GET | `/api/persons/stats/catedraticos` | Totales de catedraticos. | `Map<String, Long>` |
| GET | `/api/persons/stats/titulares` | Totales de titulares. | `Map<String, Long>` |
| GET | `/api/persons/stats/agregados` | Totales de agregados. | `Map<String, Long>` |
| GET | `/api/persons/stats/lectores` | Totales de lectores. | `Map<String, Long>` |
| GET | `/api/persons/stats/asociados` | Totales de asociados. | `Map<String, Long>` |
| GET | `/api/persons/stats/substituts` | Totales de substituts/sustitutos. | `Map<String, Long>` |
| GET | `/api/persons/stats/predoctorals` | Totales de predoctorales. | `Map<String, Long>` |
| GET | `/api/persons/stats/postdoctorals` | Totales de postdoctorales. | `Map<String, Long>` |
| GET | `/api/persons/stats/icrea` | Totales de personal ICREA. | `Map<String, Long>` |
| GET | `/api/persons/stats/lectores/candidates` | Candidatos detectados para categoria de lectores. | `List<Map<String, Object>>` |
| GET | `/api/persons/stats/employment-types-summary` | Resumen consolidado por tipos de empleo. | `Map<String, List<Map<String, Object>>>` |
| GET | `/api/persons/stats/predoctorals/debug-terms` | Depuracion de terminos usados para detectar predoctorales. | `List<Map<String, Object>>` |
| GET | `/api/persons/stats/asociados/debug-terms` | Depuracion de terminos usados para detectar asociados. | `List<Map<String, Object>>` |
| GET | `/api/persons/stats/personal-academic/debug-types` | Depuracion de tipos usados para personal academico. | `List<Map<String, Object>>` |
| GET | `/api/persons/stats/investigadors/debug-terms` | Depuracion de terminos usados para personal investigador. | `List<Map<String, Object>>` |
| GET | `/api/persons/stats/age-pyramid/debug-gender` | Depuracion de extraccion de genero en piramide de edad. | `List<Map<String, Object>>` |
| GET | `/api/persons/reports/word` | Genera y descarga informe Word de personas. | `void` (descarga de fichero) |
| GET | `/api/persons/search/active` | Busqueda de personas activas por texto/criterio. | `List<Map<String, String>>` |
| GET | `/api/persons/reports/word/person` | Genera y descarga informe Word de una persona concreta. | `void` (descarga de fichero) |

## Pure / Publications (`/api/pure`)

| Metodo | Endpoint | Explicacion | Resultado |
|---|---|---|---|
| GET | `/api/pure` | Lista paginada de publicaciones (Pure). | `Page<Publicacion>` |
| GET | `/api/pure/search` | Busqueda/filtrado de publicaciones (por anio y otros filtros). | `Page<Publicacion>` |
| GET | `/api/pure/{publicationUuid}/raw` | Documento raw completo de una publicacion por UUID. | `ResponseEntity<Object>` |
| GET | `/api/pure/{publicationUuid}/journal-jcr` | Relacion publicacion + revista + datos JCR. | `ResponseEntity<Map<String, Object>>` |
| GET | `/api/pure/journal-jcr/resumen` | Resumen agregado de cruce entre journals y JCR. | `Map<String, Object>` |
| GET | `/api/pure/stats/quartiles` | Quartiles por departamento. | `List<Map<String, Object>>` |
| GET | `/api/pure/stats/quartiles/articles` | Articulos incluidos en calculo de quartiles por departamento. | `List<Map<String, Object>>` |
| GET | `/api/pure/stats/quartiles/evolution` | Evolucion temporal de quartiles por departamento. | `List<Map<String, Object>>` |
| GET | `/api/pure/stats/quartiles/dashboard` | Resumen para dashboard de quartiles. | `Map<String, Object>` |
| GET | `/api/pure/stats/years` | Totales de publicaciones por anio. | `List<Map>` |
| GET | `/api/pure/stats/types` | Totales de publicaciones por tipo. | `List<Map>` |
| GET | `/api/pure/stats/types-by-year` | Totales de tipo de publicacion por anio. | `List<Map>` |
| GET | `/api/pure/stats/persona-resumen` | Resumen de publicaciones por persona. | `List<Map>` |
| GET | `/api/pure/stats/apa` | Listado de referencias en formato APA. | `List<Map<String, Object>>` |

## Student theses (`/api/student-theses`)

| Metodo | Endpoint | Explicacion | Resultado |
|---|---|---|---|
| GET | `/api/student-theses` | Lista paginada de tesis de estudiantes. | `Page<StudentThesis>` |
| GET | `/api/student-theses/stats/same-author-director` | Casos donde autor/a y director/a coinciden (control de calidad). | `List<Document>` |
| GET | `/api/student-theses/stats/per-year-institute` | Tesis por anio e instituto. | `List<Map<String, Object>>` |
| GET | `/api/student-theses/stats/directors-institut` | Direcciones de tesis agregadas por instituto. | `List<Map<String, Object>>` |
| GET | `/api/student-theses/stats/list-institute` | Listado de tesis por instituto. | `List<Map<String, Object>>` |

## Notas practicas

- Los endpoints de listados paginados suelen aceptar parametros de paginacion de Spring (`page`, `size`, `sort`).
- Los endpoints `debug/*` y algunos `stats/*` estan orientados a diagnostico/analisis interno.
- En entorno local (`dev`) se usa autenticacion HTTP Basic; en otros entornos aplica CAS segun configuracion.
