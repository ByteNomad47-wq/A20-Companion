# SPDX-License-Identifier: GPL-3.0-only
"""Execute the stock 01.57 mode callback offline. No phone, radio or firmware writes.
Requires unicorn==2.1.4; firmware is user-supplied and is not redistributed.
Hardware state and internal transition effects are explicit models, not device tests.
"""
from pathlib import Path
import argparse,struct,hashlib,json
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('firmware', type=Path)
parser.add_argument('--output', type=Path)
args=parser.parse_args()
from unicorn import Uc,UC_ARCH_ARM,UC_MODE_THUMB,UC_MODE_MCLASS,UC_HOOK_CODE
from unicorn.arm_const import UC_ARM_REG_R0,UC_ARM_REG_R1,UC_ARM_REG_R2,UC_ARM_REG_SP,UC_ARM_REG_LR,UC_ARM_REG_PC
image=args.firmware.read_bytes()
assert hashlib.sha256(image).hexdigest()=='20f5f148be6f346677422679e78a251219a72ac9501ad959a01783fa8439df9f'
checks=0

def check(condition,why):
 global checks
 checks+=1
 assert condition,why

def word(addr):return struct.unpack_from('<I',image,addr-0x14018000)[0]
# Actual constant mapping connects wire command to the callback array index.
table=image[0x3426e290-0x34018000:0x3426e290-0x34018000+65*3]
entries=[tuple(table[i:i+3]) for i in range(0,len(table),3)]
check((1,0xa9,0x18) in entries,'wire setter maps to internal 0x18')
check((1,0x14,0x17) in entries,'wire getter maps to internal 0x17')
check(word(0x1412c138)==0x1412a265,'registered mode setter pointer')
check(word(0x1412c128)==0x1412937d,'registered mode getter pointer')

def run(desired,ota=False,master=True,awake=True,scene=False,playing=False):
 m=Uc(UC_ARCH_ARM,UC_MODE_THUMB|UC_MODE_MCLASS)
 for addr,size in [(0x14018000,0x300000),(0x34018000,0x300000),(0x20000000,0x100000),(0x20500000,0x100000),(0x1000,0x1000)]:m.mem_map(addr,size)
 m.mem_write(0x14018000,image);m.mem_write(0x34018000,image)
 state=0x2055ce18;output=0x20001000;payload=0x20002000
 m.mem_write(state+4,bytes([int(awake)]));m.mem_write(state+0x742,bytes([int(scene)]));m.mem_write(payload,bytes([desired]))
 m.reg_write(UC_ARM_REG_R0,output);m.reg_write(UC_ARM_REG_R1,payload)
 m.reg_write(UC_ARM_REG_SP,0x200ff000);m.reg_write(UC_ARM_REG_LR,0x1001)
 # Explicit models for unrelated logging, hardware state and the transition side effects.
 stubs={0x141186dc:'log',0x1411f2e4:'ota',0x140b5d68:'master',0x1412f6c0:'playing',0x1412f7b4:'stop_audio',0x141309f4:'transition'}
 for addr in stubs:m.mem_write(addr,b'\x70\x47')
 calls=[]
 def code(uc,addr,size,user):
  if addr==0x1000:m.emu_stop();return
  if addr in stubs:
   name=stubs[addr]
   value={'ota':int(ota),'master':int(master),'playing':int(playing)}.get(name,0)
   if name=='transition':
    args=tuple(uc.reg_read(reg) for reg in (UC_ARM_REG_R0,UC_ARM_REG_R1,UC_ARM_REG_R2));calls.append(('transition',args));uc.mem_write(state+0x8e,bytes([args[0]]))
   elif name=='stop_audio':calls.append(('stop_audio',))
   uc.reg_write(UC_ARM_REG_R0,value);return
  if not(0x1412a264<=addr<0x1412a350 or 0x1412d938<=addr<0x1412d940 or 0x1411ef00<=addr<0x1411f300):raise AssertionError('unexpected call '+hex(addr))
 m.hook_add(UC_HOOK_CODE,code);m.emu_start(0x1412a265,0,count=5000)
 check(m.reg_read(UC_ARM_REG_PC)==0x1000,'callback returned')
 status=bytes(m.mem_read(output,10));transitions=[c[1] for c in calls if c[0]=='transition']
 allowed=not ota and master and awake and not scene
 check(transitions==([(desired,1,0)] if allowed else []),'exact transition argument and single call/refusal')
 check(status[0]==0x18,'response retains internal command')
 check(status[1]==(1 if allowed or (not master and not ota and not scene) else 0),'firmware success flag/refusal behavior')
 check(status[4:10]==bytes(6),'acknowledgement has no payload')
 return {'requested':desired,'ota':ota,'mobile_master':master,'awake':awake,'sleep_scene':scene,'playing':playing,'transition_calls':len(transitions),'ack_status':status[1]}
results=[]
for desired in (0,1):
 for options in ({},{'ota':True},{'master':False},{'awake':False},{'scene':True},{'playing':True}):results.append(run(desired,**options))
report={'firmware_sha256':hashlib.sha256(image).hexdigest(),'checks':checks,'wire_setter':[1,169],'wire_payload_values':{'bluetooth':0,'sleep':1},'wire_getter':[1,20],'actual_stock_thumb_callback_executed':True,'mode_transition_effects':'modeled, not physically emulated','hardware_and_radio':'not tested','cases':results}
if args.output:args.output.write_text(json.dumps(report,indent=2)+'\n')
print('Stock mode callback checks passed:',checks)
