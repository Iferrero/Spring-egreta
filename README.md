# Portal de Recerca — Spring-egreta

Aplicació web de gestió i visualització de la recerca de la UAB (Universitat Autònoma de Barcelona). Proporciona una API REST construïda amb Spring Boot que consulta les bases de dades MongoDB del sistema **Kraken** (Pure) i **JCR**, i exposa un portal web estàtic per a la consulta d'informes per part del personal d'investigació.

---

## Tecnologies

| Capa | Tecnologia |
|------|-----------|
| Backend | Java 21, Spring Boot 4.0.5 |
| Seguretat | Spring Security (HTTP Basic) |
| Persistència | Spring Data MongoDB (dues connexions: Kraken + JCR) |
| SSO | CAS client (Apereo, compatible amb Jakarta Servlet) |
| Generació de documents | Apache POI 5.4.0 (Word/Excel) |
| Frontend | HTML + Tailwind CSS + Font Awesome (estàtic sota `webapp/`) |
| Empaquetament | WAR (desplegable en Tomcat extern) |

---

## Requisits previs

- **JDK 21** (`JAVA_HOME` apuntant a JDK 21)
- **Maven 3.9+** (o usar el wrapper `mvnw` inclòs)
- Accés a les instàncies MongoDB de la UAB (`ymir.uab.cat:8000`)

---

## Configuració

El fitxer de configuració principal és [`src/main/resources/application.properties`](src/main/resources/application.properties).

```properties
# Connexió principal (Kraken / Pure)
spring.mongodb.uri=mongodb://<user>:<password>@ymir.uab.cat:8000/kraken?authSource=admin

# Connexió secundària (JCR — bibliometria)
app.jcr.mongodb.uri=mongodb://<user>:<password>@ymir.uab.cat:8000/JCR?authSource=admin

# CAS (producció)
cas.server.url-prefix=https://sso.uab.cat
cas.service.server-name=http://localhost:8080
cas.enabled=true

server.port=8080
```

Per desenvolupament local amb autenticació bàsica, activa el perfil `dev` (fitxer [`src/main/resources/application-dev.properties`](src/main/resources/application-dev.properties)):

```properties
cas.enabled=false
dev.user.name=recerca
dev.user.password=<password>
```

> **Nota de seguretat:** No pugis mai credencials reals al repositori. Usa variables d'entorn o un fitxer `.env` ignorat per Git.

---

## Execució en local

```bash
# Clonar el repositori
git clone <url-del-repo>
cd Spring-egreta

# Linux/macOS: compilar i arrencar amb perfil dev
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev

# Windows (PowerShell/CMD):
mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=dev"
```

L'aplicació estarà disponible a `http://localhost:8080`.

El portal web (pàgina principal) s'accedeix directament: `http://localhost:8080/index.html`

---

## Estructura del projecte

```
src/main/
├── java/com/example/demo/
│   ├── controller/        # REST controllers per a cada domini
│   ├── model/             # Entitats MongoDB
│   ├── repository/        # Spring Data repositories
│   ├── service/           # Lògica de negoci
│   ├── config/            # Configuració de Spring (seguretat, MongoDB secundari…)
│   └── util/              # Utilitats generals
└── webapp/                # Interfície web estàtica (HTML + JS + CSS)
```

---

## API REST

En entorn local (`dev`) els endpoints requereixen autenticació HTTP Basic.
En altres perfils, l'accés es governa per CAS segons la configuració de seguretat.

| Recurs | Base path |
|--------|-----------|
| Publicacions (Pure / Research Outputs) | `/api/pure` |
| Persones | `/api/persons` |
| Premis (Awards) | `/api/awards` |
| Sol·licituds (Applications) | `/api/applications` |
| Oportunitats de finançament | `/api/funding-opportunities` |
| Tesis doctorals | `/api/student-theses` |
| Revistes (JCR) | `/api/journals` |
| Organitzacions externes | `/api/external-organizations` |
| Esquema MongoDB | `/api/mongo` |

Tots els recursos s'exposen en format canònic sota `/api/...`.

### Política de rutes (canònic + legacy)

- **Canònic:** totes les rutes del frontend i backend usen `/api/...`.
- **Legacy retirat per defecte:** `app.api.legacy-endpoints-enabled=false`.
- **Resposta per rutes antigues:** `410 Gone` amb indicació de ruta successora.
- **Base API per entorn:**
	- Desenvolupament: `/api`
	- Producció: `/otr/api`
	- Resolució automàtica al frontend segons context d'URL (`/otr/*` -> `/otr/api`, altrament `/api`)
	- Override opcional via `window.APP_CONFIG.apiBaseUrl` o meta `api-base-url`

### Exemples d'endpoints destacats

```
GET /api/awards/stats/categories          # Recompte per categoria de premi
GET /api/awards/stats/powertable          # Taula creuada d'ajuts
GET /api/awards/stats/persona-resumen     # Resum d'ajuts per investigador
GET /api/awards/reports/word/country      # Informe Word per país
GET /api/pure/stats/quartiles             # Distribució de quartils per departament
GET /api/student-theses/stats/same-author-director  # Tesis amb autor i director coincidents
GET /api/pure/stats/types-by-year         # Tipus de publicació per any
GET /api/pure/stats/apa                   # Llistat APA de publicacions
GET /api/persons/search/active            # Cerca de personal actiu
GET /api/persons/reports/word/person      # Informe Word per persona
```

Consulta també [`ENDPOINTS_MIGRATION.md`](ENDPOINTS_MIGRATION.md) per al detall de rutes canòniques i aliases.

---

## Informes web disponibles

| Informe | Fitxer |
|---------|--------|
| Ajuts per investigador | `persona-resumen.html` |
| Producció científica (quartils) | `quartiles-departamento.html` |
| Personal actiu UAB | `personas-vigentes-uab.html` |
| Premis | `awards.html` |
| Oportunitats de finançament | `funding-opportunities.html` |
| Organitzacions externes | `organizaciones-externas.html` |
| Tesis doctorals | `tesis-duplicadas.html` |
| Publicacions per persona | `publicaciones-persona.html` |

---

## Empaquetament i desplegament

```bash
# Generar el WAR
./mvnw clean package -DskipTests

# Windows
mvnw.cmd clean package -DskipTests

# El fitxer resultant es troba a:
target/otr.war
```

Desplegeu `otr.war` en un servidor Tomcat 11+ compatible amb Jakarta EE 10.

---

## Llicència

Ús intern de la UAB — Oficina de Transferència de Resultats de Recerca (OTR).
