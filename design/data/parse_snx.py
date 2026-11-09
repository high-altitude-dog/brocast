import json, sys, re, collections
f = sys.argv[1]
blocks = collections.defaultdict(list); cur = None
for line in open(f, encoding="latin-1"):
    if line.startswith("+"): cur = line[1:].strip(); continue
    if line.startswith("-"): cur = None; continue
    if cur and line.startswith(" "): blocks[cur].append(line.rstrip("\n"))
OPEN = "0000:000:00000"
ident = {}
for l in blocks["SATELLITE/IDENTIFIER"]:
    p = l.split(None, 4)
    svn, cospar, satcat, block = p[0], p[1], p[2], p[3]
    com = p[4] if len(p) > 4 else ""
    m = re.search(r"Launched (\d{4}-\d\d-\d\d)", com)
    name = com.split(";")[1].strip() if ";" in com else ""
    ident[svn] = dict(svn=svn, cospar=cospar, norad=int(satcat), block=block, launched=m.group(1) if m else None, name=name)
def cur_rows(b):
    out = {}
    for l in blocks[b]:
        p = l.split()
        if p[2] == OPEN: out.setdefault(p[0], []).append(p)
    return out
prn = {}
for svn, rows in cur_rows("SATELLITE/PRN").items():
    for r in rows: prn[r[3]] = svn
mass = {svn: float(r[-1][3]) for svn, r in cur_rows("SATELLITE/MASS").items()}
plane = {}
for svn, rows in cur_rows("SATELLITE/PLANE").items():
    r = rows[-1]; plane[svn] = (r[3], r[4] if len(r) > 4 else "")
chan = {svn: int(r[-1][3]) for svn, r in cur_rows("SATELLITE/FREQUENCY_CHANNEL").items()}
SYS = {"G": ("GPS", "US", "United States", "US Space Force"), "R": ("GLONASS", "RU", "Russia", "Roscosmos"),
       "E": ("Galileo", "EU", "European Union", "EU Space Programme (EUSPA)"), "C": ("BeiDou", "CN", "China", "China Satellite Navigation Office"),
       "J": ("QZSS", "JP", "Japan", "Cabinet Office of Japan"), "I": ("NavIC", "IN", "India", "ISRO")}
table = {}
for p_, svn in sorted(prn.items()):
    d = dict(ident.get(svn, {"svn": svn}))
    s = SYS[p_[0]]
    d.update(prn=p_, system=s[0], country_code=s[1], country=s[2], operator=s[3], mass_kg=mass.get(svn),
             plane=plane.get(svn, (None, None))[0], slot=plane.get(svn, (None, None))[1], glonass_channel=chan.get(svn))
    table[p_] = d
json.dump(table, open(sys.argv[2], "w"), indent=0)
cnt = collections.Counter(v["system"] for v in table.values())
print(cnt, "total", len(table))
for k in ("G12", "G05", "R07", "E24", "C21", "C32", "E30", "G29"):
    print(k, table.get(k))
missing = [k for k, v in table.items() if not v.get("mass_kg")]
print("no mass:", missing)
