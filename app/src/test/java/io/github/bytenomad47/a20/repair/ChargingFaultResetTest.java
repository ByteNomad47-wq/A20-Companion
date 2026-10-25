// SPDX-License-Identifier: GPL-3.0-only
package io.github.bytenomad47.a20.repair;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;
/** Exact supplied histories and synthetic streams; NEVER connects to a device. */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk={35}, application=android.app.Application.class)
public final class ChargingFaultResetTest {
 @org.junit.Test public void auditedOfflineCases() throws Exception { main(new String[]{"src/test/resources/repair-fixtures/case-left-history.json","src/test/resources/repair-fixtures/case-right-history.json"}); }
 static int checks;static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
 interface Op{void run()throws Exception;}static void rejects(Op op,String why)throws Exception{boolean bad=false;try{op.run();}catch(Exception e){bad=true;}check(bad,why);}
 static String wire(JSONObject h)throws Exception {
  StringBuilder s=new StringBuilder("EARSIDE "+h.getString("ear")+"CHG_DATA_NV lock:"+h.getInt("rawMainCounter")+", index:"+h.getInt("rawIndex")+", err1:"+h.getInt("error1")+", err2:"+h.getInt("error2")+", err3:"+h.getInt("error3")+", nor:"+h.getInt("normalCount")+"\n");
  JSONArray a=h.getJSONArray("storedHistory");for(int i=0;i<a.length();i++){JSONObject r=a.getJSONObject(i);s.append("ct[").append(r.getInt("slot")).append("]:").append(r.getLong("rawChargeTime")).append('\n');JSONArray q=r.getJSONArray("storedSamples");for(int j=0;j<q.length();j++){JSONObject v=q.getJSONObject(j);s.append(v.getLong("rawTime")).append(',').append(v.getInt("rawVoltage")).append('\n');}}
  return s.toString();
 }
 static String cleared(String ear){StringBuilder b=new StringBuilder("EARSIDE "+ear+"CHG_DATA_NV lock:0, index:0, err1:0, err2:0, err3:0, nor:0\n");for(int i=0;i<20;i++){b.append("ct[").append(i).append("]:0\n");for(int j=0;j<10;j++)b.append("0,0\n");}return b.toString();}
 static class Link extends ChargingProtocolTest.Link {
  String ear,history;boolean didClear,loseAck,badAck;int clearCount;
  Link(String ear,String history){this.ear=ear;this.history=history;}
  public void send(int command){sent.add(command);String text;
   if(command=='3'){clearCount++;didClear=true;if(loseAck)return;text="EARSIDE "+ear+(badAck?"WRONG_REPLY":"CLEAR_CHARGE_INF");}
   else if(command=='4')text="EARSIDE "+ear+"local_bat_volt 3780";
   else if(command=='7')text=didClear?cleared(ear):history;
   else if(command=='2')text="EARSIDE "+ear+"CHG_TIME_NV valid, lock:0, index:"+(didClear?0:1)+", err1:0, err2:0, err3:0, nor:"+(didClear?0:1)+"\n"+(didClear?"":"i:0, t:900\n");
   else throw new AssertionError("unexpected command");
   byte[] bytes=text.getBytes(StandardCharsets.US_ASCII);chunks.add(new ChargingProtocolTest.Chunk(clock,bytes));
  }
 }
 public static void main(String[] args)throws Exception{
  for(String file:args){JSONObject report=new JSONObject(new String(Files.readAllBytes(Paths.get(file)),StandardCharsets.UTF_8));JSONObject h=report.getJSONArray("reads").getJSONObject(1).getJSONObject("result");String ear=h.getString("ear"),text=wire(h);
   check(text.getBytes(StandardCharsets.US_ASCII).length==h.getInt("replyBytes"),"wire reconstructed from captured numeric fields");
   Link prepare=new Link(ear,text);ChargingFaultReset.Snapshot snapshot=ChargingFaultReset.read(prepare,true);ChargingFaultReset.guard(snapshot);
   check(prepare.sent.equals(Arrays.asList((int)'4',(int)'7',(int)'2')),"preparation sends read-only4/7/2, never clear");
   Link reset=new Link(ear,text);ArrayList<JSONObject> states=new ArrayList<>();boolean[] claimed={false};
   ChargingFaultReset.reset(reset,snapshot,value->{check(reset.clearCount==0,"single-use claim must happen BEFORE any clear");check(value.equals(ear),"ledger receives the prepared ear");claimed[0]=true;},v->states.add(new JSONObject(v.toString())));
   check(claimed[0] && reset.clearCount==1,"one clear only");check(reset.sent.equals(Arrays.asList((int)'4',(int)'7',(int)'2',(int)'3',(int)'4',(int)'7',(int)'2')),"fresh read,one clear,full verification only");
   JSONObject last=states.get(states.size()-1);check(last.getBoolean("ramResetVerified") && !last.getBoolean("persistenceVerified") && !last.getBoolean("chargingFixVerified"),"RAM success never claims persistence/repair");
   Link ledgerFail=new Link(ear,text);rejects(()->ChargingFaultReset.reset(ledgerFail,snapshot,value->{throw new java.io.IOException("claim failed");},v->{}),"ledger failure refuses mutation");check(ledgerFail.clearCount==0,"no3 when persistent claim fails");
   Link lost=new Link(ear,text);lost.loseAck=true;int[] claims={0};rejects(()->ChargingFaultReset.reset(lost,snapshot,value->claims[0]++,v->{}),"lost ack is unknown, never retried");check(lost.clearCount==1 && claims[0]==1 && lost.sent.size()==4,"lost ack consumes one attempt, sends no further commands");
   Link wrongAck=new Link(ear,text);wrongAck.badAck=true;rejects(()->ChargingFaultReset.reset(wrongAck,snapshot,value->{},v->{}),"unexpected ack refused");check(wrongAck.clearCount==1,"no repeat after unexpected ack");
   Link changed=new Link(ear,text.replace("nor:"+h.getInt("normalCount"),"nor:99"));rejects(()->ChargingFaultReset.reset(changed,snapshot,value->{},v->{}),"changed history refused");check(changed.clearCount==0,"changed history sends no3");
   Link dirty=new Link(ear,text);dirty.chunks.add(new ChargingProtocolTest.Chunk(0,ChargingProtocolTest.b("unsolicited")));rejects(()->ChargingFaultReset.reset(dirty,snapshot,value->{},v->{}),"dirty stream refuses reset");check(dirty.clearCount==0,"dirty stream never mutates");
   Link cancelled=new Link(ear,text);cancelled.cancel=true;rejects(()->ChargingFaultReset.reset(cancelled,snapshot,value->{},v->{}),"cancelled reset refuses writes");check(cancelled.clearCount==0,"cancelled sends no3");
   ChargingFaultReset.Snapshot wrong=new ChargingFaultReset.Snapshot(ear,"bad",snapshot.timingHash,snapshot.data);Link pinned=new Link(ear,text);rejects(()->ChargingFaultReset.reset(pinned,wrong,value->{},v->{}),"changed prepared fingerprint rejected at fresh comparison");check(pinned.clearCount==0 && pinned.sent.size()==3,"mismatched prepared fingerprint sends read-only queries, never3");
   Link changedTiming=new Link(ear,text){public void send(int command){super.send(command);if(command=='2'){chunks.clear();chunks.add(new ChargingProtocolTest.Chunk(clock,ChargingProtocolTest.b("EARSIDE "+ear+"CHG_TIME_NV valid, lock:0, index:0, err1:0, err2:0, err3:0, nor:0\n")));}}};
   rejects(()->ChargingFaultReset.reset(changedTiming,snapshot,value->{},v->{}),"changed timing record requires a new saved diagnostic copy");check(changedTiming.clearCount==0,"no clear when second saved record changed");
   Link claimCancel=new Link(ear,text);rejects(()->ChargingFaultReset.reset(claimCancel,snapshot,value->claimCancel.cancel=true,v->{}),"cancellation after claim sends no clear");check(claimCancel.clearCount==0,"no physical command after cancellation at claim boundary");
   Link noEffect=new Link(ear,text){public void send(int command){super.send(command);if(command=='3')didClear=false;}};
   ArrayList<JSONObject> noEffectStates=new ArrayList<>();rejects(()->ChargingFaultReset.reset(noEffect,snapshot,value->{},v->noEffectStates.add(v)),"ack without actual zero records cannot count as success");
   check(noEffectStates.stream().noneMatch(v->v.optBoolean("ramResetVerified")),"no false success for acknowledged but ineffective clear");
   Link wrongEar=new Link(ear.equals("L")?"R":"L",text);rejects(()->ChargingFaultReset.reset(wrongEar,snapshot,value->{},v->{}),"changed ear refused before mutation");check(wrongEar.clearCount==0,"wrong ear never cleared");
   Link zero=new Link(ear,cleared(ear));ChargingFaultReset.Snapshot already=ChargingFaultReset.read(zero,true);rejects(()->ChargingFaultReset.guard(already),"already-cleared history cannot be cleared again");
  }
  check(ChargingProtocol.parse('7',ChargingProtocolTest.b(cleared("L"))).getInt("mainVoltageLimitFaultCounter")==0,"existing compact parser reads zero result");
  rejects(()->ChargingProtocol.exchange(new ChargingProtocolTest.Link(),'3'),"ordinary diagnostic interface still refuses mutation");
  System.out.println("Charging fault reset passed: "+checks+" offline checks; no physical device calls");
 }
}
