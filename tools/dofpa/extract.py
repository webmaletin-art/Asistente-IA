# Extrae texto por página (nativo + OCR si hace falta) con títulos detectados por tipografía.
import json, sys, os, io, re, subprocess, zipfile, statistics
from concurrent.futures import ProcessPoolExecutor
import pymupdf

INV, ROOT, OUT = sys.argv[1], sys.argv[2], sys.argv[3]

def ocr_page(pg):
    pix = pg.get_pixmap(dpi=220)
    png = pix.tobytes('png')
    r = subprocess.run(['tesseract', 'stdin', 'stdout', '-l', 'spa', '--psm', '3'], input=png, capture_output=True)
    return r.stdout.decode('utf-8', 'ignore')

def pdf_pages(path):
    d = pymupdf.open(path)
    sizes = {}
    for pg in d:
        for b in pg.get_text('dict')['blocks']:
            for l in b.get('lines', []):
                for s in l['spans']:
                    k = round(s['size'], 1); sizes[k] = sizes.get(k, 0) + len(s['text'].strip())
    body = max(sizes, key=sizes.get) if sizes else 11
    out = []
    for i, pg in enumerate(d):
        text = pg.get_text('text', sort=True)
        method = 'native'; heads = []
        if len(text.strip()) < 40:
            t2 = ocr_page(pg)
            if len(t2.strip()) > len(text.strip()): text, method = t2, 'ocr'
        else:
            for b in pg.get_text('dict')['blocks']:
                for l in b.get('lines', []):
                    lt = ''.join(s['text'] for s in l['spans']).strip()
                    if not lt or len(lt) > 110: continue
                    mx = max(s['size'] for s in l['spans'])
                    bold = all((s['flags'] & 16) or 'Bold' in s['font'] for s in l['spans'] if s['text'].strip())
                    if mx >= body * 1.18 or (bold and len(lt) < 90 and re.match(r'^[\dA-ZÁÉÍÓÚÑ]', lt)):
                        heads.append(lt)
        out.append(dict(page=i + 1, text=text, heads=heads[:12], method=method))
    d.close()
    return out

def docx_pages(path):
    z = zipfile.ZipFile(path); xml = z.read('word/document.xml').decode('utf-8', 'ignore')
    paras, heads = [], []
    for p in re.findall(r'<w:p[ >].*?</w:p>', xml, re.S):
        t = ''.join(re.findall(r'<w:t[^>]*>(.*?)</w:t>', p, re.S))
        t = t.replace('&amp;', '&').replace('&lt;', '<').replace('&gt;', '>').replace('&quot;', '"').replace('&apos;', "'")
        if re.search(r'w:pStyle w:val="(Heading|Ttulo|Titulo|Title)', p) and t.strip(): heads.append(t.strip())
        paras.append(t)
    return [dict(page=1, text='\n'.join(paras), heads=heads[:20], method='docx')]

def doc_pages(path):
    r = subprocess.run(['antiword', '-w', '0', path], capture_output=True)
    t = r.stdout.decode('utf-8', 'ignore')
    if len(t.strip()) < 20:
        t = subprocess.run(['catdoc', '-d', 'utf-8', path], capture_output=True).stdout.decode('utf-8', 'ignore')
    return [dict(page=1, text=t, heads=[], method='doc')]

def work(r):
    p = os.path.join(ROOT, r['path'])
    try:
        if r['kind'] == 'pdf': pages = pdf_pages(p)
        elif r['kind'] == 'docx': pages = docx_pages(p)
        elif r['kind'] == 'doc': pages = doc_pages(p)
        else: return dict(path=r['path'], status='OMITIDO', note='video: no se extrae texto', pages=[])
        chars = sum(len(x['text'].strip()) for x in pages)
        empty = sum(1 for x in pages if len(x['text'].strip()) < 40)
        ocr = sum(1 for x in pages if x['method'] == 'ocr')
        st = 'OK' if empty == 0 else ('PARCIAL' if empty < len(pages) else 'ERROR')
        note = (f'{ocr} páginas por OCR. ' if ocr else '') + (f'{empty} páginas sin texto legible.' if empty else '')
        return dict(path=r['path'], status=st, note=note.strip(), chars=chars, pages=pages)
    except Exception as e:
        return dict(path=r['path'], status='ERROR', note=f'{type(e).__name__}: {e}', pages=[])

import time
os.makedirs(OUT, exist_ok=True)
def job(r):
    dst = os.path.join(OUT, r['sha256'] + '.json')
    if os.path.exists(dst): return r['path'], 'ya estaba', 0
    t = time.time(); res = work(r); res['sha256'] = r['sha256']
    json.dump(res, open(dst + '.tmp', 'w'), ensure_ascii=False); os.replace(dst + '.tmp', dst)
    return r['path'], f"{res['status']} {res.get('chars',0)} car {res.get('note','')}", time.time() - t
inv = [r for r in json.load(open(INV)) if not r['duplicate_of']]
inv.sort(key=lambda r: -(r['pages'] or 0))   # los grandes primero: mejor reparto entre procesos
from concurrent.futures import as_completed
with ProcessPoolExecutor(max_workers=os.cpu_count()) as ex:
    futs = [ex.submit(job, r) for r in inv]
    for f in as_completed(futs):
        p, msg, dt = f.result()
        print(f"{dt:6.1f}s  {p[:70]}  {msg}", flush=True)
print('FIN', flush=True)
