import json,subprocess,base64,urllib.request,urllib.error
REPO="tiantong007/io.github.tiantong007.douyinspark"
TOKEN=subprocess.check_output(["gh","auth","token"],text=True,encoding="utf-8").strip()
def api(method,path,body=None):
    data=json.dumps(body).encode() if body is not None else None
    req=urllib.request.Request("https://api.github.com"+path,data=data,method=method)
    req.add_header("Authorization","Bearer "+TOKEN)
    req.add_header("Accept","application/vnd.github+json")
    req.add_header("Content-Type","application/json")
    try:
        with urllib.request.urlopen(req) as r: return json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        print("ERR",e.code,e.read().decode()[:800]); raise

local_base=subprocess.check_output(["git","rev-parse","HEAD~1"],text=True,encoding="utf-8").strip()
remote=api("GET",f"/repos/{REPO}/git/ref/heads/main")["object"]["sha"]
remote_tree=api("GET",f"/repos/{REPO}/git/commits/{remote}")["tree"]["sha"]
local_tree=subprocess.check_output(["git","rev-parse",local_base+"^{tree}"],text=True,encoding="utf-8").strip()
assert remote_tree==local_tree, (remote_tree,local_tree)
print("remote base",remote[:7],"tree ok")

out=subprocess.check_output(["git","diff","--name-status","--no-renames","-z",f"{local_base}..HEAD"])
parts=out.split(b"\0"); entries=[]; i=0
while i<len(parts)-1:
    st=parts[i].decode()
    if not st.strip(): break
    path=parts[i+1].decode("utf-8"); i+=2
    if st.startswith("D"):
        entries.append({"path":path,"mode":"100644","type":"blob","sha":None})
    else:
        blob=api("POST",f"/repos/{REPO}/git/blobs",{"content":base64.b64encode(open(path,"rb").read()).decode(),"encoding":"base64"})
        entries.append({"path":path,"mode":"100644","type":"blob","sha":blob["sha"]})
    print(" ",st,path)
tree=api("POST",f"/repos/{REPO}/git/trees",{"base_tree":remote_tree,"tree":entries})
msg=subprocess.check_output(["git","log","-1","--format=%B"],text=True,encoding="utf-8")
commit=api("POST",f"/repos/{REPO}/git/commits",{"message":msg,"tree":tree["sha"],"parents":[remote]})
print("new commit",commit["sha"])
api("PATCH",f"/repos/{REPO}/git/refs/heads/main",{"sha":commit["sha"],"force":False})
print("PATCHED main ->",commit["sha"])
