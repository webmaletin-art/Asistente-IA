# Construye el paquete maestro DOFPA (.dofpa = SQLite gzip) a partir de las páginas extraídas.
# Conserva el TEXTO COMPLETO; agrega estructura (capítulo/sección/artículo), materia, conceptos y relaciones.
# El índice de búsqueda (FTS) lo construye la app con su propia normalización al importar.
import json, os, re, sys, sqlite3, hashlib, gzip, shutil, unicodedata, datetime
from collections import Counter, defaultdict

INV, PAGES_DIR, OUT_DIR = sys.argv[1], sys.argv[2], sys.argv[3]
VERSION = sys.argv[4] if len(sys.argv) > 4 else datetime.date.today().strftime('%Y.%m.%d')

def fold(s):
    s = unicodedata.normalize('NFD', s.lower())
    return ''.join(c for c in s if unicodedata.category(c) != 'Mn')

def norm(s):
    t = fold(s).replace('º', ' ').replace('°', ' ').replace('ª', ' ')
    while re.search(r'(\d)\.(\d{3})(?!\d)', t): t = re.sub(r'(\d)\.(\d{3})(?!\d)', r'\1\2', t)
    t = re.sub(r'[^a-z0-9]+', ' ', t).strip()
    return re.sub(r'\b(n|nro|num|numero)\s+(?=\d)', '', re.sub(' +', ' ', t))

# ------------------------------------------------------------------ metadatos de documento
SUBJECTS = [
    ('Legislación', r'\bley\b|codigo|constitucion|decreto|resolucion|anexo 1 del decreto'),
    ('Actas y redacción', r'^actas/|\bactas?\b|inventario|allanamiento|aprehension|constatacion|exposicion|paradero|contravencion|requisa'),
    ('Conducción segura y seguridad vial', r'conduccion|aquaplaning|frenado|frenos|neumatic|estacionar|maniobra|banquina|aceite|imprudencia|transito|choque|seguridad y fluidez|muertes'),
    ('Salud pública, drogas y toxicología', r'salud|toxicolog|hongos|dosis|drogas|consumo|sociedad de consumo|craneo'),
    ('Narcotráfico y criminología', r'narcotrafico|estructuras criminales|unodc|investigaci|fuero de lucha|politic.? criminal|ministerio publico'),
    ('Procedimientos y actuación policial', r'protocolo|tactica|intervencion|uso de la fuerza|actuacion|estrategias|etica|derechos humanos|principios rectores|disciplinario'),
    ('Armamento y tiro', r'armamento|tiro\b|teoria de tiro'),
    ('Género', r'genero'),
    ('Manual de estudio FPA', r'manual de estudio'),
    ('Institucional FPA', r'induccion|presentacion|trayecto|curso|modelos preventivos|discursos|unidad'),
]

def subject_of(path):
    n = norm(path.replace('/', ' / ')) if not path.startswith('actas/') else 'actas/ ' + norm(path)
    p = path.lower()
    for s, rx in SUBJECTS:
        if re.search(rx, 'actas/' if p.startswith('actas/') and s == 'Actas y redacción' else n):
            return s
    return 'Otros'

def kind_of(path, law):
    n = norm(path)
    if path.startswith('actas/') or re.search(r'\bactas?\b|inventario|orden allanamiento', n): return 'acta'
    if re.search(r'codigo', n): return 'codigo'
    if re.search(r'constitucion', n): return 'constitucion'
    if law or re.search(r'\bley\b', n): return 'ley'
    if re.search(r'decreto|resolucion', n): return 'norma'
    if re.search(r'protocolo', n): return 'protocolo'
    if re.search(r'pptx|presentacion|discursos', n): return 'presentacion'
    if re.search(r'unidad|manual|curso|modulo', n): return 'apunte'
    return 'documento'

def law_of(title, first_text):
    t = norm(title.replace('_', ' ').replace('-', ' '))
    m = re.search(r'\bley\s*(?:pcial|provincial|nacional|nro|numero)?\s*(\d{4,6})\b', t) or re.search(r'\bley\s(\d{2})\s(\d{3})\b', t)
    if m: return ''.join(g for g in m.groups() if g)
    m = re.search(r'\bley\s+(?:nacional\s+|provincial\s+)?(\d{4,6})\b', norm(first_text[:600]))
    if m and re.search(r'\bley\b', t): return m.group(1)
    # Códigos y constituciones: "LEY N° 8123 — CÓDIGO PROCESAL PENAL…" al comienzo del texto
    m = re.search(r'\bley\s+(\d{4,6})\b', norm(first_text[:250]))
    if m and re.search(r'\bcodigo\b', t): return m.group(1)
    return None

