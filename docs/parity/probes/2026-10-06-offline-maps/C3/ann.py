# SPDX-License-Identifier: GPL-3.0-or-later
import subprocess, sys, re
jar, cls = sys.argv[1], sys.argv[2]
out = subprocess.run(['javap','-v','-p','-cp',jar,cls],capture_output=True,text=True).stdout
lines = out.split('\n')
# class-level annotations near the end
cur=None
res=[]
i=0
in_methods=False
for idx,l in enumerate(lines):
    if l.startswith('{'): in_methods=True
    m = re.match(r'^  (\S.*\));$|^  (\S.*);$', l)
    if in_methods and m and not l.startswith('    '):
        cur=l.strip()
        res.append([cur,[]])
    if ('RuntimeInvisibleAnnotations' in l or 'RuntimeVisibleAnnotations' in l or 'Deprecated: true' in l):
        # gather following annotation lines
        k=idx+1
        anns=[]
        if 'Deprecated: true' in l: anns.append('Deprecated attr')
        while k < len(lines) and lines[k].startswith('        ') :
            t=lines[k].strip()
            mm=re.search(r'//\s*(L[\w/$]+;)', t) or re.search(r'^\d+:\s*#\d+\(\)\s*$', t)
            if '//' in t: anns.append(t.split('//')[-1].strip())
            k+=1
        if cur and res: res[-1][1].extend(anns)
        elif not in_methods: print('CLASS-ANN', anns)
for name,anns in res:
    a=[x for x in anns if 'MainThread' in x or 'Deprecated' in x or 'NonNull' in x or 'Nullable' in x]
    print(name, '  <<', ', '.join(sorted(set(a))) if a else '')
# class-level at end
tail='\n'.join(lines[-30:])
for t in re.findall(r'//\s*(L[\w/$]+;)', tail): print('CLASS-TAIL', t)
