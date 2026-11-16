package com.example.demo.curation.service;

import com.example.demo.curation.util.CurationTextNormalizer;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Ranks Egreta's organization-type categories for an organization name (+ country) with a model learned from the
 * organizations that are already classified. Used to offer the "3 best options" to a person: a type cannot be
 * guessed reliably from the name alone (the best single answer is right about 6 times in 10), but the right one is
 * among the first three far more often.
 *
 * <p>The model is a multinomial logistic regression over TF-IDF features, trained offline by
 * {@code scripts/train_type_model.py} and shipped as {@code models/org-type-model.json.gz}. This class reproduces
 * scikit-learn's feature extraction exactly (character n-grams inside word boundaries, word uni/bi-grams, country
 * token, sublinear TF, smoothed IDF, L2 norm per block); a unit test checks it against numbers computed by
 * scikit-learn. Nothing is learned at runtime and no Python is needed.
 */
@Component
public class OrgTypeModel {

    private static final Logger log = LoggerFactory.getLogger(OrgTypeModel.class);

    /** A category and how likely the model considers it (0..1, the options of one name add up to 1). */
    public record Option(String label, double probability) {}

    @Value("${app.type-model.enabled:true}")
    private boolean enabled = true;

    @Value("${app.type-model.resource:models/org-type-model.json.gz}")
    private String resource = "models/org-type-model.json.gz";

