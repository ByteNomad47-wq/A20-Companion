#!/usr/bin/env python3
"""Install pinned Android CLI and platform/build tools. No emulator or phone changes."""
from pathlib import Path
import argparse,hashlib,os,shutil,subprocess,urllib.request,zipfile
ARCHIVE="commandlinetools-linux-16111833_latest.zip"
# Official repository2-1.xml archive checksum for this exact Linux package.
SHA1="e025545c62a8e64c7559119566a569fb1dec5f60"
def main():
 parser=argparse.ArgumentParser(description=__doc__)
 parser.add_argument('--sdk',type=Path,default=Path('.tools/android-sdk'))
 parser.add_argument('--github-env',action='store_true')
 args=parser.parse_args();sdk=args.sdk.resolve();sdk.mkdir(parents=True,exist_ok=True)
 cli=sdk/'cmdline-tools/latest/bin/android'
 if not cli.exists():
  archive=sdk/ARCHIVE;tmp=archive.with_suffix('.download')
  try:
   with urllib.request.urlopen('https://dl.google.com/android/repository/'+ARCHIVE,timeout=60) as response,tmp.open('wb') as out:shutil.copyfileobj(response,out)
   with tmp.open('rb') as stream:actual=hashlib.file_digest(stream,'sha1').hexdigest()
   if actual!=SHA1:raise ValueError('Android CLI archive checksum mismatch')
   tmp.replace(archive)
   stage=sdk/'unpack';stage.mkdir(exist_ok=True)
   with zipfile.ZipFile(archive) as z:
    for name in z.namelist():
     if not (stage/name).resolve().is_relative_to(stage):raise ValueError('Unsafe archive path')
    z.extractall(stage)
   (sdk/'cmdline-tools').mkdir(exist_ok=True)
   shutil.move(str(stage/'cmdline-tools'),str(sdk/'cmdline-tools/latest'))
   for f in (sdk/'cmdline-tools/latest/bin').iterdir():f.chmod(0o755)
  finally:tmp.unlink(missing_ok=True)
 for package in ('platforms/android-37.0','build-tools/37.0.0'):
  if not (sdk/package).exists():subprocess.run([str(cli),'--no-metrics','--sdk='+str(sdk),'sdk','install',package],check=True)
 if args.github_env:
  with open(os.environ['GITHUB_ENV'],'a') as f:f.write('ANDROID_HOME='+str(sdk)+'\nANDROID_SDK_ROOT='+str(sdk)+'\n')
 print('SDK ready:',sdk)
if __name__=='__main__':main()
