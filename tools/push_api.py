import json,os,subprocess,base64,urllib.request
REPO="tiantong007/io.github.tiantong007.douyinspark"
TOKEN=subprocess.check_output(["gh","auth","token"],text=True).strip()
def api(method,path,body=None):
    url="https://api.github.com"+path
    data=json.dumps(body).encode() if body is not None else None
    req=urllib.request.Request(url,data=data,method=method)
    req.add_header("Authorization","Bearer "+TOKEN)
    req.add_header("Accept","application/vnd.github+json")
    req.add_header("Content-Type","application/json")
    try:
        with urllib.request.urlopen(req) as r: return json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        print("ERR",e.code,e.read().decode()[:800]); raise
def changes(a,b):
    out=subprocess.check_output(["git","diff","--name-status","--no-renames","-z",f"{a}..{b}"])
    parts=out.split(b"\0"); res=[]; i=0
    while i<len(parts)-1:
        st=parts[i].decode()
        if not st.strip(): break
        res.append((st,parts[i+1].decode("utf-8"))); i+=2
    return res
base="71ba0f39c1bd9bb387f3460d4003e68b7affec00"
prev_local=base
prev_tree=api("GET",f"/repos/{REPO}/git/commits/{base}")["tree"]["sha"]
prev_remote=base
for c in subprocess.check_output(["git","rev-list","--reverse",f"{base}..HEAD"],text=True).split():
    ch=changes(prev_local,c)
    print(c[:7],[p for _,p in ch])
    entries=[]
    for st,path in ch:
        if st.startswith("D"): entries.append({"path":path,"mode":"100644","type":"blob","sha":None})
        else:
            blob=api("POST",f"/repos/{REPO}/git/blobs",{"content":base64.b64encode(open(path,"rb").read()).decode(),"encoding":"base64"})
            entries.append({"path":path,"mode":"100644","type":"blob","sha":blob["sha"]})
    tree=api("POST",f"/repos/{REPO}/git/trees",{"base_tree":prev_tree,"tree":entries})
    msg=subprocess.check_output(["git","log","-1","--format=%B",c]).decode()
    commit=api("POST",f"/repos/{REPO}/git/commits",{"message":msg,"tree":tree["sha"],"parents":[prev_remote]})
    print("  ->",commit["sha"])
    prev_local=c; prev_tree=tree["sha"]; prev_remote=commit["sha"]
api("PATCH",f"/repos/{REPO}/git/refs/heads/main",{"sha":prev_remote,"force":False})
print("PATCHED main ->",prev_remote)
