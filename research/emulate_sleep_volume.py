# SPDX-License-Identifier: GPL-3.0-only
"""Offline execution of the stock A6611/01.57 Sleep-volume setter.
Hardware application, peer synchronization and usage counters are explicit stubs.
No Bluetooth, firmware writes or proprietary binary distribution.
Requires separately installed unicorn==2.1.4 and the owner's firmware image.
"""
from pathlib import Path
import argparse,hashlib,json,struct
from unicorn import Uc,UC_ARCH_ARM,UC_MODE_THUMB,UC_MODE_MCLASS,UC_HOOK_CODE
from unicorn.arm_const import UC_ARM_REG_R0,UC_ARM_REG_R1,UC_ARM_REG_SP,UC_ARM_REG_LR,UC_ARM_REG_PC
p=argparse.ArgumentParser(description=__doc__);p.add_argument('firmware',type=Path);p.add_argument('--output',type=Path);a=p.parse_args()
b=a.firmware.read_bytes();digest=hashlib.sha256(b).hexdigest()
assert digest=='20f5f148be6f346677422679e78a251219a72ac9501ad959a01783fa8439df9f'
table=b[0x3426e290-0x34018000:0x3426e290-0x34018000+65*3]
assert (0x13,5,0x3b) in [tuple(table[i:i+3]) for i in range(0,len(table),3)]
assert struct.unpack_from('<I',b,0x1412c1d8-0x14018000)[0]==0x1412a375
checks=2
cases=[]
for master in (True,False):
 for value in list(range(66))+[66,127,255]:
  m=Uc(UC_ARCH_ARM,UC_MODE_THUMB|UC_MODE_MCLASS)
  for address,size in [(0x14018000,0x300000),(0x34018000,0x300000),(0x20000000,0x100000),(0x20500000,0x100000),(0x1000,0x1000)]:m.mem_map(address,size)
  m.mem_write(0x14018000,b);m.mem_write(0x34018000,b)
  state=0x2055ce18;output=0x20001000;payload=0x20002000
  m.mem_write(state+0x95,bytes([30,30]));m.mem_write(payload,bytes([value]))
  m.reg_write(UC_ARM_REG_R0,output);m.reg_write(UC_ARM_REG_R1,payload);m.reg_write(UC_ARM_REG_SP,0x200ff000);m.reg_write(UC_ARM_REG_LR,0x1001)
  stubs={0x141186dc:'log',0x140b5d68:'master',0x14130424:'apply',0x141230b4:'peer',0x1412c5e4:'notify',0x14121ae4:'usage_counter'}
  for address in stubs:m.mem_write(address,b'\x70\x47')
  calls=[]
  def code(uc,address,size,user):
   if address==0x1000:uc.emu_stop();return
   if address in stubs:
    name=stubs[address]
    if name not in ('log','master'):calls.append(name)
    uc.reg_write(UC_ARM_REG_R0,int(master) if name=='master' else 0);return
   if not (0x1412a374<=address<0x1412a3d8 or 0x14130484<=address<0x141304e8 or 0x1411ef00<=address<0x1411f300):raise AssertionError('Unexpected call '+hex(address))
  m.hook_add(UC_HOOK_CODE,code);m.emu_start(0x1412a375,0,count=5000)
  allowed=master and value<=65;status=bytes(m.mem_read(output,10));actual=bytes(m.mem_read(state+0x95,2))
  assert m.reg_read(UC_ARM_REG_PC)==0x1000;checks+=1
  assert actual==(bytes([value,value]) if allowed else bytes([30,30]));checks+=1
  assert status[0]==0x3b and status[1]==int(not master or value<=65) and status[4:10]==bytes(6);checks+=1
  assert calls==(['apply','peer','notify','usage_counter'] if allowed else []);checks+=1
  if value in (0,30,65,66,127,255):cases.append({'requested':value,'mobile_master':master,'applied':allowed,'ack_status':status[1]})
report={'firmware_sha256':digest,'checks':checks,'wire_setter':[19,5],'registered_callback':'0x1412a374','allowed_raw_levels':[0,65],'normal_status_effective_volume_body_offset':78,'actual_stock_thumb_callback_executed':True,'hardware_audio_peer_usage_counters':'explicit models; no physical volume verification','boundary_cases':cases}
if a.output:a.output.write_text(json.dumps(report,indent=2)+'\n')
print('Stock Sleep-volume callback checks passed:',checks)
