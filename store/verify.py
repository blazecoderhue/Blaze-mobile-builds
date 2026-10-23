import json,urllib.request,urllib.parse,concurrent.futures as cf,re,time
C=json.load(open('candidates.json'))
OSI={'MIT','Apache-2.0','GPL-2.0','GPL-3.0','AGPL-3.0','LGPL-2.1','LGPL-3.0','MPL-2.0','BSD-2-Clause','BSD-3-Clause','ISC','Unlicense','EUPL-1.2','0BSD','CC0-1.0','WTFPL','Zlib','GPL-2.0-only','GPL-3.0-only','GPL-3.0-or-later','GPL-2.0-or-later','AGPL-3.0-only','AGPL-3.0-or-later','EPL-2.0','Artistic-2.0','BSL-1.0','LGPL-3.0-or-later','LGPL-2.1-or-later','MPL-1.1','OSL-3.0','EUPL-1.1','AFL-3.0','MS-PL','NCSA','UPL-1.0','PostgreSQL','BlueOak-1.0.0','MIT-0'}
def get(u):
  for i in range(3):
    try:
      r=urllib.request.Request(u,headers={'Accept':'application/vnd.github+json','User-Agent':'blaze-store'})
      return json.load(urllib.request.urlopen(r,timeout=30))
    except urllib.error.HTTPError as e:
      if e.code in (404,451): return None
      time.sleep(3)
    except Exception: time.sleep(3)
  return 'ERR'
def gh(c):
  repo=re.sub(r'^https?://github.com/','',c['gh']).strip('/')
  r=get(f'https://api.github.com/repos/{repo}')
  if not isinstance(r,dict): return dict(c,ok=False,why='repo '+str(r))
  lic=(r.get('license') or {}).get('spdx_id')
  out=dict(c,repo=repo,license=lic,stars=r.get('stargazers_count'),archived=r.get('archived'),fork=r.get('fork'),desc=r.get('description'),homepage=r.get('homepage'))
  if lic not in OSI: return dict(out,ok=False,why='license '+str(lic))
  rel=get(f'https://api.github.com/repos/{repo}/releases/latest')
  if not isinstance(rel,dict): return dict(out,ok=False,why='no release')
  apks=[a['name'] for a in rel.get('assets',[]) if a['name'].lower().endswith('.apk')]
  if not apks: return dict(out,ok=False,why='no apk asset')
  return dict(out,ok=True,tag=rel.get('tag_name'),published=rel.get('published_at'),apks=apks[:8])
def gl(c):
  dom=c.get('gld') or 'gitlab.com'; dom=re.sub(r'^https?://','',dom).strip('/')
  p=re.sub(r'^https?://[^/]+/','',c['gl']).strip('/')
  r=get(f'https://{dom}/api/v4/projects/{urllib.parse.quote(p,safe="")}?license=true')
  if not isinstance(r,dict): return dict(c,ok=False,why='gl repo '+str(r))
  lic=((r.get('license') or {}).get('key') or '').upper()
  return dict(c,repo=f'{dom}/{p}',license=lic or None,stars=r.get('star_count'),archived=r.get('archived'),ok=bool(lic),why='' if lic else 'gl license none',gitlab=True)
def one(c):
  try: return gh(c) if c['gh'] else gl(c)
  except Exception as e: return dict(c,ok=False,why='exc '+str(e)[:80])
with cf.ThreadPoolExecutor(6) as ex: res=list(ex.map(one,C))
json.dump(res,open('verified.json','w'),indent=0)
import collections
print(sum(r['ok'] for r in res),'ok of',len(res)); print(collections.Counter(r.get('why','').split(' ')[0]+' '+(r.get('why','').split(' ')[1] if ' ' in r.get('why','') else '') for r in res if not r['ok']).most_common(10))
