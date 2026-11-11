import os, sys, hashlib, json, subprocess
root = sys.argv[1]; out = sys.argv[2]
rows = []
for dp, dn, fn in os.walk(root):
    for f in sorted(fn):
        p = os.path.join(dp, f); rel = os.path.relpath(p, root)
        b = open(p, 'rb').read()
        magic = subprocess.run(['file', '-b', p], capture_output=True, text=True).stdout.strip()
        kind = ('pdf' if magic.startswith('PDF') else 'mp4' if 'MP4' in magic or 'ISO Media' in magic else
                'doc' if 'Composite Document' in magic else 'docx' if 'Word 2007' in magic else
                'pptx' if 'PowerPoint 2007' in magic else 'otro')
        pages = None
        if kind == 'pdf':
            try:
                import pymupdf; d = pymupdf.open(p); pages = d.page_count; d.close()
            except Exception as e: pages = -1
        rows.append(dict(path=rel, size=len(b), sha256=hashlib.sha256(b).hexdigest(), kind=kind, magic=magic[:90], pages=pages))
by = {}
for r in rows: by.setdefault(r['sha256'], []).append(r['path'])
for r in rows:
    same = [x for x in by[r['sha256']] if x != r['path']]
    r['duplicate_of'] = same[0] if same and sorted(by[r['sha256']])[0] != r['path'] else None
json.dump(rows, open(out, 'w'), ensure_ascii=False, indent=1)
from collections import Counter
c = Counter(r['kind'] for r in rows)
print('total', len(rows), dict(c))
print('duplicados exactos:', [(r['path'], '=', r['duplicate_of']) for r in rows if r['duplicate_of']])
print('paginas PDF:', sum(r['pages'] or 0 for r in rows if r['kind']=='pdf'))
print('otros tipos:', [(r['path'], r['magic']) for r in rows if r['kind']=='otro'])
