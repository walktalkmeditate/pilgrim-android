#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Derives the Thought Threads NLP substrate assets from WordNet 3.1 + VADER.

Downloads two third-party corpora, verifies each against a checksum pinned
below, and derives small gzip'd flat files into
``app/src/main/assets/threads/`` for the Android-original NLP substrate
(``WordNetLexicon``, ``TranscriptNlp``, ``VaderSentiment`` in
``core/threads/``). Nothing this script downloads is committed; only its
derived output is.

Run from anywhere (paths resolve relative to this file):

    python3 tools/threads/derive_nlp_assets.py

Stdlib only, no pip installs required.

--------------------------------------------------------------------------
PROVENANCE — read this before touching the pinned hashes below
--------------------------------------------------------------------------

WordNet 3.1 dict tarball
    URL: https://wordnetcode.princeton.edu/wn3.1.dict.tar.gz
    sha256 pinned below.
    Independently corroborated: Homebrew-core's own ``wordnet.rb`` formula
    (a separately maintained, widely audited project with no relationship
    to this script or to Princeton's own site) pins the IDENTICAL sha256
    for this exact URL:
        https://github.com/Homebrew/homebrew-core/blob/master/Formula/w/wordnet.rb
          resource "dict" do
            url "https://wordnetcode.princeton.edu/wn3.1.dict.tar.gz"
            sha256 "3f7d8be8ef6ecc7167d39b10d66954ec734280b5bdcd57f7d9eafe429d11c22a"
          end
    Cross-checked 2026-08-25; matches this script's WORDNET_SHA256 exactly.

VADER sentiment lexicon + license
    Repo: https://github.com/cjhutto/vaderSentiment (MIT)
    Pinned to commit VADER_COMMIT below (the last commit that touched
    ``vaderSentiment/vader_lexicon.txt``, 2026-05-22 at the time of
    writing) rather than a mutable branch ref, so re-running this script
    later reproduces the same bytes.
    Independently corroborated via GitHub's Git-object API — a different
    subsystem from the raw-content CDN this script downloads from: the
    pinned commit's tree lists ``vaderSentiment/vader_lexicon.txt`` at git
    blob sha ``6059e7ee6c2ad9f8acdd534c6bd09eb6d9bfcc3b`` and ``LICENSE.txt``
    at ``62a909ca0ff8aebdf09d50e590587dca94f1d152``; both match
    ``git hash-object`` of the downloaded bytes exactly, and the commit is
    GPG-signed by the lexicon's author (C.J. Hutto) and reported
    "verified" by GitHub's API. Cross-checked 2026-08-25 via:
        curl -s https://api.github.com/repos/cjhutto/vaderSentiment/git/commits/<VADER_COMMIT>
        curl -s https://api.github.com/repos/cjhutto/vaderSentiment/git/trees/<its-tree-sha>?recursive=1
    (PyPI's published ``vaderSentiment`` sdist was also checked as a
    candidate second source but its bundled lexicon predates this commit
    and differs — it was NOT used as corroboration for that reason.)

If either upstream ever rotates its content at these pinned addresses,
this script's checksum verification will fail loudly rather than silently
ingest different data.

--------------------------------------------------------------------------
EXCLUSION RULES — why the derived asset counts are smaller than the source
--------------------------------------------------------------------------

1. Single-token reachability (noun/verb/adjective lemma sets + the synset
   map). ``TranscriptNlp.wordTokens()`` only ever produces lowercase runs
   of `a`-`z` (see core/threads/TranscriptNlp.kt) — never multi-word
   phrases, hyphenated compounds, digits, or apostrophes. A WordNet index
   entry that isn't itself a pure `[a-z]+` string (e.g. "physical_entity",
   "1000000", "'hood") can therefore never be produced as a lookup key by
   our own tokenizer, in any language, ever. Keeping such entries would be
   dead weight, not a behavior difference, so they are dropped before
   anything else.

