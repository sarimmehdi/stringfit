import sys, json
from collections import defaultdict, Counter
from pathlib import Path
import coverage as C

root = Path(sys.argv[1]).resolve()
kt = [p for p in C.walk(root) if p.suffix in ('.kt','.java')
      and not C.is_test_path(str(p.relative_to(root)))]
parsed, anncls = {}, {}
for p in kt:
    rel = str(p.relative_to(root))
    f, s, a = C.parse_kotlin_file(p, rel); parsed[rel]=(f,s); anncls.update(a)

comp = [f for fs,_ in parsed.values() for f in fs if f.is_composable]
names = Counter(f.name for f in comp)
amb = {n:c for n,c in names.items() if c > 1}
print(f"composables={len(comp)} distinct_names={len(names)} "
      f"ambiguous_names={len(amb)} ({100*len(amb)/max(1,len(names)):.1f}%) "
      f"fns_with_ambiguous_name={sum(amb.values())}")
print("top ambiguous:", sorted(amb.items(), key=lambda x:-x[1])[:8])

# unattributed reference sites: what do they look like?
spans=defaultdict(list)
for fs,_ in parsed.values():
    for f in fs: spans[f.file].append(f)
for v in spans.values(): v.sort(key=lambda f:(f.start,-f.end))
shown=0
for rel,(fs,src) in parsed.items():
    for rx in C.STR_REF_RES.values():
        for m in rx.finditer(src):
            if any(f.start <= m.start() < f.end for f in spans.get(rel,())): continue
            if shown < 6:
                ls = src.rfind('\n',0,m.start())+1
                print(f"  TOPLEVEL {rel.split('/')[-1]}: {src[ls:src.find(chr(10),m.start())].strip()[:100]}")
                shown+=1