def clean_title(path):
    t = os.path.basename(path)
    t = re.sub(r'\.(pdf|docx?|pptx)$', '', t, flags=re.I).replace('.pptx', '').replace('.docx', '')
    t = re.sub(r'[_]+', ' ', t); t = re.sub(r'\s*\(\d\)\s*$', '', t)
    t = re.sub(r'-(?=\w)', ' ', t) if t.count('-') >= 3 else t
    t = re.sub(r'\s+', ' ', t).strip(' .-')
    for a, b in [('Investigaci n', 'Investigación'), ('acci n', 'acción'), ('Antinarcotr fico', 'Antinarcotráfico')]: t = t.replace(a, b)
    t = re.sub(r'^Ley (\d{2})(\d{3}) del .*$', r'Ley \1.\2', t)
    return t

# ------------------------------------------------------------------ limpieza de páginas
def clean_pages(pages):
    """Quita encabezados/pies repetidos y números de página; une guiones de fin de línea."""
    lines_by_page = [[l.rstrip() for l in p['text'].split('\n')] for p in pages]
    n = len(pages)
    freq = Counter()
    for ls in lines_by_page:
        seen = set()
        for l in ls[:3] + ls[-3:]:
            k = re.sub(r'\d+', '#', l.strip())
            if 3 <= len(k) <= 120 and k not in seen: freq[k] += 1; seen.add(k)
    repeated = {k for k, c in freq.items() if n >= 6 and c >= max(3, n * 0.4)}
    out = []
    for p, ls in zip(pages, lines_by_page):
        kept = []
        for l in ls:
            s = l.strip()
            if not s: kept.append(''); continue
            if re.fullmatch(r'(p[aá]g(ina)?\.?\s*)?\d{1,4}(\s*(de|/)\s*\d{1,4})?', s, re.I): continue
            if re.sub(r'\d+', '#', s) in repeated: continue
            kept.append(s)
        txt = '\n'.join(kept)
        txt = re.sub(r'(\w)-\n(\w)', r'\1\2', txt)                       # guion de corte de línea
        txt = re.sub(r'[ \t]+', ' ', txt)
        txt = re.sub(r'\n{3,}', '\n\n', txt).strip()
        out.append(dict(p, text=txt))
    return out

def paragraphs(text):
    """Une líneas cortadas del PDF en párrafos (respeta viñetas, títulos y artículos)."""
    paras, cur = [], []
    for l in text.split('\n'):
        s = l.strip()
        if not s:
            if cur: paras.append(' '.join(cur)); cur = []
            continue
        starts_new = bool(re.match(r'^(\*?\s*(ART[IÍ]CULO|Art[íi]culo|Art\.|ART\.)\s*\d|[•\-–\*·▪●]|\d{1,2}[\.\)]\s|[a-z]\)\s|ART[IÍ]CULO|Art[íi]culo|ARTICULO|CAP[IÍ]TULO|T[IÍ]TULO|UNIDAD|SECCI[OÓ]N|LIBRO)', s))
        if cur and (starts_new or (cur[-1].endswith(('.', ':', ';', '?', '!')) and s[:1].isupper() and len(cur[-1]) < 60)):
            paras.append(' '.join(cur)); cur = []
        cur.append(s)
    if cur: paras.append(' '.join(cur))
    return [p for p in paras if p.strip()]

ART = re.compile(r'^\*?\s*(ART[IÍ]CULO|Art[íi]culo|ARTICULO|Art\.|ART\.)\s*(\d{1,4})\s*(°|º|o\.?)?\s*(bis|ter|quater)?\b', re.I)
# Capítulo/Título/Unidad REAL: palabra con mayúscula + número/romano/ordinal y luego ":", ".", "-", fin o Mayúscula.
# Rechaza referencias en medio de una oración ("Capítulo 4, la Ley…", "sección 2.6, la jurisdicción…").
CHAP = re.compile(r'^(LIBRO|T[IÍ]TULO|CAP[IÍ]TULO|SECCI[OÓ]N|PARTE|UNIDAD|M[OÓ]DULO|TEMA|ANEXO|Libro|T[íi]tulo|Cap[íi]tulo|Secci[oó]n|Parte|Unidad|M[oó]dulo|Tema|Anexo)'
                  r'\s+(N[°º]\s*)?([\dIVXLC]+|PRIMER[OA]?|SEGUND[OA]|TERCER[OA]?|CUART[OA]|QUINT[OA]|[ÚU]NIC[OA]|Primer[oa]?|Segund[oa]|Tercer[oa]?|Cuart[oa]|Quint[oa]|[ÚU]nic[oa])'
                  r'(\s*[:\.\-–]|\s*$|\s+[A-ZÁÉÍÓÚÑ\"«“(])')