2. Abbreviation/initialism-only NOUN entries (decision 1 of the U3 task
   brief) — practically:
     a. Any surviving (post-#1) noun lemma of length <= 2 characters is
        dropped outright (e.g. "wa", the two-letter Washington-state
        abbreviation — this is also what keeps "was" from morphologically
        resolving to a noun via the "s"-stripping suffix rule; see
        WordNetLexiconTest).
     b. Any surviving noun lemma of length >= 3 is dropped if EVERY
        synset word-form WordNet lists for it (checked against data.noun,
        which preserves original case — index.noun does not) is entirely
        uppercase letters, e.g. "NASA", "FBI", "CIA", "NATO". A lemma with
        even one non-all-caps attested form (a genuine common-noun sense)
        is kept.
   Only NOUNS get this treatment — verbs/adjectives are not
   abbreviation-prone the same way, and a blind length-based cut there
   would wrongly exclude legitimate short verbs ("be", "do", "go").

3. Singleton synsets in the lemma -> synset-offsets map. `related()` only
   ever asks "do lemma A and lemma B share a synset" — a synset that only
   ONE surviving lemma points to can never be that shared link between two
   DIFFERENT words, so it is pruned from the map. This is lossless for
   every consumer this map has (see the U3 spec / TranscriptNlp.related).

--------------------------------------------------------------------------
OFFSET ENCODING — why synsets.txt.gz stores biased integers
--------------------------------------------------------------------------

WordNet's noun and verb synset offsets are two SEPARATE numbering spaces
(each file's offsets are only unique within that one file) — 124 numeric
values collide between data.noun and data.verb in WordNet 3.1 despite
referring to entirely unrelated synsets. `WordNetLexicon.synsets()` returns
a flat `IntArray` with no accompanying POS tag, so noun offsets are
shipped unbiased and verb offsets are shipped with VERB_OFFSET_BIAS added,
keeping the two spaces disjoint. The Kotlin runtime must never re-derive
this bias independently; it only ever compares encoded values it read back
from this same asset.
"""

from __future__ import annotations

import gzip
import hashlib
import json
import re
import sys
import tarfile
import tempfile
import urllib.request
from pathlib import Path
from typing import Dict, Iterable, List, Set, Tuple

# --- Pinned sources -------------------------------------------------------

WORDNET_URL = "https://wordnetcode.princeton.edu/wn3.1.dict.tar.gz"
WORDNET_SHA256 = "3f7d8be8ef6ecc7167d39b10d66954ec734280b5bdcd57f7d9eafe429d11c22a"

VADER_COMMIT = "0b8040fd23e0ba0a68ebf043697f087cd4c4d6c6"
VADER_LEXICON_URL = (
    f"https://raw.githubusercontent.com/cjhutto/vaderSentiment/{VADER_COMMIT}/"
    "vaderSentiment/vader_lexicon.txt"
)
VADER_LEXICON_SHA256 = "ec636f884f36b5de6a4d681b2f61c08c2ec90eeb440472033f3307d0d6fa8bc9"
VADER_LICENSE_URL = (
    f"https://raw.githubusercontent.com/cjhutto/vaderSentiment/{VADER_COMMIT}/LICENSE.txt"
)
VADER_LICENSE_SHA256 = "b78925304c567623f14677bad66b8e305e7ddcaf7982c8d8411ce51c55a8bf51"

# Every noun offset in WordNet 3.1 is well under 16,000,000 and every verb
# offset under 3,000,000 (checked against the pinned archive below at
# derivation time) — 20,000,000 leaves a wide, documented margin so the
# two encoded ranges never touch. Mirrored verbatim as
# `WordNetLexicon.VERB_OFFSET_BIAS` in Kotlin; the two must always agree.
VERB_OFFSET_BIAS = 20_000_000

# Princeton's morphy suffix-detachment table (noun/verb/adjective). This is
# a fixed property of the algorithm itself, not data pulled from the
# downloaded archive — reproduced here, once, as the single source that
# both the derived asset and this docstring agree with.
MORPHOLOGY_RULES: Dict[str, List[Tuple[str, str]]] = {
    "noun": [
        ("s", ""), ("ses", "s"), ("ves", "f"), ("xes", "x"), ("zes", "z"),
        ("ches", "ch"), ("shes", "sh"), ("men", "man"), ("ies", "y"),
    ],
    "verb": [
        ("s", ""), ("ies", "y"), ("es", "e"), ("es", ""),
        ("ed", "e"), ("ed", ""), ("ing", "e"), ("ing", ""),
    ],
    "adjective": [
        ("er", ""), ("est", ""), ("er", "e"), ("est", "e"),
    ],
}

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
ASSETS_DIR = REPO_ROOT / "app" / "src" / "main" / "assets" / "threads"

PURE_LOWERCASE_WORD = re.compile(r"^[a-z]+$")


# --- Download + verify -----------------------------------------------------

def _download(url: str, expected_sha256: str) -> bytes:
    print(f"downloading {url}", file=sys.stderr)
    with urllib.request.urlopen(url, timeout=60) as response:
        data = response.read()
    actual = hashlib.sha256(data).hexdigest()
    if actual != expected_sha256:
        raise SystemExit(
            f"checksum mismatch for {url}\n"
            f"  expected: {expected_sha256}\n"
            f"  actual:   {actual}\n"
            "Refusing to derive assets from unverified data. See this "
            "script's module docstring for provenance of the pinned hash."
        )
    print(f"  sha256 verified ({len(data):,} bytes)", file=sys.stderr)
    return data


# --- WordNet dict.* parsing -------------------------------------------------

def _skip_header(lines: Iterable[str]) -> Iterable[str]:
    """WordNet's index.*/data.* files open with a license header whose
    lines are indented two spaces; every real record starts in column 0."""
    for line in lines:
        if line.startswith("  ") or not line.strip():
            continue
        yield line


def parse_index(text: str) -> Dict[str, List[str]]:
    """lemma -> synset offsets, in the file's own (frequency) order.

    Format (wninput(5WN)): lemma pos synset_cnt p_cnt [ptr_symbol]*p_cnt
    sense_cnt tagsense_cnt [synset_offset]*synset_cnt.
    """
    result: Dict[str, List[str]] = {}
    for line in _skip_header(text.splitlines()):
        parts = line.split()
        lemma = parts[0]
        synset_cnt = int(parts[2])
        p_cnt = int(parts[3])
        offsets_start = 4 + p_cnt + 2
        result[lemma] = parts[offsets_start:offsets_start + synset_cnt]
    return result


def parse_data_words(text: str) -> Dict[str, List[str]]:
    """synset offset -> case-preserved word forms it contains.

    Only what's needed for the abbreviation-only-noun check (rule 2 in the
    module docstring); pointers/frames/glosses are not parsed. Format
    (wndb(5WN)): synset_offset lex_filenum ss_type w_cnt
    [word lex_id]*w_cnt p_cnt ... — w_cnt is TWO HEX DIGITS.
    """
    result: Dict[str, List[str]] = {}
    for line in _skip_header(text.splitlines()):
        parts = line.split()
        offset = parts[0]
        w_cnt = int(parts[3], 16)
        words = [parts[4 + 2 * i] for i in range(w_cnt)]
        result[offset] = words
    return result


def parse_exceptions(text: str) -> Dict[str, List[str]]:
    """inflected form -> base form(s), in the file's own order.

    A handful of WordNet 3.1 .exc files repeat the same inflected form on
    two separate lines with different base forms (e.g. noun.exc's
    "aurar" -> "eyir" on one line, "eyrir" on another) — every base form
    across every occurrence is kept, in file order, deduplicated, rather
    than letting a later line silently overwrite an earlier one.
    """
    result: Dict[str, List[str]] = {}
    for line in text.splitlines():
        parts = line.split()
        if not parts:
            continue
        bases = result.setdefault(parts[0], [])
        for base in parts[1:]:
            if base not in bases:
                bases.append(base)
    return result


# --- Filters (see module docstring for the rationale behind each) ----------

def filter_reachable(offsets_by_lemma: Dict[str, List[str]]) -> Dict[str, List[str]]:
    return {
        lemma: offsets
        for lemma, offsets in offsets_by_lemma.items()
        if PURE_LOWERCASE_WORD.match(lemma)
    }


def _is_all_caps_alpha(word: str) -> bool:
    letters = [c for c in word if c.isalpha()]
    return bool(letters) and all(c.isupper() for c in letters)


def compute_abbreviation_only_nouns(
    noun_offsets: Dict[str, List[str]],
    data_noun_words: Dict[str, List[str]],
) -> Set[str]:
    """Noun lemmas to drop per exclusion rule 2. `noun_offsets` must
    already be reachability-filtered (rule 1), so every lemma here is a
    plain lowercase a-z string with no underscores to normalize."""
    excluded: Set[str] = set()
    for lemma, offsets in noun_offsets.items():
        if len(lemma) <= 2:
            excluded.add(lemma)
            continue
        raw_forms = [
            raw
            for offset in offsets
            for raw in data_noun_words.get(offset, [])
            if raw.lower() == lemma
        ]
        if raw_forms and all(_is_all_caps_alpha(w) for w in raw_forms):
            excluded.add(lemma)
    return excluded


def build_synset_map(
    noun_offsets: Dict[str, List[str]],
    verb_offsets: Dict[str, List[str]],
) -> Dict[str, List[int]]:
    """lemma -> encoded synset offsets (noun unbiased, verb + BIAS),
    pruned to synsets with >= 2 surviving lemmas (exclusion rule 3)."""
    max_noun = max((int(o) for offsets in noun_offsets.values() for o in offsets), default=0)
    max_verb = max((int(o) for offsets in verb_offsets.values() for o in offsets), default=0)
    assert max_noun < VERB_OFFSET_BIAS, (
        f"noun offset {max_noun} would collide with the verb bias "
        f"({VERB_OFFSET_BIAS}) — raise VERB_OFFSET_BIAS in both this "
        "script and WordNetLexicon.kt before shipping"
    )
    assert max_verb < VERB_OFFSET_BIAS, (
        f"verb offset {max_verb} exceeds VERB_OFFSET_BIAS "
        f"({VERB_OFFSET_BIAS}) — raise the bias in both this script and "
        "WordNetLexicon.kt before shipping"
    )

    lemma_to_encoded: Dict[str, List[int]] = {}
    synset_lemmas: Dict[int, Set[str]] = {}
    for lemma, offsets in noun_offsets.items():
        encoded = [int(o) for o in offsets]
        lemma_to_encoded[lemma] = encoded
        for e in encoded:
            synset_lemmas.setdefault(e, set()).add(lemma)
    for lemma, offsets in verb_offsets.items():
        encoded = [int(o) + VERB_OFFSET_BIAS for o in offsets]
        lemma_to_encoded.setdefault(lemma, []).extend(encoded)
        for e in encoded:
            synset_lemmas.setdefault(e, set()).add(lemma)

    result: Dict[str, List[int]] = {}
    for lemma, encoded in lemma_to_encoded.items():
        kept = sorted({e for e in encoded if len(synset_lemmas[e]) >= 2})
        if kept:
            result[lemma] = kept
    return result


def parse_vader_lexicon(text: str) -> Dict[str, float]:
    """token -> mean sentiment value only. Canonical VADER's own scoring
    code (`make_lex_dict`) reads just columns 0-1 of each line, ignoring
    the stddev (col 2) and raw per-annotator scores (col 3) entirely, so
    dropping those columns changes nothing about what gets scored — it
    only shrinks the shipped file (~76% smaller). Entries that aren't a
    pure a-z token (emoticons, punctuation) are also dropped: our
    tokenizer's output can never contain one, so they could never be
    looked up (mirrors exclusion rule 1)."""
    result: Dict[str, float] = {}
    for line in text.splitlines():
        if not line.strip():
            continue
        fields = line.split("\t")
        token, value = fields[0], fields[1]
        lower = token.lower()
        if PURE_LOWERCASE_WORD.match(lower):
            result[lower] = float(value)
    return result


# --- Output ------------------------------------------------------------

def _write_gzip(path: Path, data: bytes) -> Tuple[int, str]:
    """Writes deterministic gzip bytes (fixed mtime=0, no embedded
    filename) so re-running this script against unchanged input
    reproduces byte-identical output — otherwise every regeneration would
    churn the sha256 in manifest.json for no content reason."""
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "wb") as raw:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0) as gz:
            gz.write(data)
    written = path.read_bytes()
    return len(written), hashlib.sha256(written).hexdigest()


def _write_lines_gz(path: Path, lines: List[str]) -> Tuple[int, int, str]:
    body = ("\n".join(lines) + "\n") if lines else ""
    size, digest = _write_gzip(path, body.encode("utf-8"))
    return len(lines), size, digest


def main() -> None:
    with tempfile.TemporaryDirectory(prefix="threads-nlp-derive-") as tmp:
        tmp_path = Path(tmp)

        wordnet_bytes = _download(WORDNET_URL, WORDNET_SHA256)
        archive_path = tmp_path / "wn3.1.dict.tar.gz"
        archive_path.write_bytes(wordnet_bytes)
        with tarfile.open(archive_path) as tar:
            tar.extractall(tmp_path, filter="data")
        dict_dir = tmp_path / "dict"

        vader_lexicon_text = _download(VADER_LEXICON_URL, VADER_LEXICON_SHA256).decode("utf-8")
        vader_license_bytes = _download(VADER_LICENSE_URL, VADER_LICENSE_SHA256)

        noun_offsets = filter_reachable(parse_index((dict_dir / "index.noun").read_text()))
        verb_offsets = filter_reachable(parse_index((dict_dir / "index.verb").read_text()))
        adj_offsets = filter_reachable(parse_index((dict_dir / "index.adj").read_text()))

        data_noun_words = parse_data_words((dict_dir / "data.noun").read_text())
        abbreviation_nouns = compute_abbreviation_only_nouns(noun_offsets, data_noun_words)
        noun_offsets = {
            lemma: offsets
            for lemma, offsets in noun_offsets.items()
            if lemma not in abbreviation_nouns
        }

        synset_map = build_synset_map(noun_offsets, verb_offsets)

        noun_exceptions = parse_exceptions((dict_dir / "noun.exc").read_text())
        verb_exceptions = parse_exceptions((dict_dir / "verb.exc").read_text())
        adj_exceptions = parse_exceptions((dict_dir / "adj.exc").read_text())

        vader_lexicon = parse_vader_lexicon(vader_lexicon_text)

        manifest: Dict[str, object] = {
            "sources": {
                "wordnet": {"url": WORDNET_URL, "sha256": WORDNET_SHA256, "version": "3.1"},
                "vader": {
                    "url": VADER_LEXICON_URL,
                    "commit": VADER_COMMIT,
                    "sha256": VADER_LEXICON_SHA256,
                },
            },
            "assets": {},
        }
        assets: Dict[str, object] = manifest["assets"]  # type: ignore[assignment]

        def emit_lines(name: str, lines: List[str]) -> None:
            entries, size, digest = _write_lines_gz(ASSETS_DIR / name, lines)
            assets[name] = {"entries": entries, "bytes": size, "sha256": digest}
            print(f"  {name}: {entries:,} entries, {size:,} bytes gzip'd", file=sys.stderr)

        emit_lines("nouns.txt.gz", sorted(noun_offsets))
        emit_lines("verbs.txt.gz", sorted(verb_offsets))
        emit_lines("adjectives.txt.gz", sorted(adj_offsets))

        emit_lines(
            "noun-exceptions.txt.gz",
            [f"{k}\t{','.join(v)}" for k, v in sorted(noun_exceptions.items())],
        )
        emit_lines(
            "verb-exceptions.txt.gz",
            [f"{k}\t{','.join(v)}" for k, v in sorted(verb_exceptions.items())],
        )
        emit_lines(
            "adjective-exceptions.txt.gz",
            [f"{k}\t{','.join(v)}" for k, v in sorted(adj_exceptions.items())],
        )

        emit_lines(
            "synsets.txt.gz",
            [f"{lemma}\t{','.join(str(o) for o in offsets)}" for lemma, offsets in sorted(synset_map.items())],
        )

        emit_lines(
            "vader-lexicon.txt.gz",
            [f"{token}\t{value}" for token, value in sorted(vader_lexicon.items())],
        )

        rule_count = sum(len(v) for v in MORPHOLOGY_RULES.values())
        morphology_json = json.dumps(MORPHOLOGY_RULES, sort_keys=True, separators=(",", ":"))
        size, digest = _write_gzip(ASSETS_DIR / "morphology-rules.json.gz", morphology_json.encode("utf-8"))
        assets["morphology-rules.json.gz"] = {"entries": rule_count, "bytes": size, "sha256": digest}
        print(f"  morphology-rules.json.gz: {rule_count} entries, {size:,} bytes gzip'd", file=sys.stderr)

        vader_license_path = ASSETS_DIR / "vader-license.txt"
        vader_license_path.write_bytes(vader_license_bytes)
        assets["vader-license.txt"] = {
            "bytes": len(vader_license_bytes),
            "sha256": hashlib.sha256(vader_license_bytes).hexdigest(),
        }

        manifest_path = ASSETS_DIR / "manifest.json"
        manifest_path.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")

        total_gz_bytes = sum(
            v["bytes"] for v in assets.values() if isinstance(v, dict) and "bytes" in v
        )
        print(f"\ntotal derived asset size: {total_gz_bytes:,} bytes ({total_gz_bytes / 1_048_576:.2f} MiB)", file=sys.stderr)
        print(f"wrote manifest: {manifest_path}", file=sys.stderr)


if __name__ == "__main__":
    main()
