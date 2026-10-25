// SPDX-License-Identifier: GPL-3.0-only
package io.github.bytenomad47.a20.repair;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.json.*;
/** Complete fixed-slot histories and fragmented serial fixtures; no sockets. */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk={35}, application=android.app.Application.class)
public final class ChargingHistoryTest {
 @org.junit.Test public void auditedOfflineCases() throws Exception { main(new String[0]); }
 static int checks;static void check(boolean c,String why){checks++;if(!c)throw new AssertionError(why);}
 interface Op{void run()throws Exception;}static void rejects(Op op,String why)throws Exception{boolean bad=false;try{op.run();}catch(Exception e){bad=true;}check(bad,why);}
 static String frame(String ear,boolean maximum){
  StringBuilder b=new StringBuilder("EARSIDE "+ear+"CHG_DATA_NV lock:"+(maximum?65535:3)+", index:"+(maximum?65535:18)+", err1:"+(maximum?65535:12)+", err2:"+(maximum?65535:3)+", err3:"+(maximum?65535:0)+", nor:"+(maximum?Integer.MIN_VALUE:26)+"\n");
  for(int slot=0;slot<20;slot++){
   b.append("ct[").append(slot).append("]:").append(maximum?Integer.MIN_VALUE:slot<12?1000+slot:0).append('\n');
   for(int sample=0;sample<10;sample++)b.append(maximum?Integer.MIN_VALUE:slot<12?2000+sample*300:0).append(',').append(maximum?65535:slot<12?4180-sample*6:0).append('\n');
  }
  return b.toString();
 }
 public static void main(String[] args)throws Exception{
  for(String ear:new String[]{"L","R"}){
   JSONObject r=ChargingProtocol.parse('7',ChargingProtocolTest.b(frame(ear,false)));
   check(r.getString("ear").equals(ear) && r.getInt("mainVoltageLimitFaultCounter")==3,"ear and main counter preserved");
   check(r.getBoolean("historyComplete") && r.getJSONArray("storedHistory").length()==20,"all20 physical slots captured");
   for(int slot=0;slot<20;slot++){
    JSONObject row=r.getJSONArray("storedHistory").getJSONObject(slot);
    check(row.getInt("slot")==slot && row.getJSONArray("storedSamples").length()==10,"ordered slot and ten values");
    check(row.getBoolean("recordMarkedEmpty")== (slot>=12),"unused slots are explicitly marked, not sessions");
   }
   check(!r.toString().contains("rawReply"),"no unfiltered text retained");
  }
  String maximum=frame("R",true);check(maximum.getBytes(StandardCharsets.US_ASCII).length==4068,"independent maximum-width fixture includes ear marker and fits4096");
  JSONObject max=ChargingProtocol.parse('7',ChargingProtocolTest.b(maximum));
  check(max.getInt("mainVoltageLimitFaultCounter")==65535,"unsigned main counter maximum");
  check(max.getJSONArray("storedHistory").getJSONObject(19).getJSONArray("storedSamples").getJSONObject(9).getLong("rawTime")==Integer.MIN_VALUE,"last maximum-width time retained");
  check(max.getJSONArray("storedHistory").getJSONObject(19).getJSONArray("storedSamples").getJSONObject(9).getInt("rawVoltage")==65535,"last maximum-width voltage retained");
  check(max.toString().getBytes(StandardCharsets.UTF_8).length<32768,"largest parsed result fits local atomic report cap");
  String valid=frame("L",false);
  for(String bad:new String[]{valid.substring(0,valid.length()-1),valid.replace("ct[19]","ct[18]"),valid.replace("ct[19]","ct[20]"),valid.replace("2000,4180","2000,65536"),valid.replace("2000,4180","2147483648,4180"),valid.replace("ct[1]:1001","ct[1]:-2147483649"),valid.replace("2000,4180\n",""),valid+"ACCOUNT_SECRET",valid.replace("CHG_DATA_NV","CHG_INFO_NV")})rejects(()->ChargingProtocol.parse('7',ChargingProtocolTest.b(bad)),"malformed/incomplete/foreign/old history refused");
  JSONObject invalid=ChargingProtocol.parse('7',ChargingProtocolTest.b("EARSIDE RCHG_DATA_NV invalid"));
  check(!invalid.getBoolean("recordValid") && !invalid.has("mainVoltageLimitFaultCounter"),"invalid record does not fabricate counter or history");
  final int boundary=valid.indexOf("ct[19]");
  ChargingProtocolTest.Link fragmented=new ChargingProtocolTest.Link(){public void send(int command){
   if(command!='7'){super.send(command);return;}
   sent.add(command);chunks.add(new ChargingProtocolTest.Chunk(clock,ChargingProtocolTest.b(valid.substring(0,boundary))));
   chunks.add(new ChargingProtocolTest.Chunk(clock+2000,ChargingProtocolTest.b(valid.substring(boundary))));
  }};
  JSONObject report=new JSONObject();ChargingProtocol.run(fragmented,report,value->{},true);
  check(report.getBoolean("voltageHistoryRetrieved"),"only complete fixed-count history promoted");
  check(fragmented.clock>=2000,"quiet gap after19 records cannot truncate history as complete");
  check(fragmented.sent.equals(Arrays.asList((int)'4',(int)'7')),"only exact4/7 read bytes sent");
  ChargingProtocolTest.Link partial=new ChargingProtocolTest.Link(){public void send(int command){if(command!='7'){super.send(command);return;}sent.add(command);chunks.add(new ChargingProtocolTest.Chunk(clock,ChargingProtocolTest.b(valid.substring(0,boundary))));}};
  JSONObject failed=new JSONObject();rejects(()->ChargingProtocol.run(partial,failed,value->{},true),"partial history times out without automatic retry");
  check(!failed.optBoolean("voltageHistoryRetrieved") && failed.isNull("mainVoltageLimitFaultCounter"),"partial header never promoted as a complete main read");
  check(!failed.toString().contains("ct["),"partial raw history never exported");
  System.out.println("Charging compact history passed: "+checks+" checks (offline serial fixtures only)");
 }
}
