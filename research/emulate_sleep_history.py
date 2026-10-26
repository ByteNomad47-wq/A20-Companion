# SPDX-License-Identifier: GPL-3.0-only
"""Offline stock A6611 sleep-history framing checks; no Bluetooth or firmware writes.
Requires a separately obtained exact firmware and unicorn==2.1.4.
"""
from pathlib import Path
import sys,struct,json,hashlib,argparse
parser=argparse.ArgumentParser(description=__doc__);parser.add_argument("firmware",type=Path);parser.add_argument("--output",type=Path);args=parser.parse_args()
from unicorn import *
from unicorn.arm_const import *
b=args.firmware.read_bytes();sha=hashlib.sha256(b).hexdigest();assert sha=='20f5f148be6f346677422679e78a251219a72ac9501ad959a01783fa8439df9f'
def word(a):return struct.unpack_from('<I',b,a-0x14018000)[0]
checks=0;cases=[]
for cmd,entry,header,stride,limit,per,magic,pool in [(0x26,0x1412c65c,0x187,6,100,25,0xaabbccee,0x1412c898),(0x2d,0x1412c8a4,0x3eb,14,50,10,0xaabbcda4,0x1412cb3c)]:
 for ear in [0,1]:
  for count in sorted(set([0,1,per,per+1,limit])):
   m=Uc(UC_ARCH_ARM,UC_MODE_THUMB|UC_MODE_MCLASS)
   for a,n in [(0x14018000,0x300000),(0x34018000,0x300000),(0x20000000,0x100000),(0x20500000,0x100000),(0x1000,0x1000)]:m.mem_map(a,n)
   m.mem_write(0x14018000,b);m.mem_write(0x34018000,b);state=0x2055ce18;desc=0x20001000;buf=word(pool)
   record=struct.pack('<IHH',magic,count,count)+b''.join(bytes(((i*stride+j)%256 for j in range(stride))) for i in range(limit));m.mem_write(state+header,record);m.mem_write(state+1,bytes([ear]))
   stubs={0x141186dc:'log',0x140b5d68:'master',0x14123fd0:'peer-basic-read',0x1412433c:'peer-summary-read',0x1412c5fc:'send'}
   for a in stubs:m.mem_write(a,b'\x70\x47')
   sent=[];calls=[]
   def hook(uc,a,size,user):
    if a==0x1000:uc.emu_stop();return
    if a in stubs:
     name=stubs[a];calls.append(name)
     if name=='send':
      ptr=uc.reg_read(UC_ARM_REG_R2);n=uc.reg_read(UC_ARM_REG_R3);sent.append(bytes(uc.mem_read(ptr,n)))
     uc.reg_write(UC_ARM_REG_R0,1 if name=='master' else 0);return
    if not(entry<=a<(0x1412c874 if cmd==0x26 else 0x1412cb18) or 0x1412d938<=a<0x1412d940 or 0x1411ef00<=a<0x1411f300):raise AssertionError(hex(a))
   m.hook_add(UC_HOOK_CODE,hook);m.reg_write(UC_ARM_REG_R0,desc);m.reg_write(UC_ARM_REG_SP,0x200ff000);m.reg_write(UC_ARM_REG_LR,0x1001);m.emu_start(entry|1,0,count=100000)
   assert m.reg_read(UC_ARM_REG_PC)==0x1000;checks+=1
   d=bytes(m.mem_read(desc,10));ptr,n=struct.unpack_from('<IH',d,4);sent.append(bytes(m.mem_read(ptr,n)));assert d[:2]==bytes([cmd,1]);checks+=1
   assert len(sent)==max(1,(count+per-1)//per);checks+=1
   payload=b''
   for i,s in enumerate(sent):
    assert s[:3]==bytes([ear,i,len(sent)]) and len(s)==4+s[3]*stride;checks+=1;payload+=s[4:]
   assert payload==record[8:8+count*stride];checks+=1
   assert bytes(m.mem_read(state+header,len(record)))==record;checks+=1
   cases.append({'internal_command':cmd,'ear':ear,'count':count,'chunks':len(sent),'lengths':[len(s) for s in sent]})
table=b[0x3426e290-0x34018000:0x3426e290-0x34018000+195];assert (0x15,1,0x26) in [tuple(table[i:i+3]) for i in range(0,195,3)] and (0x15,0x88,0x2d) in [tuple(table[i:i+3]) for i in range(0,195,3)];checks+=2
r={'firmware_sha256':sha,'checks':checks,'cases':cases,'actual_stock_callbacks_executed':True,'helpers_modeled':['logger','master predicate','peer read requests','normal response send'],'record_regions_unchanged':True,'limitations':['No radio/real device capture','Peer read forwarding effects not emulated','Timestamp epoch and record field meanings not yet qualified','No clear/enable/time-write commands sent']};
if args.output:args.output.write_text(json.dumps(r,indent=2)+"\n")
print('Stock sleep-read callbacks verified:',checks,'checks;',len(cases),'cases')
