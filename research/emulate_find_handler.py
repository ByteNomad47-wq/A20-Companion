# SPDX-License-Identifier: GPL-3.0-only
"""Execute the pinned stock A6611 find setter offline with explicit helper models.
No phone/radio operations. Firmware input is not redistributed.
"""
from pathlib import Path
import argparse,hashlib,struct,json
from unicorn import Uc,UC_ARCH_ARM,UC_MODE_THUMB,UC_MODE_MCLASS,UC_HOOK_CODE
from unicorn.arm_const import UC_ARM_REG_R0,UC_ARM_REG_R1,UC_ARM_REG_R2,UC_ARM_REG_SP,UC_ARM_REG_LR,UC_ARM_REG_PC
p=argparse.ArgumentParser();p.add_argument('firmware',type=Path);p.add_argument('--output',type=Path);args=p.parse_args()
b=args.firmware.read_bytes();assert hashlib.sha256(b).hexdigest()=='20f5f148be6f346677422679e78a251219a72ac9501ad959a01783fa8439df9f'
checks=0
def check(ok,why):
 global checks
 checks+=1;assert ok,why
o=0x3426e290-0x34018000;entries=[tuple(b[o+i*3:o+i*3+3]) for i in range(65)]
check((0x10,0x89,0x35) in entries,'wire setter dispatch')
check((0x10,0x09,0x36) in entries,'wire getter dispatch')
check(struct.unpack_from('<I',b,0x1412c1c0-0x14018000)[0]==0x1412b1c5,'registered setter')
results=[]
for left,right in ((0,0),(1,0),(0,1),(1,1)):
 for master in (False,True):
  m=Uc(UC_ARCH_ARM,UC_MODE_THUMB|UC_MODE_MCLASS)
  for base,size in ((0x14018000,0x300000),(0x34018000,0x300000),(0x20000000,0x100000),(0x20500000,0x100000),(0x1000,0x1000)):m.mem_map(base,size)
  m.mem_write(0x14018000,b);m.mem_write(0x34018000,b)
  output=0x20001000;payload=0x20002000;m.mem_write(payload,bytes([left,right]))
  m.reg_write(UC_ARM_REG_R0,output);m.reg_write(UC_ARM_REG_R1,payload)
  m.reg_write(UC_ARM_REG_SP,0x200ff000);m.reg_write(UC_ARM_REG_LR,0x1001)
  helpers={0x141186dc:'log',0x140b5d68:'master',0x1412ffc4:'stop-other-audio',0x14123d84:'tws',0x141308e8:'local-find'}
  for addr in helpers:m.mem_write(addr,b'\x70\x47')
  calls=[]
  def hook(uc,addr,size,data):
   if addr==0x1000:m.emu_stop();return
   if addr in helpers:
    name=helpers[addr]
    if name not in ('log','master'):calls.append((name,[uc.reg_read(r) for r in (UC_ARM_REG_R0,UC_ARM_REG_R1,UC_ARM_REG_R2)]))
    uc.reg_write(UC_ARM_REG_R0,int(master) if name=='master' else 0);return
   if not(0x1412b1c4<=addr<0x1412b280 or 0x1412d938<=addr<0x1412d940 or 0x1411ef00<=addr<0x1411f300):raise AssertionError('unmodeled '+hex(addr))
  m.hook_add(UC_HOOK_CODE,hook);m.emu_start(0x1412b1c5,0,count=5000)
  check(m.reg_read(UC_ARM_REG_PC)==0x1000,'returned')
  check(bytes(m.mem_read(output,10))==bytes([0x35,1,0,0,0,0,0,0,0,0]),'empty successful ACK; note ACK even when master gate ignores action')
  local=[v[:2] for name,v in calls if name=='local-find']
  check(local==([[left,right]] if master else []),'exact flags forwarded only by master')
  results.append({'left':left,'right':right,'master':master,'local_flags':local})
report={'firmware_sha256':hashlib.sha256(b).hexdigest(),'checks':checks,'wire_setter':[16,137],'wire_getter':[16,9],'body':'two enable flags; local side0 selects first, side1 second','cases':results,'actual_stock_callback_executed':True,'audio_tws_and_timer_effects':'modeled, not emulated','bounded_native_timeout_verified':False,'physical_test':False,'limitations':'ACK can succeed without action when mobile-master gate refuses; getter returns zeros in examined stub; no physical/radio or disconnect verification'}
if args.output:args.output.write_text(json.dumps(report,indent=2)+'\n')
print('Find setter offline checks passed:',checks)
