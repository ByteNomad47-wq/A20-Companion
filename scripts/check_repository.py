#!/usr/bin/env python3
"""Validate repository privacy boundaries, mode gate, relative docs and optional APK."""
from pathlib import Path
from urllib.parse import unquote
import argparse,hashlib,json,re,xml.etree.ElementTree as E,zipfile
ROOT=Path(__file__).resolve().parents[1]
ANDROID='{http://schemas.android.com/apk/res/android}'
SKIP={'.git','.gradle','.kotlin','.tools','build','__pycache__'}
FORBIDDEN={'android.permission.INTERNET','android.permission.ACCESS_FINE_LOCATION','android.permission.ACCESS_COARSE_LOCATION','android.permission.RECORD_AUDIO','android.permission.BLUETOOTH_SCAN','android.permission.SYSTEM_ALERT_WINDOW','android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS','android.permission.WAKE_LOCK'}
def files():
 for p in ROOT.rglob('*'):
  if any(x in SKIP for x in p.relative_to(ROOT).parts) or p.is_symlink() or p.name=='local.properties':continue
  if p.is_file():yield p

def main():
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--apk',type=Path);parser.add_argument('--variant',choices=('release',),default='release');args=parser.parse_args()
 for p in files():
  assert p.suffix.lower() not in {'.jks','.keystore','.pem','.key','.bin','.dex','.so'},str(p)
  assert p.name!='local.properties' or p.parent==ROOT,str(p)
  if p.name=='local.properties':continue
  if p.suffix=='.json':json.loads(p.read_text())
  if p.suffix=='.md':
   for target in re.findall(r'\[[^\]]*\]\(([^)]+)\)',p.read_text()):
    target=target.strip('<>').split('#')[0].split('?')[0]
    if not target or '://' in target or target.startswith('mailto:'):continue
    assert (p.parent/unquote(target)).is_file(),str(p.relative_to(ROOT))+' -> '+target
 manifest=E.parse(ROOT/'app/src/main/AndroidManifest.xml').getroot()
 permissions={p.attrib[ANDROID+'name'] for p in manifest.findall('uses-permission')}
 assert not permissions&FORBIDDEN,permissions&FORBIDDEN
 app=manifest.find('application');assert app.attrib[ANDROID+'allowBackup']=='false'
 services=app.findall('service');assert any(s.attrib[ANDROID+'name']=='.ActionService' and s.attrib[ANDROID+'exported']=='false' for s in services)
 for service in services:
  if service.attrib.get(ANDROID+'exported')=='true':assert service.attrib.get(ANDROID+'permission') in {'android.permission.BIND_QUICK_SETTINGS_TILE','android.permission.BIND_COMPANION_DEVICE_SERVICE'}
 main_activity=next(a for a in app.findall('activity') if a.attrib[ANDROID+'name']=='.MainActivity')
 shortcuts_metadata=[m for m in main_activity.findall('meta-data') if m.attrib[ANDROID+'name']=='android.app.shortcuts']
 assert len(shortcuts_metadata)==1 and shortcuts_metadata[0].attrib[ANDROID+'resource']=='@xml/shortcuts'
 shortcuts=E.parse(ROOT/'app/src/main/res/xml/shortcuts.xml').getroot().findall('shortcut')
 assert len(shortcuts)==2 and {s.attrib[ANDROID+'shortcutId'] for s in shortcuts}=={'bluetooth_mode','sleep_mode'}
 assert {s.find('intent').attrib[ANDROID+'action'] for s in shortcuts}=={'io.github.bytenomad47.a20.shortcut.BLUETOOTH','io.github.bytenomad47.a20.shortcut.SLEEP'}
 for shortcut in shortcuts:
  target=shortcut.find('intent').attrib
  assert target[ANDROID+'targetPackage']=='io.github.bytenomad47.a20' and target[ANDROID+'targetClass']=='io.github.bytenomad47.a20.ModeShortcutActivity'
 shortcut_activity=next(a for a in app.findall('activity') if a.attrib[ANDROID+'name']=='.ModeShortcutActivity')
 assert shortcut_activity.attrib[ANDROID+'excludeFromRecents']=='true' and shortcut_activity.attrib[ANDROID+'taskAffinity']==''
 widgets=[r for r in app.findall('receiver') if r.attrib[ANDROID+'name'].startswith('.widgets.')]
 assert len(widgets)==2
 for receiver in widgets:
  assert receiver.attrib[ANDROID+'exported']=='false'
  resource=receiver.find('meta-data').attrib[ANDROID+'resource'].split('/')[-1]
  config=E.parse(ROOT/'app/src/main/res/xml'/f'{resource}.xml').getroot()
  assert config.attrib[ANDROID+'updatePeriodMillis']=='0','Widgets must not poll'
  assert config.attrib[ANDROID+'resizeMode']=='none','Widgets use fixed launcher spans'
 gate=(ROOT/'core/protocol/src/main/kotlin/io/github/bytenomad47/a20/protocol/A20State.kt').read_text()
 assert re.search(r'const val VERIFIED\s*=\s*true',gate)
 assert 'QUALIFIED_FIRMWARE = "01.57"' in gate
 evidence=json.loads((ROOT/'research/mode-control-device-validation.json').read_text())
 assert evidence['confirmed_sleep_transitions']==4 and evidence['confirmed_bluetooth_transitions']==4 and evidence['owner_confirmed_switching']
 transport=(ROOT/'core/device/src/main/kotlin/io/github/bytenomad47/a20/device/RfcommTransport.kt').read_text()
 assert 'createRfcommSocketToServiceRecord' in transport and 'createInsecure' not in transport
 assert 'A20Protocol.stateRequest()' in transport
 assert 'if (!allowModeWrites)' in transport and 'ModeCapability.supportsModeControl' in transport
 assert 'A20Protocol.modeRequest(mode)' in transport
 assert 'A20Protocol.sleepVolumeRequest(level)' in transport
 volume=json.loads((ROOT/'research/sleep-volume-handler-validation.json').read_text())
 assert volume['wire_setter']==[19,5] and volume['allowed_raw_levels']==[0,65] and volume['checks']==554
 repository=(ROOT/'core/device/src/main/kotlin/io/github/bytenomad47/a20/device/CompanionRepository.kt').read_text()
 assert 'allowModeWrites = ModeCapability.VERIFIED' in repository
 assert '00001101' not in transport,'Normal controls must not use the diagnostic service'
 repair_service=next(s for s in services if s.attrib[ANDROID+'name']=='.RepairService')
 assert repair_service.attrib[ANDROID+'exported']=='false' and not repair_service.findall('intent-filter')
 repair_transport=(ROOT/'app/src/main/java/io/github/bytenomad47/a20/repair/DiagnosticConnection.kt').read_text()
 assert '00001101-0000-1000-8000-00805f9b34fb' in repair_transport
 assert 'createInsecure' not in repair_transport and 'createRfcommSocketToServiceRecord' in repair_transport
 repair_worker=(ROOT/'app/src/main/java/io/github/bytenomad47/a20/RepairService.kt').read_text()
 assert 'getStringExtra' not in repair_worker and 'getBooleanExtra' not in repair_worker
 assert 'withRepairLease' in repository
 checks=ROOT/'artifacts/SHA256SUMS'
 if checks.exists():
  for line in checks.read_text().splitlines():
   expected,name=line.split('  ',1);assert hashlib.sha256((ROOT/'artifacts'/name).read_bytes()).hexdigest()==expected,name
 if args.apk:
  with zipfile.ZipFile(args.apk) as z:
   assert z.testzip() is None
   for name in ('assets/licenses/GPL-3.0.txt','assets/licenses/APACHE-2.0.txt'):assert name in z.namelist(),name
   assert not any('libscsecurity' in name or 'a20-research' in name or (name.endswith('.bin') and name != 'DebugProbesKt.bin') for name in z.namelist())
  manifests=list((ROOT/'app/build/intermediates').glob('merged_manifest/'+args.variant+'/*/AndroidManifest.xml'))
  if not manifests:manifests=list((ROOT/'app/build/intermediates').glob('merged_manifests/'+args.variant+'/*/AndroidManifest.xml'))
  assert manifests,'Build release before APK verification'
  merged=E.parse(manifests[0]).getroot();p={x.attrib[ANDROID+'name'] for x in merged.findall('uses-permission')};assert not p&FORBIDDEN,p&FORBIDDEN
  assert merged.find('application').attrib.get(ANDROID+'debuggable','false')=='false'
 print('Repository privacy/gate/docs checks passed'+('; release APK/manifest verified' if args.apk else ''))
if __name__=='__main__':main()
