#!/usr/bin/env python3
"""
Trains the organization-type classifier used to offer the "3 best options" in organizaciones-externas-kpis.html.

It learns, from the organizations that already have one of Egreta's type categories, which category goes with a
name (+ country). The model is exported as a plain JSON.GZ so the application can score names in pure Java, without
Python at runtime, and a fixtures file lets a unit test prove that Java and scikit-learn give the same numbers.

Usage (from the repository root):
    python scripts/train_type_model.py
    python scripts/train_type_model.py --out src/main/resources/models/org-type-model.json.gz \
                                       --fixtures src/test/resources/org-type-model-fixtures.json

The Mongo connection is read from MONGO_URI, or from spring.mongodb.uri in src/main/resources/application.properties.
Requires: pymongo, scikit-learn, numpy, scipy.

IMPORTANT: normalize() must stay identical to CurationTextNormalizer.normalizeText() in Java.
"""
import argparse
import gzip
import json
import os
import re
import sys
import time
import unicodedata
from collections import Counter

import numpy as np
from pymongo import MongoClient
from scipy.sparse import hstack
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.linear_model import LogisticRegression

BULK_ACCOUNT = '1586538'  # account that bulk-loaded many records: kept apart to measure on cleaner data
CHAR_FEATURES = 60000
WORD_FEATURES = 25000
COUNTRY_DROPOUT = 0.30  # the country is often unknown when a type has to be suggested
PRUNE_BELOW = 0.05      # weights smaller than this are dropped to keep the artifact small


def normalize(value):
    """Same steps as CurationTextNormalizer.normalizeText (NFD, strip marks, lower, non [a-z0-9] -> space)."""
    s = unicodedata.normalize('NFD', value or '')
    s = ''.join(ch for ch in s if not unicodedata.category(ch).startswith('M'))
    s = s.lower()
    s = re.sub(r'[^a-z0-9\s]', ' ', s, flags=re.ASCII)
    s = re.sub(r'\s+', ' ', s, flags=re.ASCII).strip()
    return s


def country_token(country):
    return normalize(country) or 'none'


def mongo_uri():
    if os.environ.get('MONGO_URI'):
        return os.environ['MONGO_URI']
    props = os.path.join('src', 'main', 'resources', 'application.properties')
    for line in open(props, encoding='utf-8'):
        if line.startswith('spring.mongodb.uri='):
            return line.split('=', 1)[1].strip()
    sys.exit('No Mongo URI: set MONGO_URI or spring.mongodb.uri')


def load_rows(db):
    scheme = db['Classificationschemes'].find_one({'baseUri': '/dk/atira/pure/ueoexternalorganisation/ueoexternalorganisationtypes'})
    valid = {}
    for c in scheme['containedClassifications']:
        uri = (c.get('uri') or '').strip()
        label = ((c.get('term') or {}).get('ca_ES') or '').strip()
        if c.get('disabled') or not uri or not label:
            continue
        if uri.lower().endswith(('/unknown', '/ueoexternalorganisation')):
            continue
        valid[uri] = label
    rows = []
    for d in db['ExternalOrganizations'].find({}, {'uuid': 1, 'name': 1, 'type': 1, 'address': 1, 'modifiedBy': 1}):
        label = valid.get(((d.get('type') or {}).get('uri') or '').strip())
        if not label:
            continue
        nm = d.get('name') or {}
        name = nm.get('en_GB') or nm.get('ca_ES') or nm.get('es_ES') or ''
        if not name.strip():
            continue
        country = (((d.get('address') or {}).get('country') or {}).get('term') or {}).get('en_GB') or ''
        rows.append(dict(uuid=d['uuid'], name=name, country=country, label=label, bulk=d.get('modifiedBy') == BULK_ACCOUNT))
    return valid, rows


class Model:
    def __init__(self):
        self.char = TfidfVectorizer(analyzer='char_wb', ngram_range=(2, 5), min_df=3, sublinear_tf=True, max_features=CHAR_FEATURES)
        self.word = TfidfVectorizer(analyzer='word', ngram_range=(1, 2), min_df=2, sublinear_tf=True,
                                    token_pattern=r'[a-z0-9]+', max_features=WORD_FEATURES)
        self.cty = TfidfVectorizer(analyzer=lambda s: [s], min_df=2)
        self.clf = LogisticRegression(C=20, max_iter=400, solver='lbfgs')

    def _features(self, names, countries, fit):
        t = [normalize(n) for n in names]
        c = [country_token(x) for x in countries]
        if fit:
            return hstack([self.char.fit_transform(t), self.word.fit_transform(t), self.cty.fit_transform(c)]).tocsr()
        return hstack([self.char.transform(t), self.word.transform(t), self.cty.transform(c)]).tocsr()

    def fit(self, rows, seed=7):
        rng = np.random.RandomState(seed)
        countries = [r['country'] if rng.rand() > COUNTRY_DROPOUT else '' for r in rows]
        X = self._features([r['name'] for r in rows], countries, fit=True)
        self.clf.fit(X, [r['label'] for r in rows])
        return self

    def proba(self, names, countries):
        return self.clf.predict_proba(self._features(names, countries, fit=False))

    def prune(self, threshold):
        w = self.clf.coef_.copy()
        w[np.abs(w) < threshold] = 0.0
        self.clf.coef_ = w

    def quantize(self):
        """Round to the precision that is written to the artifact, so that the fixtures that prove Java == sklearn
        are computed from exactly the numbers Java reads."""
        self.clf.coef_ = np.round(self.clf.coef_, 4)
        self.clf.intercept_ = np.round(self.clf.intercept_, 6)
        for vec in (self.char, self.word, self.cty):
            vec.idf_ = np.round(vec.idf_, 6)


