"""Reproducible reference-edit proxies; not a substitute for semantic review.
Raw synthetic outputs remain available beside the summaries. Never estimates missing results.
"""
import argparse
import collections
import difflib
import json
import pathlib
import re
import statistics
import unicodedata


def tokens(text):
    return re.findall(r'\w+|[^\w\s]', unicodedata.normalize('NFC', text))


def edits(source, target):
    a, b = tokens(source), tokens(target)
    return {(i, j, tuple(b[x:y])) for operation, i, j, x, y in difflib.SequenceMatcher(None, a, b, autojunk=False).get_opcodes() if operation != 'equal'}


def median(values):
    return round(statistics.median(values), 2) if values else None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--reports', type=pathlib.Path, required=True)
    parser.add_argument('--output', type=pathlib.Path, required=True)
    args = parser.parse_args()
    summaries = []
    for path in sorted(args.reports.glob('*-quality-*.json')):
        data = json.loads(path.read_text(encoding='utf-8'))
        gold_edits = predicted_edits = matched_edits = unchanged = false_corrections = exact = formatting = literals = 0
        categories = collections.Counter()
        review = []
        samples = [sample for sample in data.get('samples', []) if sample.get('status') == 'complete' and 'case' in sample]
        for sample in samples:
            case = sample['case']; response = sample.get('response', '').strip()
            reference = case['gold']; source = case['input']; categories[case['category']] += 1
            expected = edits(source, reference); actual = edits(source, response)
            gold_edits += len(expected); predicted_edits += len(actual); matched_edits += len(expected & actual)
            same = tokens(response) == tokens(source)
            if case['unchanged']:
                unchanged += 1; false_corrections += not same
            exact += tokens(response) == tokens(reference)
            literal_ok = all(value in response for value in case.get('mustPreserve', []))
            literals += literal_ok
            format_ok = bool(response) and not re.search(r'(?i)^(here\b|sure\b|corrected\b|the corrected\b|proofread\b)|```|<think>|</think>', response)
            format_ok = format_ok and not (response.startswith('"') and not source.startswith('"'))
            formatting += format_ok
            review.append(dict(id=case['id'], category=case['category'], input=source, reference=reference, response=response,
                referenceEdits=len(expected), proposedEdits=len(actual), matchedEdits=len(expected & actual),
                literalsPreserved=literal_ok, formatProxyPassed=format_ok, requiresSemanticReview=True))
        summary = dict(model=data.get('model'), requestedBackend=data.get('requestedBackend'), status=data.get('status'),
            completeCases=len(samples), expectedCases=113, allCasesComplete=len(samples) == 113 and data.get('status') == 'complete',
            referenceEditPrecision=matched_edits / predicted_edits if predicted_edits else 1.0,
            referenceEditRecall=matched_edits / gold_edits if gold_edits else 1.0,
            unchangedCases=unchanged, falseCorrectionsOnUnchanged=false_corrections,
            falseCorrectionRate=false_corrections / unchanged if unchanged else None,
            exactReferenceMatches=exact, formatProxyPasses=formatting, literalInvariantPasses=literals,
            medianFirstTokenMs=median([s['firstTokenMs'] for s in samples if 'firstTokenMs' in s]),
            medianTokensPerSecond=median([s['tokensPerSecond'] for s in samples if s.get('tokensPerSecond', 0) > 0]),
            medianEndToEndMs=median([s['durationMs'] for s in samples if 'durationMs' in s]),
            peakProcessPssKb=data.get('peakProcessPssKb'), categories=dict(categories),
            semanticAccuracy='NOT_AUTOMATICALLY_SCORED; inspect raw outputs and invariants',
            caveat='Reference edits penalize valid alternative wording; do not use this proxy alone to rank models.')
        summaries.append(summary)
        args.output.mkdir(parents=True, exist_ok=True)
        (args.output / (path.stem + '-review.json')).write_text(json.dumps(review, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / 'quality-summary.json').write_text(json.dumps(summaries, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(summaries, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
