package com.example.demo.util;

import org.bson.Document;
import java.util.ArrayList;
import java.util.List;

public final class PublicacionDocumentUtils {
    private PublicacionDocumentUtils() {}

    public static List<Document> getListDocument(Document doc, String key) {
        Object val = doc.get(key);
        if (val instanceof List<?> rawList) {
            List<Document> result = new ArrayList<>();
            for (Object item : rawList) {
                if (item instanceof Document d) result.add(d);
            }
            return result;
        }
        return new ArrayList<>();
    }

    public static Document getNestedDocument(Document doc, String... path) {
        Object current = doc;
        for (String key : path) {
            if (current instanceof Document d) current = d.get(key);
            else return null;
        }
        return current instanceof Document d ? d : null;
    }

    public static Object getByPath(Document root, String... path) {
        Object current = root;
        for (String key : path) {
            if (!(current instanceof Document doc)) return null;
            current = doc.get(key);
        }
        return current;
    }

    public static String nestedString(Document root, String... path) {
        Object val = getByPath(root, path);
        return val instanceof String s ? s : null;
    }

    public static String decodeHtmlEntities(String text) {
        if (text == null) return "";
        return text
            .replaceAll("(?i)&amp;", "&").replaceAll("(?i)&quot;", "\"").replaceAll("(?i)&apos;", "'")
            .replaceAll("&#39;", "'").replaceAll("&#039;", "'").replaceAll("(?i)&lt;", "<")
            .replaceAll("(?i)&gt;", ">").replaceAll("(?i)&nbsp;", " ").replaceAll("(?i)&ndash;", "-")
            .replaceAll("(?i)&mdash;", "-").replaceAll("(?i)&hellip;", "...").replaceAll("(?i)&#x2013;", "-")
            .replaceAll("(?i)&#x2014;", "-").replaceAll("&#8211;", "-").replaceAll("&#8212;", "-")
            .replaceAll("&#8216;", "'").replaceAll("&#8217;", "'").replaceAll("(?i)&lsquo;", "'").replaceAll("(?i)&rsquo;", "'")
            .replaceAll("&#8220;", "\"").replaceAll("&#8221;", "\"").replaceAll("(?i)&ldquo;", "\"").replaceAll("(?i)&rdquo;", "\"");
    }

    public static String cleanRawTitle(String text) {
        if (text == null) return "";
        String s = decodeHtmlEntities(text);
        s = s.replaceAll("<[^>]*>", " ");
        return s.trim();
    }

    public static String normalizeForComparison(String text) {
        if (text == null) return "";
        String s = cleanRawTitle(text);
        s = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return s.toLowerCase().replaceAll("[^a-z0-9\\s]", " ").replaceAll("\\s+", " ").trim();
    }

    public static String normalizeAuthorName(String name) {
        if (name == null) return "";
        String s = cleanRawTitle(name);
        return normalizeForComparison(s);
    }

    public static boolean isValidIsbn(String isbn) {
        if (isbn == null) return false;
        String clean = isbn.replaceAll("[\\s-]", "");
        if (clean.length() != 10 && clean.length() != 13) return false;
        for (int i = 0; i < clean.length(); i++) {
            char c = clean.charAt(i);
            if (i == 9 && clean.length() == 10 && (c == 'X' || c == 'x')) continue;
            if (!Character.isDigit(c)) return false;
        }
        return true;
    }

    public static boolean isValidIssn(String issn) {
        if (issn == null) return false;
        String clean = issn.replaceAll("[\\s-]", "");
        if (clean.length() != 8) return false;
        for (int i = 0; i < 8; i++) {
            char c = clean.charAt(i);
            if (i == 7 && (c == 'X' || c == 'x')) continue;
            if (!Character.isDigit(c)) return false;
        }
        return true;
    }