def report(title, model, rows):
    p = model.proba([r['name'] for r in rows], [r['country'] for r in rows])
    cls = model.clf.classes_
    truth = np.array([r['label'] for r in rows])
    top1 = cls[p.argmax(1)]
    ok = top1 == truth
    top3 = np.mean([truth[i] in cls[np.argsort(-p[i])[:3]] for i in range(len(rows))])
    conf = p.max(1)
    hi = conf >= 0.9
    print(f'  {title:34s} n={len(rows):6d}  top-1 {ok.mean():6.1%}  top-3 {top3:6.1%}'
          f'  | conf>=0.9: covers {hi.mean():4.0%}, precision {ok[hi].mean() if hi.any() else 0:6.1%}')


def export(model, path):
    classes = [str(c) for c in model.clf.classes_]
    blocks = []
    n_char = len(model.char.vocabulary_)
    n_word = len(model.word.vocabulary_)
    n_cty = len(model.cty.vocabulary_)
    for name, vec in (('char', model.char), ('word', model.word), ('country', model.cty)):
        vocab = [None] * len(vec.vocabulary_)
        for term, idx in vec.vocabulary_.items():
            vocab[idx] = term
        blocks.append({'name': name, 'vocab': vocab, 'idf': [round(float(x), 6) for x in vec.idf_]})
    coef = model.clf.coef_  # classes x features, features ordered char | word | country
    feat, cls_idx, val = [], [], []
    for c in range(coef.shape[0]):
        nz = np.nonzero(coef[c])[0]
        feat.extend(int(i) for i in nz)
        cls_idx.extend([c] * len(nz))
        val.extend(round(float(coef[c][i]), 4) for i in nz)
    payload = {
        'version': 1,
        'trainedAt': time.strftime('%Y-%m-%d'),
        'classes': classes,
        'intercept': [round(float(x), 6) for x in model.clf.intercept_],
        'blocks': blocks,
        'coefFeature': feat,
        'coefClass': cls_idx,
        'coefValue': val,
        'featureCounts': [n_char, n_word, n_cty],
    }
    os.makedirs(os.path.dirname(path) or '.', exist_ok=True)
    with gzip.open(path, 'wt', encoding='utf-8', compresslevel=9) as f:
        json.dump(payload, f, ensure_ascii=False, separators=(',', ':'))
    return os.path.getsize(path)


def export_fixtures(model, rows, path, n=60):
    rng = np.random.RandomState(11)
    pick = [rows[i] for i in rng.permutation(len(rows))[:n]]
    extra = [('Fundació Pare Manel', ''), ('Fundació Privada Pare Manel', 'Spain'), ('Universitat Autònoma de Barcelona', 'Spain'),
             ('Hospital Clínic de Barcelona', ''), ('Société Générale — Laboratoire d\'Études', 'France'), ('Straße der Wissenschaften GmbH', 'Germany'),
             ('', ''), ('X', 'none'), ('Ústav polymerů Slovenskej akadémie vied', 'Slovakia'), ('ESADE Business School', '')]
    cases = [(r['name'], r['country']) for r in pick] + extra
    p = model.proba([c[0] for c in cases], [c[1] for c in cases])
    cls = model.clf.classes_
    out = []
    for (name, country), probs in zip(cases, p):
        order = np.argsort(-probs)[:5]
        out.append({'name': name, 'country': country, 'top': [{'label': str(cls[i]), 'p': round(float(probs[i]), 6)} for i in order]})
    os.makedirs(os.path.dirname(path) or '.', exist_ok=True)
    with open(path, 'w', encoding='utf-8') as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
    return len(out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--out', default='src/main/resources/models/org-type-model.json.gz')
    ap.add_argument('--fixtures', default='src/test/resources/org-type-model-fixtures.json')
    ap.add_argument('--no-final', action='store_true', help='only evaluate; do not retrain on all data and export')
    args = ap.parse_args()

    db = MongoClient(mongo_uri(), serverSelectionTimeoutMS=10000).get_default_database()
    valid, rows = load_rows(db)
    print(f'{len(valid)} categories, {len(rows)} classified organizations '
          f'({sum(not r["bulk"] for r in rows)} not touched by the bulk account)')
    print('class balance:', Counter(r['label'] for r in rows).most_common(4), '...')

    # ---- honest evaluation: hold out a random 10% and every organization the bulk account did not touch
    rng = np.random.RandomState(7)
    clean = [r for r in rows if not r['bulk']]
    rest = [r for r in rows if r['bulk']]
    order = rng.permutation(len(rest))
    cut = int(0.9 * len(rest))
    train = [rest[i] for i in order[:cut]]
    test_random = [rest[i] for i in order[cut:]]
    t0 = time.time()
    m = Model().fit(train)
    print(f'trained on {len(train)} in {time.time() - t0:.0f}s')
    print('unpruned model:')
    report('random holdout', m, test_random)
    report('organizations not touched by bulk', m, clean)
    m.prune(PRUNE_BELOW)
    print(f'after pruning weights < {PRUNE_BELOW}:')
    report('random holdout', m, test_random)
    report('organizations not touched by bulk', m, clean)

    if args.no_final:
        return
    # ---- the shipped model learns from everything
    t0 = time.time()
    final = Model().fit(rows)
    final.prune(PRUNE_BELOW)
    final.quantize()
    print(f'final model trained on all {len(rows)} in {time.time() - t0:.0f}s')
    size = export(final, args.out)
    print(f'exported {args.out}: {size / 1e6:.2f} MB, {int((final.clf.coef_ != 0).sum())} non-zero weights')
    n = export_fixtures(final, rows, args.fixtures)
    print(f'exported {n} fixtures to {args.fixtures}')


if __name__ == '__main__':
    main()