    private volatile Loaded loaded;
    private volatile boolean loadFailed;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Payload {
        public String[] classes;
        public double[] intercept;
        public List<Block> blocks;
        public int[] coefFeature;
        public int[] coefClass;
        public float[] coefValue;
        public int[] featureCounts;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Block {
        public String name;
        public String[] vocab;
        public double[] idf;
    }

    private static final class Loaded {
        String[] classes;
        double[] intercept;
        Map<String, Integer> charIndex = new HashMap<>();
        Map<String, Integer> wordIndex = new HashMap<>();
        Map<String, Integer> countryIndex = new HashMap<>();
        double[] charIdf;
        double[] wordIdf;
        double[] countryIdf;
        int wordOffset;
        int countryOffset;
        /** Row-major [feature][class]. */
        float[] coef;
    }

    public OrgTypeModel() {
    }

    /** For tests and tooling: build the model from an already parsed artifact. */
    public OrgTypeModel(Payload payload) {
        this.loaded = build(payload);
    }

    public boolean isAvailable() {
        return enabled && ensureLoaded() != null;
    }

    /**
     * The {@code k} most likely categories, best first. Empty when the model is disabled or missing, or the name is blank.
     *
     * @param country the organization's country if known (any language; blank when unknown)
     */
    public List<Option> topOptions(String name, String country, int k) {
        if (!enabled || name == null || name.isBlank() || k <= 0) {
            return List.of();
        }
        Loaded m = ensureLoaded();
        if (m == null) {
            return List.of();
        }
        double[] p = probabilities(m, name, country);
        List<Option> all = new ArrayList<>(p.length);
        for (int c = 0; c < p.length; c++) {
            all.add(new Option(m.classes[c], p[c]));
        }
        all.sort(Comparator.comparingDouble(Option::probability).reversed());
        return List.copyOf(all.subList(0, Math.min(k, all.size())));
    }

    // ------------------------------------------------------------------------------------------------------------

    private Loaded ensureLoaded() {
        Loaded m = loaded;
        if (m != null || loadFailed) {
            return m;
        }
        synchronized (this) {
            if (loaded != null || loadFailed) {
                return loaded;
            }
            try (InputStream raw = new ClassPathResource(resource).getInputStream();
                 InputStream in = new GZIPInputStream(raw)) {
                loaded = build(new ObjectMapper().readValue(in, Payload.class));
                log.info("Organization type model loaded: {} categories, {} features", loaded.classes.length, loaded.coef.length / loaded.classes.length);
            } catch (Exception e) {
                loadFailed = true;
                log.warn("Organization type model '{}' could not be loaded; the type options are disabled: {}", resource, e.getMessage());
            }
            return loaded;
        }
    }

    private static Loaded build(Payload p) {
        Loaded m = new Loaded();
        m.classes = p.classes;
        m.intercept = p.intercept;
        for (Block b : p.blocks) {
            Map<String, Integer> index = new HashMap<>(b.vocab.length * 2);
            for (int i = 0; i < b.vocab.length; i++) {
                index.put(b.vocab[i], i);
            }
            switch (b.name) {
                case "char" -> { m.charIndex = index; m.charIdf = b.idf; }
                case "word" -> { m.wordIndex = index; m.wordIdf = b.idf; }
                case "country" -> { m.countryIndex = index; m.countryIdf = b.idf; }
                default -> throw new IllegalArgumentException("unknown feature block " + b.name);
            }
        }
        m.wordOffset = m.charIdf.length;
        m.countryOffset = m.wordOffset + m.wordIdf.length;
        int nFeatures = m.countryOffset + m.countryIdf.length;
        int nClasses = m.classes.length;
        m.coef = new float[nFeatures * nClasses];
        for (int i = 0; i < p.coefValue.length; i++) {
            m.coef[p.coefFeature[i] * nClasses + p.coefClass[i]] = p.coefValue[i];
        }
        return m;
    }

    private static double[] probabilities(Loaded m, String name, String country) {
        int nClasses = m.classes.length;
        double[] scores = m.intercept.clone();
        String text = CurationTextNormalizer.normalizeText(name);

        // block 1: character n-grams (2..5) inside word boundaries
        addBlock(scores, m, countOf(charWbNgrams(text), m.charIndex), m.charIdf, 0);
        // block 2: word unigrams + bigrams
        addBlock(scores, m, countOf(wordNgrams(text), m.wordIndex), m.wordIdf, m.wordOffset);
        // block 3: the country as a single token ("none" when unknown); one feature, so its L2-normalized value is 1
        String countryToken = CurationTextNormalizer.normalizeText(country);
        if (countryToken.isBlank()) {
            countryToken = "none";
        }
        Integer ci = m.countryIndex.get(countryToken);
        if (ci != null) {
            int base = (m.countryOffset + ci) * nClasses;
            for (int c = 0; c < nClasses; c++) {
                scores[c] += m.coef[base + c];
            }
        }

        // softmax
        double max = Double.NEGATIVE_INFINITY;
        for (double s : scores) {
            max = Math.max(max, s);
        }
        double sum = 0;
        double[] p = new double[nClasses];
        for (int c = 0; c < nClasses; c++) {
            p[c] = Math.exp(scores[c] - max);
            sum += p[c];
        }
        for (int c = 0; c < nClasses; c++) {
            p[c] /= sum;
        }
        return p;
    }

    /** TF-IDF of one block: sublinear tf (1 + ln count) times idf, L2 normalized, then multiplied by the weights. */
    private static void addBlock(double[] scores, Loaded m, Map<Integer, Integer> counts, double[] idf, int offset) {
        if (counts.isEmpty()) {
            return;
        }
        double norm = 0;
        Map<Integer, Double> values = new HashMap<>(counts.size() * 2);
        for (Map.Entry<Integer, Integer> e : counts.entrySet()) {
            double v = (1.0 + Math.log(e.getValue())) * idf[e.getKey()];
            values.put(e.getKey(), v);
            norm += v * v;
        }
        norm = Math.sqrt(norm);
        if (norm == 0) {
            return;
        }
        int nClasses = m.classes.length;
        for (Map.Entry<Integer, Double> e : values.entrySet()) {
            double w = e.getValue() / norm;
            int base = (offset + e.getKey()) * nClasses;
            for (int c = 0; c < nClasses; c++) {
                scores[c] += w * m.coef[base + c];
            }
        }
    }

    private static Map<Integer, Integer> countOf(List<String> terms, Map<String, Integer> index) {
        Map<Integer, Integer> counts = new HashMap<>();
        for (String t : terms) {
            Integer i = index.get(t);
            if (i != null) {
                counts.merge(i, 1, Integer::sum);
            }
        }
        return counts;
    }

    /** scikit-learn's analyzer="char_wb" with ngram_range=(2, 5). */
    static List<String> charWbNgrams(String text) {
        List<String> out = new ArrayList<>();
        for (String word : text.split("\\s+")) {
            if (word.isEmpty()) {
                continue;
            }
            String w = " " + word + " ";
            int len = w.length();
            for (int n = 2; n <= 5; n++) {
                int offset = 0;
                out.add(w.substring(offset, Math.min(offset + n, len)));
                while (offset + n < len) {
                    offset++;
                    out.add(w.substring(offset, Math.min(offset + n, len)));
                }
                if (offset == 0) { // a word shorter than n is counted only once
                    break;
                }
            }
        }
        return out;
    }

    /** scikit-learn's analyzer="word", token_pattern=[a-z0-9]+, ngram_range=(1, 2). */
    static List<String> wordNgrams(String text) {
        List<String> tokens = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[a-z0-9]+").matcher(text);
        while (m.find()) {
            tokens.add(m.group());
        }
        List<String> out = new ArrayList<>(tokens);
        for (int i = 0; i + 1 < tokens.size(); i++) {
            out.add(tokens.get(i) + " " + tokens.get(i + 1));
        }
        return out;
    }
}