    public static boolean isAllUppercase(String text) {
        if (text == null || text.isBlank()) return false;
        String trimmed = text.trim();
        // Si el text és només una paraula (sense espais), no es considera error de majúscules
        if (!trimmed.contains(" ") && !trimmed.contains("\t") && !trimmed.contains("\n")) {
            return false;
        }
        boolean hasLetters = false;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (Character.isLetter(c)) {
                hasLetters = true;
                if (Character.isLowerCase(c)) return false;
            }
        }
        return hasLetters;
    }

    public static boolean containsStrangeCharacters(String text) {
        if (text == null) return false;
        return text.contains("Ã³") || text.contains("Ã©") || text.contains("Ã¡") || text.contains("Ãº") ||
               text.contains("â€™") || text.contains("Ã ") || text.contains("Ã²") || text.contains("Ã§") ||
               text.contains("Ã€") || text.contains("Ãˆ") || text.contains("ï¿½") || text.contains("Ã­") ||
               text.contains("Ã ") || text.contains("Ã‰") || text.contains("Ã“") || text.contains("Ãš") ||
               text.contains("Ã‘") || text.contains("Ã±") || text.contains("Â·") ||
               text.contains("&apos;") || text.contains("&#39;") || text.contains("&#039;");
    }

    public static String fixStrangeCharacters(String text) {
        if (text == null) return null;
        return decodeHtmlEntities(text)
            .replace("Ã¡", "á").replace("Ã ", "à").replace("Ã©", "é").replace("Ã¨", "è")
            .replace("Ã­", "í").replace("Ã³", "ó").replace("Ã²", "ò").replace("Ãº", "ú")
            .replace("Ã¼", "ü").replace("Ã±", "ñ").replace("Ã§", "ç")
            .replace("Ã ", "Á").replace("Ã€", "À").replace("Ã‰", "É").replace("Ãˆ", "È")
            .replace("Ã ", "Í").replace("Ã“", "Ó").replace("Ã’", "Ò").replace("Ãš", "Ú")
            .replace("Ãœ", "Ü").replace("Ã‘", "Ñ").replace("Ã‡", "Ç")
            .replace("Â·", "·").replace("â€™", "'").replace("â€œ", "\"").replace("â€", "\"")
            .replace("â€“", "-").replace("â€”", "-").replace("â€¦", "...")
            .replace("ï¿½", "");
    }

    private static final java.util.Set<String> STOP_WORDS = java.util.Set.of(
        "a", "an", "the", "and", "or", "nor", "but", "for", "at", "by", "in", "of", "on", "to", "up", "with", "from", "as",
        "de", "del", "el", "la", "los", "las", "un", "una", "unos", "unas", "y", "e", "o", "u", "en", "por", "para", "con", "sin", "sobre", "tras",
        "i", "per", "amb", "sense", "d'", "l'", "els", "les", "al", "als", "pel", "pels"
    );

    private static final java.util.Set<String> PRESERVE_ACRONYMS = java.util.Set.of(
        "DNA", "RNA", "MRNA", "CRISPR", "COVID", "COVID-19", "HIV", "AIDS", "UAB", "CSIC", "EU", "USA", "UK", "UN", "WHO",
        "MRI", "PET", "CT", "ECG", "EEG", "PCR", "HPLC", "NMR", "AI", "ML", "CNN", "RNN", "NLP", "LLM", "GPS", "GIS", "IoT",
        "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI", "XII", "XIII", "XIV", "XV", "XVI", "XVII", "XVIII", "XIX", "XX"
    );

    private static final java.util.Map<String, String> PROPER_NOUNS = new java.util.HashMap<>();

    static {
        // Scientists, Mathematicians, Physicians & Eponyms
        String[] properList = {
            "Ricci", "Gauss", "Gaussian", "Euler", "Eulerian", "Fourier", "Einstein", "Riemann", "Riemannian",
            "Hilbert", "Markov", "Markovian", "Bayes", "Bayesian", "Newton", "Newtonian", "Schrödinger", "Schrodinger",
            "Dirac", "Cauchy", "Laplace", "Laplacian", "Hamilton", "Hamiltonian", "Lagrange", "Lagrangian",
            "Taylor", "Maxwell", "Boltzmann", "Navier", "Stokes", "Banach", "Poisson", "Jacobi", "Jacobian",
            "Fermat", "Turing", "Minkowski", "Lorentz", "Lorentzian", "Euclid", "Euclidean", "Coulomb", "Kelvin",
            "Celsius", "Joule", "Hertz", "Watt", "Pascal", "Ohm", "Ampere", "Volt", "Faraday", "Debye", "Planck",
            "Bohr", "Heisenberg", "Kepler", "Copernicus", "Galileo", "Galilean", "Gödel", "Godel", "Shannon",
            "Dijkstra", "Knuth", "Lovelace", "Bernoulli", "Chebyshev", "Hermite", "Hermitian", "Lie", "Sobolev",
            "Lebesgue", "Peano", "Borel", "Cantor", "Dedekind", "Abel", "Abelian", "Galois", "Noether", "Noetherian",
            "Clifford", "Hodge", "Kähler", "Kahler", "Betti", "Poincaré", "Poincare", "Cartan", "Weyl", "De Rham",
            "Levi-Civita", "Christoffel", "Schwarzschild", "Kerr", "Chandrasekhar", "Hawking", "Penrose", "Feynman",
            "Landau", "Fermi", "Bose", "Higgs", "Yukawa", "Rutherford", "Thomson", "Röntgen", "Roentgen", "Curie",
            "Mendeleev", "Avogadro", "Gibbs", "Arrhenius", "Nernst", "Pauling", "Lewis", "Brønsted", "Bronsted",
            "Darwin", "Darwinian", "Mendel", "Mendelian", "Lamarck", "Pasteur", "Koch", "Jenner", "Fleming",
            "Lister", "Cajal", "Servet", "Harvey", "Vesalius", "Linnaeus", "Watson", "Crick", "Franklin", "Sanger",
            "Charcot", "Alzheimer", "Parkinson", "Huntington", "Crohn", "Down", "Asperger", "Tourette", "Hodgkin",
            "Lyme", "Ebola", "Zika", "Chagas", "Hansen", "Epstein-Barr", "Bell", "Cushing", "Addison", "Graves",
            "Hashimoto", "Ménière", "Meniere", "Sjögren", "Sjogren", "Guillain-Barré", "Guillain-Barre",
            "Creutzfeldt-Jakob", "Raynaud", "Paget", "Marfan", "Wilson", "Gaucher", "Babinski",

            // Philosophers, Thinkers, Social Scientists & Economists
            "Socrates", "Socratic", "Plato", "Platonic", "Aristotle", "Aristotelian", "Epicurus", "Epicurean",
            "Augustine", "Aquinas", "Machiavelli", "Machiavellian", "Hobbes", "Hobbesian", "Locke", "Lockean",
            "Spinoza", "Leibniz", "Descartes", "Cartesian", "Hume", "Humean", "Rousseau", "Kant", "Kantian",
            "Fichte", "Schelling", "Hegel", "Hegelian", "Schopenhauer", "Kierkegaard", "Marx", "Marxist", "Marxism",
            "Engels", "Nietzsche", "Nietzschean", "Husserl", "Heidegger", "Sartre", "Camus", "Foucault", "Foucauldian",
            "Derrida", "Deleuze", "Guattari", "Lyotard", "Baudrillard", "Lacan", "Lacanian", "Habermas", "Adorno",
            "Horkheimer", "Benjamin", "Marcuse", "Arendt", "Gramsci", "Gramscian", "Althusser", "Bourdieu",
            "Weber", "Weberian", "Durkheim", "Durkheimian", "Simmel", "Parsons", "Goffman", "Veblen", "Smith",
            "Ricardo", "Malthus", "Malthusian", "Keynes", "Keynesian", "Hayek", "Schumpeter", "Friedman",
            "Samuelson", "Sen", "Stiglitz", "Krugman", "Piketty", "Chomsky", "Chomskyan", "Saussure", "Jakobson",
            "Barthes", "Eco", "Bakhtin", "Vygotsky", "Piaget", "Freud", "Freudian", "Jung", "Jungian", "Adler",
            "Skinner", "Pavlov", "Pavlovian", "Maslow", "Rogers", "Bowlby", "Lorenz",

            // Places, Countries, Regions, Languages & Nationalities
            "Spain", "Spanish", "Spaniards", "Catalonia", "Catalan", "Catalans", "Basque", "Basques", "Euskadi",
            "Galicia", "Galician", "Andalusia", "Andalusian", "Valencia", "Valencian", "Madrid", "Barcelona",
            "Balearic", "Mallorca", "Menorca", "Ibiza", "Girona", "Lleida", "Tarragona", "Aragon", "Aragonese",
            "Castile", "Castilian", "France", "French", "Paris", "Italy", "Italian", "Rome", "Germany", "German",
            "Berlin", "Portugal", "Portuguese", "Lisbon", "UK", "Britain", "British", "England", "English",
            "London", "Scotland", "Scottish", "Ireland", "Irish", "Europe", "European", "America", "American",
            "USA", "Canada", "Canadian", "Mexico", "Mexican", "Brazil", "Brazilian", "Argentina", "Argentine",
            "Chile", "Chilean", "Colombia", "Colombian", "Peru", "Peruvian", "Cuba", "Cuban", "Asia", "Asian",
            "China", "Chinese", "Beijing", "Japan", "Japanese", "Tokyo", "Korea", "Korean", "Seoul", "India",
            "Indian", "Russia", "Russian", "Moscow", "Africa", "African", "Egypt", "Egyptian", "Australia",
            "Australian", "Mediterranean", "Atlantic", "Pacific", "Arctic", "Antarctic", "Pyrenees", "Alps",
            "Sahara", "Amazon", "Danube", "Rhine", "Ebro", "Tajo",

            // Catalan / Spanish geographical & language terms
            "España", "Español", "Española", "Españoles", "Españolas", "Catalunya", "Cataluña", "Català",
            "Catalana", "Catalans", "Catalanes", "Francès", "Francesa", "Francia", "Francés", "França", "Itàlia",
            "Italia", "Italià", "Italiana", "Italiano", "Alemanya", "Alemania", "Alemany", "Alemana", "Alemán",
            "Portuguès", "Portuguesa", "Portugués", "Europa", "Europeu", "Europea", "Europeos", "Europeas",
            "Amèrica", "América", "Americà", "Americana", "Americanos", "Americanas", "Àsia", "Asiàtic",
            "Asiática", "Asiático", "Àfrica", "África", "Africà", "Africana", "Africano", "Xina", "Xinès",
            "Chino", "Japó", "Japón", "Japonès", "Japonés"
        };

        for (String p : properList) {
            PROPER_NOUNS.put(p.toUpperCase(), p);
        }
    }

    private static String formatSingleWord(String cleanWord, boolean capitalizeFirst, java.util.Map<String, String> properMap) {
        if (cleanWord.isEmpty()) return cleanWord;
        String upper = cleanWord.toUpperCase();

        if (PRESERVE_ACRONYMS.contains(upper)) {
            return upper;
        }
        if (properMap != null && properMap.containsKey(upper)) {
            return properMap.get(upper);
        }

        // Si té guionet (ex: NAVIER-STOKES, COVID-19, GAUSS-BONNET)
        if (cleanWord.contains("-")) {
            String[] parts = cleanWord.split("-");
            StringBuilder hypSb = new StringBuilder();
            for (int p = 0; p < parts.length; p++) {
                hypSb.append(formatSingleWord(parts[p], p == 0 ? capitalizeFirst : true, properMap));
                if (p < parts.length - 1) hypSb.append("-");
            }
            return hypSb.toString();
        }

        if (capitalizeFirst) {
            return Character.toUpperCase(cleanWord.charAt(0))
                    + (cleanWord.length() > 1 ? cleanWord.substring(1).toLowerCase() : "");
        } else {
            return cleanWord.toLowerCase();
        }
    }

    public static String fixCapitalization(String text, boolean isTitle) {
        return fixCapitalization(text, isTitle, null);
    }

    public static String fixCapitalization(String text, boolean isTitle, java.util.Collection<String> extraProperNouns) {
        if (text == null || text.isBlank() || !isAllUppercase(text)) {
            return text;
        }
        text = text.trim();
        java.util.Map<String, String> localProper = PROPER_NOUNS;
        if (extraProperNouns != null && !extraProperNouns.isEmpty()) {
            localProper = new java.util.HashMap<>(PROPER_NOUNS);
            for (String ep : extraProperNouns) {
                if (ep != null && !ep.isBlank() && ep.trim().length() > 1) {
                    String cleanEp = ep.trim();
                    String cap = Character.toUpperCase(cleanEp.charAt(0)) + (cleanEp.length() > 1 ? cleanEp.substring(1).toLowerCase() : "");
                    localProper.put(cleanEp.toUpperCase(), cap);
                }
            }
        }

        String[] words = text.split("\\s+");
        StringBuilder sb = new StringBuilder();
        boolean capitalizeNext = true;
        for (int i = 0; i < words.length; i++) {
            String word = words[i];
            if (word.isEmpty()) continue;

            String cleanWord = word.replaceAll("^[^\\p{L}0-9]+|[^\\p{L}0-9]+$", "");
            if (cleanWord.isEmpty()) {
                sb.append(word);
            } else {
                String formatted = formatSingleWord(cleanWord, capitalizeNext, localProper);
                sb.append(word.replace(cleanWord, formatted));
            }

            capitalizeNext = word.endsWith(":") || word.endsWith(".") || word.endsWith("?") || word.endsWith("!");
            if (i < words.length - 1) sb.append(" ");
        }
        return sb.toString();
    }

    public static String cleanTextQuality(String text, boolean isTitle) {
        return cleanTextQuality(text, isTitle, null);
    }

    public static String cleanTextQuality(String text, boolean isTitle, java.util.Collection<String> extraProperNouns) {
        if (text == null) return null;
        String fixed = fixStrangeCharacters(text);
        fixed = fixCapitalization(fixed, isTitle, extraProperNouns);
        if (fixed != null) fixed = fixed.replaceAll("\\s+", " ").trim();
        return fixed;
    }

    public static String sanitizeAbstractText(String text) {
        if (text == null) return null;
        text = text.replace('\u201C', '"').replace('\u201D', '"')
            .replace('\u2018', '\'').replace('\u2019', '\'')
            .replace('\u00AB', '"').replace('\u00BB', '"')
            .replace('\u2039', '\'').replace('\u203A', '\'')
            .replace('\u201E', '"').replace('\u201F', '"')
            .replace("\u2026", "...").replace('\u2013', '-').replace('\u2014', '-');
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 0x20 || c == '\t' || c == '\n' || c == '\r') && !(c >= 0x7F && c <= 0x9F)) sb.append(c);
        }
        return sb.toString();
    }

    public static java.util.Map.Entry<String, String> splitTitleAndSubtitle(String fullTitle, String existingSubTitle) {
        if (fullTitle == null) {
            return java.util.Map.entry("", existingSubTitle != null ? existingSubTitle : "");
        }
        String t = fullTitle.trim();
        String st = existingSubTitle != null ? existingSubTitle.trim() : "";

        if (!st.isEmpty()) {
            if (t.toLowerCase().endsWith(": " + st.toLowerCase())) {
                t = t.substring(0, t.length() - (": " + st).length()).trim();
            }
            return java.util.Map.entry(t, st);
        }

        int colonIdx = t.indexOf(':');
        if (colonIdx > 0 && colonIdx < t.length() - 1) {
            String mainT = t.substring(0, colonIdx).trim();
            String subT = t.substring(colonIdx + 1).trim();
            return java.util.Map.entry(mainT, subT);
        }

        return java.util.Map.entry(t, st);
    }

    public static String escapeXmlExceptEntities(String text) {
        if (text == null) return null;
        text = text.replace("<", "&lt;").replace(">", "&gt;");
        return text.replaceAll("&(?![a-zA-Z0-9#]+;)", "&amp;");
    }

    public static boolean hasDuplicateUrlOfType(Document p) {
        List<Document> lks = getListDocument(p, "links");
        if (lks != null) {
            java.util.Map<String, Integer> counts = new java.util.HashMap<>();
            for (Document lk : lks) {
                Document lt = (Document) lk.get("linkType");
                String ltUri = lt != null ? lt.getString("uri") : null;
                if (ltUri != null && !ltUri.isEmpty()) counts.put(ltUri, counts.getOrDefault(ltUri, 0) + 1);
            }
            for (int count : counts.values()) if (count > 1) return true;
        }
        return false;
    }
}