NUMHEAD = re.compile(r'^\d{1,2}(\.\d{1,2}){1,3}\.?\s+[A-ZÁÉÍÓÚÑ¿]')
MAX = 1100

def chunk_doc(doc_pages, kind):
    """Devuelve fragmentos (page, chapter, section, text, method)."""
    chunks = []
    chapter, section = None, None
    if kind in ('ley', 'codigo', 'constitucion', 'norma'):
        cur, cur_page, cur_method = [], None, 'native'
        def flush():
            nonlocal cur
            if cur:
                txt = '\n'.join(cur).strip()
                for piece in split_long(txt): chunks.append((cur_page, chapter, section, piece, cur_method))
            cur = []
        for p in doc_pages:
            for para in paragraphs(p['text']):
                if CHAP.match(para) and len(para) < 140 and not ART.match(para):
                    flush(); chapter = para[:140]; section = None; cur_page = p['page']; continue
                m = ART.match(para)
                if m:
                    flush(); section = re.sub(r'\s+', ' ', m.group(0)).strip(' -–:.*'); cur_page = p['page']; cur_method = p['method']
                if cur_page is None: cur_page = p['page']; cur_method = p['method']
                cur.append(para)
        flush()
        return chunks
    for p in doc_pages:
        heads = set(h.strip() for h in p.get('heads', []))
        buf = []
        def flush():
            nonlocal buf
            if buf:
                txt = '\n'.join(buf).strip()
                for piece in split_long(txt): chunks.append((p['page'], chapter, section, piece, p['method']))
            buf = []
        for para in paragraphs(p['text']):
            short = len(para) < 140
            if short and (CHAP.match(para) or (para in heads and para.isupper() and len(para) > 6)):
                flush(); chapter = label(para); section = None
            elif short and not re.search(r'\.{4,}|…', para) and NUMHEAD.match(para) and is_head(para):
                flush(); section = label(para)
            elif short and para in heads and is_head(para):
                flush(); section = label(para)
            # el título también queda en el texto: no se pierde contenido
            if sum(len(x) for x in buf) + len(para) > MAX: flush()
            buf.append(para)
        flush()
    return chunks

def label(s, n=100):
    s = re.sub(r'\s+', ' ', s).strip()
    return s if len(s) <= n else s[:n].rsplit(' ', 1)[0] + '…'

def is_head(s):
    """Un título real: 2+ palabras o una palabra en mayúsculas; no notas al pie ("acarrearía”9.")."""
    words = re.findall(r'[A-Za-zÁÉÍÓÚÑáéíóúñ]{3,}', s)
    if re.search(r'[a-záéíóúñ”"\)]\d{1,3}\.?$', s): return False
    t = s.strip()
    if t[:1].islower() or t.endswith(',') or (t.endswith('.') and len(words) > 7 and not NUMHEAD.match(t)): return False
    return len(words) >= 2 or (len(words) == 1 and s.strip(' .:').isupper() and len(s) > 4)

def split_long(txt):
    if len(txt) <= MAX * 1.6: return [txt]
    out, cur = [], ''
    for s in re.split(r'(?<=[\.;:!?])\s+', txt):
        if len(cur) + len(s) > MAX and cur: out.append(cur.strip()); cur = ''
        cur += (' ' if cur else '') + s
    if cur.strip(): out.append(cur.strip())
    return out

