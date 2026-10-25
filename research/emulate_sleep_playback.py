# SPDX-License-Identifier: GPL-3.0-only
"""Offline stock A6611/01.57 Sleep playback setter and getter execution.
User supplies the pinned firmware. Audio/TWS/slot-loading helpers are modeled;
this never uses a radio, changes device firmware or proves audible playback.
"""
from pathlib import Path
import argparse,hashlib,struct,json
from unicorn import Uc,UC_ARCH_ARM,UC_MODE_THUMB,UC_MODE_MCLASS,UC_HOOK_CODE
from unicorn.arm_const import UC_ARM_REG_R0,UC_ARM_REG_R1,UC_ARM_REG_R2,UC_ARM_REG_SP,UC_ARM_REG_LR,UC_ARM_REG_PC
p=argparse.ArgumentParser(description=__doc__);p.add_argument('firmware',type=Path);p.add_argument('--output',type=Path);a=p.parse_args()
b=a.firmware.read_bytes();digest=hashlib.sha256(b).hexdigest()
assert digest=='20f5f148be6f346677422679e78a251219a72ac9501ad959a01783fa8439df9f'
checks=0

def check(value,why):
 global checks
 checks+=1;assert value,why

o=0x3426e290-0x34018000;table=[tuple(b[o+i*3:o+i*3+3]) for i in range(65)]
check((0x13,2,0x38) in table,'setter dispatch')
check((0x13,3,0x39) in table,'state notification dispatch')
check(struct.unpack_from('<I',b,0x1412c1cc-0x14018000)[0]==0x1412b299,'setter callback')
check(struct.unpack_from('<I',b,0x1412c1d0-0x14018000)[0]==0x141298c5,'getter callback')
cases=[]
for mode in (0,1):
 for master in (False,True):
  for slot in range(5):
   for valid in (False,True):
    for before in (0,1):
     for desired in (0,1):
      m=Uc(UC_ARCH_ARM,UC_MODE_THUMB|UC_MODE_MCLASS)
      for addr,size in ((0x14018000,0x300000),(0x34018000,0x300000),(0x20000000,0x100000),(0x20500000,0x100000),(0x1000,0x1000)):m.mem_map(addr,size)
      m.mem_write(0x14018000,b);m.mem_write(0x34018000,b)
      state=0x2055ce18;out=0x20001000;payload=0x20002000
      m.mem_write(state+0x8e,bytes([mode]));m.mem_write(state+0x93,bytes([0,before]))
      if valid and slot<4:m.mem_write(state+0xea+slot*10,bytes([1])+bytes(range(1,10)))
      m.mem_write(payload,bytes([slot,desired]))
      helpers={0x141186dc:'log',0x140b5d68:'master',0x1412f6c0:'other-audio',0x1412f7b4:'stop-other-audio',0x14121434:'audio-apply',0x14123280:'peer-sync',0x1412c5e4:'notify',0x1412ff10:'load-slot'}
      for addr in helpers:m.mem_write(addr,b'\x70\x47')
      calls=[]
      def hook(uc,addr,size,data):
       if addr==0x1000:uc.emu_stop();return
       if addr in helpers:
        name=helpers[addr]
        if name not in ('log','master','other-audio'):calls.append((name,[uc.reg_read(r) for r in (UC_ARM_REG_R0,UC_ARM_REG_R1)]))
        uc.reg_write(UC_ARM_REG_R0,int(master) if name=='master' else 0);return
       if not any(lo<=addr<hi for lo,hi in ((0x1412b298,0x1412b310),(0x14130344,0x14130404),(0x141298c4,0x1412995c),(0x1412d938,0x1412d940),(0x1411ef00,0x1411f300))):raise AssertionError('unmodeled '+hex(addr))
      m.hook_add(UC_HOOK_CODE,hook)
      def run(entry):
       m.reg_write(UC_ARM_REG_R0,out);m.reg_write(UC_ARM_REG_R1,payload);m.reg_write(UC_ARM_REG_SP,0x200ff000);m.reg_write(UC_ARM_REG_LR,0x1001)
       m.emu_start(entry|1,0,count=5000);check(m.reg_read(UC_ARM_REG_PC)==0x1000,'callback return')
      run(0x1412b298)
      allowed=master and mode==1 and slot<4 and valid
      ack=bytes(m.mem_read(out,10))
      check(ack[0]==0x38 and ack[1]==int(not master or allowed),'ACK including ignored non-master')
      actual=bytes(m.mem_read(state+0x93,2))
      check(actual==(bytes([slot,desired]) if allowed else bytes([0,before])),'selected slot and playing state')
      audio=[args for name,args in calls if name=='audio-apply']
      check(audio==([[slot,desired]] if allowed else []),'exact audio request')
      # Execute actual getter too, covering both valid/invalid selected-slot response lengths.
      run(0x141298c4)
      response=bytes(m.mem_read(out,10));ptr=struct.unpack_from('<I',response,4)[0];n=struct.unpack_from('<H',response,8)[0]
      body=bytes(m.mem_read(ptr,n));selected_valid=bool(m.mem_read(state+0xea+actual[0]*10,1)[0])
      check(response[:2]==bytes([0x39,1]) and n==(11 if selected_valid else 12),'getter shape')
      check(body[0]==actual[0] and body[-2]==actual[1],'getter slot/playback readback')
      if allowed and before!=desired:cases.append(dict(slot=slot,before=before,desired=desired,after=list(actual)))
report=dict(firmware_sha256=digest,checks=checks,wire_setter=[19,2],wire_state=[19,3],payload='current selected slot (0..3), desired playing flag (0/1)',normal_state_slot_offset=79,normal_state_playing_offset=80,actual_stock_setter_and_local_transition_and_getter_executed=True,audio_tws_slot_loading='explicit models, no hardware verification',requires='Sleep mode and valid saved slot; mobile-master ACK alone is insufficient',changed_cases=cases)
if a.output:a.output.write_text(json.dumps(report,indent=2)+'\n')
print('Stock Sleep-playback offline checks passed:',checks)
