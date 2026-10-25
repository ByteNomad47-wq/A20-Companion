// SPDX-License-Identifier: GPL-3.0-only
package io.github.bytenomad47.a20.repair;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Synthetic serial fixtures; no physical Bluetooth connections or device commands. */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk={35}, application=android.app.Application.class)
public final class ChargingProtocolTest {
 @org.junit.Test public void auditedOfflineCases() throws Exception { main(new String[0]); }
 static int checks;
 static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
 static byte[] b(String s){return s.getBytes(StandardCharsets.US_ASCII);}
 interface Op{void run()throws Exception;}
 static void rejects(Op op,String why)throws Exception{boolean threw=false;try{op.run();}catch(Exception e){threw=true;}check(threw,why);}
 static final String V="EARSIDE Llocal_bat_volt 3890";
 static final String T="EARSIDE LCHG_TIME_NV valid, lock:3, index:1, err1:1, err2:2, err3:3, nor:9\ni:0, t:581\n";
 static final class Chunk {long at;byte[] bytes;int pos;Chunk(long at,byte[] b){this.at=at;this.bytes=b;}}
 static class Link implements ChargingProtocol.Link {
  long clock;boolean cancel,stopAfterVoltage,wrongEar,unknown,tooLarge,silent,closed;
  List<Integer> sent=new ArrayList<>();ArrayDeque<Chunk> chunks=new ArrayDeque<>();
  public void send(int value){
   sent.add(value);String text=value=='4'?V:T;
   if(unknown)text="EARSIDE LACCOUNT_SECRET";
   if(wrongEar && value=='2')text=text.replace("EARSIDE L","EARSIDE R");
   if(silent)return;
   byte[] bytes=tooLarge?new byte[4097]:b(text);
   if(bytes.length>10){chunks.add(new Chunk(clock,Arrays.copyOf(bytes,9)));chunks.add(new Chunk(clock+200,Arrays.copyOfRange(bytes,9,bytes.length)));}
   else chunks.add(new Chunk(clock,bytes));
  }
  public int available(){Chunk c=chunks.peek();return c==null||c.at>clock?0:c.bytes.length-c.pos;}
  public int read(byte[] dst,int count){
   if(closed)return -1;Chunk c=chunks.peek();int n=Math.min(count,c.bytes.length-c.pos);System.arraycopy(c.bytes,c.pos,dst,0,n);c.pos+=n;if(c.pos==c.bytes.length)chunks.remove();return n;
  }
  public long now(){return clock;}
  public void pause(long ms){clock+=ms;if(stopAfterVoltage && !sent.isEmpty() && clock>=700)cancel=true;}
  public boolean cancelled(){return cancel;}
 }
 public static void main(String[] args)throws Exception {
  for(String name:new String[]{"Sleep A20","soundcore Sleep A20","Anker soundcore Sleep A20"})check(ChargingProtocol.supportedName(name),"recognized paired A20 name");
  for(String name:new String[]{"Sleep A20i","Sleep A20 C","A6611C","Sleep A30","someone else's device","Sleep A20 extra"})check(!ChargingProtocol.supportedName(name),"unrecognized model/name rejected");
  check(!ChargingProtocol.supportedName(null),"null name rejected");
  JSONObject v=ChargingProtocol.parse('4',b(V));check(v.getInt("cachedBatteryVoltage")==3890 && v.getString("ear").equals("L"),"cached voltage and ear parsed");
  JSONObject t=ChargingProtocol.parse('2',b(T));check(t.getInt("timingRecordLock")==3 && t.getJSONArray("samples").length()==1,"timing values parsed separately");
  check(!t.has("mainVoltageLimitFaultCounter"),"timing lock is not labeled primary voltage-limit lock");
  check(!ChargingProtocol.parse('2',b("EARSIDE RCHG_TIME_NV invalid\n")).getBoolean("recordValid"),"invalid record reported without fabrication");
  StringBuilder max=new StringBuilder("EARSIDE RCHG_TIME_NV valid, lock:-32768, index:-32768, err1:-32768, err2:-32768, err3:-32768, nor:-2147483648\n");
  for(int i=0;i<50;i++)max.append("i:").append(i).append(", t:-2147483648\n");
  JSONObject all=ChargingProtocol.parse('2',b(max.toString()));
  check(all.getJSONArray("samples").length()==50,"all fifty maximum-width timing samples fit bounded parser");
  check(b(max.toString()).length<2048,"firmware time formatter worst signed field widths remain well below4096");
  for(String s:new String[]{"local_bat_volt 3890","EARSIDE Xlocal_bat_volt 3890","EARSIDE Llocal_bat_volt 70000","EARSIDE Llocal_bat_volt -1","EARSIDE Llocal_bat_volt 3890 ACCOUNT_SECRET","EARSIDE Llocal_bat_volt 3890\0"})rejects(()->ChargingProtocol.parse('4',b(s)),"malformed/foreign voltage rejected");
  for(String s:new String[]{T+"ACCOUNT_SECRET",T+"i:0, t:2\n",T+"i:50, t:2\n",T.replace("nor:9","nor:2147483648"),T.substring(0,T.length()-1),T.replace("lock:3","lock:999999")})rejects(()->ChargingProtocol.parse('2',b(s)),"unknown/incomplete/range-invalid time record rejected");
  rejects(()->ChargingProtocol.parse('2',new byte[4097]),"parser byte limit");
  Link good=new Link();JSONObject report=new JSONObject();List<JSONObject> states=new ArrayList<>();
  ChargingProtocol.run(good,report,r->states.add(new JSONObject(r.toString())));
  check(good.sent.equals(Arrays.asList((int)'4',(int)'2')),"exact read commands4,2 only; no terminator or other bytes");
  check(report.getBoolean("complete") && report.getJSONArray("reads").length()==2,"fragmented two-stage read completes");
  check(report.isNull("mainVoltageLimitFaultCounter"),"main counter explicitly unavailable");
  check(!report.toString().contains("ACCOUNT_SECRET") && !report.has("rawReply"),"only schema values exported");
  check(states.get(0).getJSONArray("reads").length()==1 && !states.get(0).has("complete"),"progress snapshot isolated from later mutations");
  for(int command:new int[]{'1','3','5','6',0,255}){
   Link link=new Link();rejects(()->ChargingProtocol.exchange(link,command),"full-history and state-changing command unavailable");check(link.sent.isEmpty(),"forbidden command sends nothing");
  }
  Link unknown=new Link();unknown.unknown=true;JSONObject rejected=new JSONObject();rejects(()->ChargingProtocol.run(unknown,rejected,r->{}),"unexpected reply fails");
  check(unknown.sent.equals(Arrays.asList((int)'4')),"unrecognized service response stops before second query");
  check(!rejected.toString().contains("ACCOUNT_SECRET"),"unrecognized raw values never saved");
  Link pending=new Link();pending.chunks.add(new Chunk(0,b("pending")));rejects(()->ChargingProtocol.exchange(pending,'4'),"unsolicited data refused");check(pending.sent.isEmpty(),"no command on dirty stream");
  Link cancel=new Link();cancel.cancel=true;rejects(()->ChargingProtocol.run(cancel,new JSONObject(),r->{}),"pre-cancellation honored");check(cancel.sent.isEmpty(),"cancelled run sends nothing");
  Link mid=new Link();mid.stopAfterVoltage=true;rejects(()->ChargingProtocol.run(mid,new JSONObject(),r->{}),"cancellation during read honored");check(mid.sent.equals(Arrays.asList((int)'4')),"cancellation prevents further queries");
  Link overflow=new Link();overflow.tooLarge=true;rejects(()->ChargingProtocol.run(overflow,new JSONObject(),r->{}),"oversized reply fails");check(overflow.sent.size()==1,"oversized reply stops sequence");
  Link huge=new Link(){public int available(){int n=super.available();return n>0 && clock>=200?Integer.MAX_VALUE:n;}};
  rejects(()->ChargingProtocol.exchange(huge,'4'),"available-count overflow refused without allocating");
  Link timeout=new Link();timeout.silent=true;rejects(()->ChargingProtocol.exchange(timeout,'4'),"reply timeout");check(timeout.clock==ChargingProtocol.READ_MS,"timeout bounded by monotonic clock");
  Link closed=new Link();closed.closed=true;rejects(()->ChargingProtocol.exchange(closed,'4'),"closed read fails");
  Link ear=new Link();ear.wrongEar=true;JSONObject mismatch=new JSONObject();rejects(()->ChargingProtocol.run(ear,mismatch,r->{}),"changed ear rejected");check(!mismatch.toString().contains("\"ear\":\"R\""),"mismatched sample not published");
  System.out.println("Charging protocol passed: "+checks+" checks (synthetic serial fixtures only)");
 }
}