# ------------------------------------------------------------------ conceptos y relaciones
STOP_T = set('el la los las un una unos unas este esta esto eso ello que cual lo se su sus de del al y o en es son para por con como'.split())
DEF_PATTERNS = [
    re.compile(r'(?:se entiende por|se denomina|se define como|se considera|entendemos por)\s+[«"“]?([a-záéíóúñ][\w áéíóúñ\-]{2,45}?)[»"”]?\s*(?:[,:]|\ba\b|\bal\b|\bcomo\b|\bel\b|\bla\b)', re.I),
    re.compile(r'^(?:El|La|Los|Las)?\s*([A-ZÁÉÍÓÚÑ][\w áéíóúñ\-]{2,40}?)\s+(?:es|son)\s+(?:el|la|los|las|un|una|aquel|aquella|aquellos|toda|todo|cualquier)\b'),
    re.compile(r'^([A-ZÁÉÍÓÚÑ][\w áéíóúñ\-]{2,40}?):\s+(?:es|son|se\s|consiste|refiere|comprende)', re.I),
]

def concepts_in(text):
    found = []
    for sent in re.split(r'(?<=[\.;!?])\s+|\n', text):
        s = sent.strip()
        if len(s) < 25 or len(s) > 700: continue
        for rx in DEF_PATTERNS:
            m = rx.search(s)
            if not m: continue
            term = m.group(1).strip(' ,.:;«»"').strip()
            words = term.split()
            if not (1 <= len(words) <= 5) or words[0].lower() in STOP_T or any(c.isdigit() for c in term): continue
            if term.lower() in ('esta ley', 'la presente ley', 'el presente', 'presente', 'ello', 'esto'): continue
            found.append((term, s)); break
    return found

LAWREF = re.compile(r'\b(?:ley(?:es)?|decreto|resolucion)\s+(?:nacional\s+|provincial\s+|pcial\s+|n\s+|nro\s+|numero\s+)?(\d{3,6})\b')

# ------------------------------------------------------------------ armado
inv = json.load(open(INV))
by_sha = {r['sha256']: r for r in inv}
results = []
for f in sorted(os.listdir(PAGES_DIR)):
    if f.endswith('.json'): results.append(json.load(open(os.path.join(PAGES_DIR, f))))
report = dict(version=VERSION, documentos=[], duplicados=[], omitidos=[], errores=[])

# casi-duplicados: mismo texto normalizado
seen_text = {}
docs = []
for r in sorted(results, key=lambda x: x['path']):
    if r['status'] == 'OMITIDO' or not r['pages']:
        report['omitidos'].append(dict(path=r['path'], motivo=r.get('note') or r['status'])); continue
    full = norm(' '.join(p['text'] for p in r['pages']))
    h = hashlib.sha256(full.encode()).hexdigest()
    if h in seen_text:
        report['duplicados'].append(dict(path=r['path'], igual_a=seen_text[h], tipo='mismo texto')); continue
    seen_text[h] = r['path']
    docs.append(r)
for r in inv:
    if r['duplicate_of']: report['duplicados'].append(dict(path=r['path'], igual_a=r['duplicate_of'], tipo='archivo idéntico (hash)'))

