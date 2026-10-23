import json,re,collections
V={v.get('repo'):v for v in json.load(open('verified.json'))}
d=json.load(open('catalog-final.json'))
ABI={'arm64-v8a':r'arm64|aarch64|v8a|armv8','armeabi-v7a':r'armeabi|v7a|arm32|armv7|(?<![a-z])arm(?![a-z0-9])','x86_64':r'x86_64|x64|amd64','x86':r'(?<![_a-z])x86(?!_64)'}
AVOID=re.compile(r'debug|perfetto|google.?play|googleplay|-play-|_play|playstore|storefront|huawei|wear|watch|automotive|testkey|premium|nonfoss|patreon|revanced|patched|legacy|offline|lite|old|xr',re.I)
PREF=re.compile(r'universal|fdroid|foss|libre|github|release|standard|full',re.I)
TOK=re.compile(r'arm64-v8a|armeabi-v7a|x86_64|arm64v8|arm32v7|arm64|arm32|armv8a|armv7a|armv8|armv7|aarch64|amd64|v8a|v7a|x86|x64|k9mail|v2ray|i18n|2fa|mp3|mp4|3d|4k|h264|wear\s?os',re.I)
def pat(n):
  toks=[]
  def keep(m): toks.append(m.group(0)); return '\x00%d\x00'%(len(toks)-1)
  m=TOK.sub(keep,n)
  parts=re.split(r'(\d+(?:\.\d+)*|\x00\d+\x00)',m); out=''
  for p in parts:
    if not p: continue
    if p.startswith('\x00'): out+=re.escape(toks[int(p.strip('\x00'))])
    elif re.fullmatch(r'\d+(?:\.\d+)*',p): out+='[0-9][0-9.]*'
    else: out+=re.escape(p)
  return '^'+out+'$'
def isabi(n): return any(re.search(r,n,re.I) for r in ABI.values())
def pick(cands):
  good=[a for a in cands if not AVOID.search(a)] or cands
  good.sort(key=lambda a:(not re.search('universal',a,re.I), not PREF.search(a), len(a)))
  return good[0] if good else None
drop={'web-video-caster'}
apps=[];c=collections.Counter()
for a in d['apps']:
  if a['id'] in drop: c['drop']+=1; continue
  apks=[x for x in V[a['source']['repo']]['apks'] if not re.search(r'\.idsig$',x)]
  uni=[x for x in apks if re.search('universal',x,re.I) or not isabi(x)]
  asset={}; p=pick(uni)
  if p: asset['pattern']=pat(p)
  ab={}
  for k,r in ABI.items():
    m=[x for x in apks if re.search(r,x,re.I) and not re.search('universal',x,re.I)]
    q=pick(m)
    if q: ab[k]=pat(q)
  if ab: asset['abis']=ab
  if 'pattern' not in asset:
    if not ab: c['noasset']+=1; continue
    asset['pattern']=ab.get('arm64-v8a') or next(iter(ab.values()))
  rules=[asset['pattern']]+list(ab.values())
  if not all(any(re.fullmatch(r,n) for n in apks) for r in rules): c['nomatch']+=1; continue
  hits=[sum(1 for n in apks if re.fullmatch(r,n)) for r in ab.values()]
  if ab and len(set(ab.values()))<len(ab): c['abi_collide']+=1
  a['_hits']=hits
  a['asset']=asset; apps.append(a)
[x.pop('_hits',None) for x in apps]; d['apps']=apps; s=json.dumps(d,ensure_ascii=False,separators=(',',':')); open('catalog-final.json','w').write(s)
print(c,len(apps),len(s))
open('APP_LIST.md','w').write('# Blaze Store app list (2026-10-23)\n\n| # | App | License | Source repo | Stars |\n|---|---|---|---|---|\n'+''.join(f"| {i+1} | {x['name']} | {x['license']} | {x['repo_url']} | {x['stars']} |\n" for i,x in enumerate(apps)))
for x in apps[:6]: print(x['id'],x['asset'])
