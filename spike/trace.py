import sys
from collections import defaultdict, deque
from pathlib import Path
import coverage as C

root = Path(sys.argv[1]).resolve(); targets = sys.argv[2:]
kt = [p for p in C.walk(root) if p.suffix in ('.kt','.java')
      and not C.is_test_path(str(p.relative_to(root)))]
parsed, anncls = {}, {}
for p in kt:
    rel = str(p.relative_to(root)); f,s,a = C.parse_kotlin_file(p, rel)
    parsed[rel]=(f,s); anncls.update(a)
pa=set(C.BASE_PREVIEW_ANNOTATIONS)
while True:
    g={n for n,o in anncls.items() if any(x in pa for x in o)}
    if g<=pa: break
    pa|=g
allf=[]
for fs,_ in parsed.values():
    for f in fs: f.is_preview=any(a in pa for a in f.annotations)
    allf+=fs
comp=[f for f in allf if f.is_composable]; by=defaultdict(list)
for f in comp: by[f.name].append(f)
spans=defaultdict(list)
for f in allf: spans[f.file].append(f)
for v in spans.values(): v.sort(key=lambda f:(f.sig_start,-f.end))
E=defaultdict(set)
for rel,(fs,src) in parsed.items():
    for f in fs:
        if not f.is_composable: continue
        b=src[f.sig_start:f.end]
        cs={m.group(1) for m in C.CALL_RE.finditer(b)
            if m.group(1) not in C.KEYWORDS and m.group(1) in by}
        for n in cs:
            for t in by[n]:
                if t.fid!=f.fid: E[f.fid].add(t.fid)
fid={f.fid:f for f in allf}
# BFS from previews recording parent, then report path to the target's owner
prev=[f for f in allf if f.is_preview]; par={f.fid:None for f in prev}
q=deque(par); 
while q:
    c=q.popleft()
    for n in E.get(c,()):
        if n not in par: par[n]=c; q.append(n)
for t in targets:
    import re
    rx=re.compile(r'\b\w*R\.(?:string|plurals)\.'+t+r'\b')
    hits=[]
    for rel,(fs,src) in parsed.items():
        for m in rx.finditer(src):
            owner=None
            for f in spans.get(rel,()):
                if f.sig_start<=m.start()<f.end and (owner is None or f.end-f.sig_start<owner.end-owner.sig_start): owner=f
            hits.append((rel,owner))
    print(f"\n### {t}  ({len(hits)} site(s))")
    for rel,owner in hits[:4]:
        if owner is None: print(f"  {rel.split('/')[-1]}: <no enclosing fn>"); continue
        path=[];cur=owner.fid
        while cur is not None and cur in par: path.append(fid[cur].name); cur=par[cur]
        reach = owner.fid in par
        print(f"  {rel.split('/')[-1]}::{owner.name}  composable={owner.is_composable} reachable={reach}")
        if reach: print(f"     path: {' <- '.join(path)}")
