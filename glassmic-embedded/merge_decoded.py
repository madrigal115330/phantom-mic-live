"""Stage the built probe into a COPY of an apktool-decoded, merged WhatsApp.
No installation, signing, or integrity-check changes are performed.
Usage: python merge_decoded.py decoded_whatsapp decoded_probe output_directory
"""
import sys, shutil
from pathlib import Path
import xml.etree.ElementTree as ET
wa,probe,out=map(Path,sys.argv[1:])
if out.exists(): raise SystemExit('Output must not exist')
ns='http://schemas.android.com/apk/res/android'
ET.register_namespace('android',ns)
tree=ET.parse(wa/'AndroidManifest.xml'); root=tree.getroot()
if root.get('package')!='com.whatsapp': raise SystemExit('Expected WhatsApp')
if not (wa/'lib/arm64-v8a').is_dir(): raise SystemExit('Use the full merged arm64 APK')
if not (probe/'lib/arm64-v8a/libglassmic_native.so').is_file(): raise SystemExit('Build the probe APK first')
if list(wa.glob('smali*/io/mo/glassmic')): raise SystemExit('Existing GlassMic classes; refusing duplicate patch')
shutil.copytree(wa,out)
for folder in sorted(probe.glob('smali*')):
 if not folder.is_dir():continue
 n=2
 while (out/f'smali_classes{n}').exists():n+=1
 shutil.copytree(folder,out/f'smali_classes{n}')
for f in (probe/'lib/arm64-v8a').glob('*.so'):
 dest=out/'lib/arm64-v8a'/f.name
 if dest.exists() and dest.read_bytes()!=f.read_bytes():raise SystemExit('Native library conflict: '+f.name)
 shutil.copy2(f,dest)
app=root.find('application')
a=ET.SubElement(app,'activity',{f'{{{ns}}}name':'io.mo.glassmic.xposed.ProbeActivity',f'{{{ns}}}exported':'true',f'{{{ns}}}label':'WhatsApp Audio Probe',f'{{{ns}}}theme':'@android:style/Theme.Material.Light.NoActionBar'})
f=ET.SubElement(a,'intent-filter')
ET.SubElement(f,'action',{f'{{{ns}}}name':'android.intent.action.MAIN'})
ET.SubElement(f,'category',{f'{{{ns}}}name':'android.intent.category.LAUNCHER'})
tree.write(out/'AndroidManifest.xml',encoding='utf-8',xml_declaration=True)
print('Staged only. Build with apktool, align, sign and verify before testing.')
