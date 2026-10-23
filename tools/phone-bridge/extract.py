import re,sys,json
src=open('app/src/main/java/com/blaze/assistant/browser/phone/PhoneScripts.kt').read()
common=re.search(r'private const val COMMON = """(.*?)"""',src,re.S).group(1)
def raw(name):
    m=re.search(r'(?:const val|fun) '+name+r'\b[^\n]*?= ?"""(.*?)"""',src,re.S); return m.group(1)
def sub(s, env):
    s=s.replace('$COMMON',common).replace('$BRIDGE_WM','blazePhoneBridge').replace('$BRIDGE_JI','BlazePhoneBridge')
    s=re.sub(r'\$\{JSONObject\.quote\((\w+)\)\}', lambda m: json.dumps(env[m.group(1)]), s)
    s=re.sub(r'\$(\w+)', lambda m: json.dumps(env[m.group(1)]) if m.group(1) in env else m.group(0), s)
    return s
out={}
out['snapshot']=sub(raw('SNAPSHOT'),{})
out['bridge']=sub(raw('bridge'),{'token':'TOK'})
out['locate']=sub(raw('locate'),{'text':'login','field':False})
out['rect']=sub(raw('rect'),{'id':'e1'})
out['find']=sub(raw('find'),{'text':'login','field':False})
out['focus']=sub(raw('FOCUS'),{})
out['type']=sub(raw('type'),{'id':'e1','value':'abc','secret':False,'clear':True})
out['select']=sub(raw('select'),{'id':'e1','option':'x'})
json.dump(out,open('tools/phone-bridge/out/scripts.json','w'))
for k,v in out.items():
    open(f'tools/phone-bridge/out/{k}.js','w').write(v+';\n')
print('ok', {k:len(v) for k,v in out.items()})