os.makedirs(OUT_DIR, exist_ok=True)
dbp = os.path.join(OUT_DIR, 'dofpa_corpus.db')
if os.path.exists(dbp): os.remove(dbp)
con = sqlite3.connect(dbp)
con.executescript('''
CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT);
CREATE TABLE documents(id INTEGER PRIMARY KEY, title TEXT NOT NULL, file_name TEXT, kind TEXT, subject TEXT,
   unit TEXT, law TEXT, pages INTEGER DEFAULT 0, passages INTEGER DEFAULT 0, sha256 TEXT, status TEXT, note TEXT, source TEXT);
CREATE TABLE passages(id INTEGER PRIMARY KEY, doc_id INTEGER NOT NULL, ordinal INTEGER, page INTEGER,
   chapter TEXT, section TEXT, article TEXT, refs TEXT, method TEXT, text TEXT NOT NULL);
CREATE INDEX idx_passages_doc ON passages(doc_id, ordinal);
CREATE INDEX idx_passages_doc_page ON passages(doc_id, page);
CREATE TABLE concepts(id INTEGER PRIMARY KEY, term TEXT, norm TEXT, passage_id INTEGER, doc_id INTEGER, page INTEGER, definition TEXT);
CREATE INDEX idx_concepts_norm ON concepts(norm);
CREATE TABLE relations(a TEXT, b TEXT, kind TEXT, weight INTEGER);
CREATE INDEX idx_relations_a ON relations(a);
''')
pid = 0; concept_rows = []; rel = Counter(); total_chars_src = 0; total_chars_out = 0
for did, r in enumerate(docs, 1):
    path = r['path']; title = clean_title(path)
    pages = clean_pages(r['pages'])
    law = law_of(title, pages[0]['text'] if pages else '')
    kind = kind_of(path, law); subject = subject_of(path)
    unit = None
    m = re.search(r'unidad(?:es)?\s*(?:n\s*)?([\d ,y]+)', norm(title))
    if m: unit = 'Unidad ' + m.group(1).strip()
    chunks = chunk_doc(pages, kind)
    src = sum(len(p['text']) for p in pages); total_chars_src += src
    con.execute('INSERT INTO documents VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)',
                (did, title, os.path.basename(path), kind, subject, unit, law, len(r['pages']), len(chunks), r['sha256'], r['status'], r.get('note') or None, 'paquete'))
    out_chars = 0
    for o, (page, chap, sec, text, method) in enumerate(chunks):
        pid += 1; out_chars += len(text)
        con.execute('INSERT INTO passages(id,doc_id,ordinal,page,chapter,section,method,text) VALUES(?,?,?,?,?,?,?,?)',
                    (pid, did, o, page, chap, sec, method, text))
        for term, sent in concepts_in(text):
            concept_rows.append((term, norm(term), pid, did, page, sent))
        laws = sorted(set(LAWREF.findall(norm(text))) | ({law} if law else set()))
        for i in range(len(laws)):
            for j in range(i + 1, len(laws)):
                rel[('ley:' + laws[i], 'ley:' + laws[j])] += 1
    total_chars_out += out_chars
    cov = out_chars / src if src else 0
    report['documentos'].append(dict(id=did, path=path, titulo=title, tipo=kind, materia=subject, unidad=unit, ley=law,
        paginas=len(r['pages']), fragmentos=len(chunks), caracteres=out_chars, cobertura=round(cov, 3), estado=r['status'], nota=r.get('note') or ''))

# conceptos: el mejor por término (preferir definiciones más largas pero razonables)
best = {}
for term, n, p, d, pg, sent in concept_rows:
    if len(n) < 4: continue
    if n not in best or abs(len(sent) - 220) < abs(len(best[n][5]) - 220): best[n] = (term, n, p, d, pg, sent)
for i, row in enumerate(best.values(), 1): con.execute('INSERT INTO concepts VALUES(?,?,?,?,?,?,?)', (i,) + row)
for (a, b), w in rel.items():
    if w >= 1: con.execute('INSERT INTO relations VALUES(?,?,?,?)', (a, b, 'co-cita', w)); con.execute('INSERT INTO relations VALUES(?,?,?,?)', (b, a, 'co-cita', w))

# hash de contenido (idéntico al de la app: id|doc_id|page|text\n en orden de id)
md = hashlib.sha256()
for row in con.execute('SELECT id, doc_id, page, text FROM passages ORDER BY id'):
    md.update(f'{row[0]}|{row[1]}|{row[2] or 0}|{row[3]}\n'.encode())
meta = dict(format='dofpa-corpus', schema='2', corpus_version=VERSION, created_at=datetime.datetime.now().isoformat(timespec='seconds'),
            documents=str(len(docs)), passages=str(pid), concepts=str(len(best)), content_sha256=md.hexdigest(),
            generator='dofpa build_package.py')
for k, v in meta.items(): con.execute('INSERT INTO meta VALUES(?,?)', (k, v))
con.commit(); con.execute('VACUUM'); con.close()

pkg = os.path.join(OUT_DIR, f'DOFPA_corpus_{VERSION}.dofpa')
with open(dbp, 'rb') as fi, gzip.open(pkg, 'wb', compresslevel=9) as fo: shutil.copyfileobj(fi, fo)
report.update(totales=dict(documentos=len(docs), fragmentos=pid, conceptos=len(best), relaciones=len(rel),
    caracteres_fuente=total_chars_src, caracteres_corpus=total_chars_out,
    db_bytes=os.path.getsize(dbp), paquete_bytes=os.path.getsize(pkg), content_sha256=md.hexdigest()))
json.dump(report, open(os.path.join(OUT_DIR, 'informe_extraccion.json'), 'w'), ensure_ascii=False, indent=1)
print(json.dumps(report['totales'], ensure_ascii=False, indent=1))
print('duplicados:', len(report['duplicados']), 'omitidos:', len(report['omitidos']))
