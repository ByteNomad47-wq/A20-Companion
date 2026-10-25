package io.github.bytenomad47.a20.repair;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;
/** Sanitized numeric owner baselines, simulated links only. */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk={35}, application=android.app.Application.class)
public final class ChargingResetExperimentTest {
 static void merge(JSONObject out,JSONObject value)throws Exception {for(java.util.Iterator<String> keys=value.keys();keys.hasNext();){String k=keys.next();out.put(k,value.get(k));}}
 static int checks;interface Op{void run()throws Exception;}
 static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
 static void reject(Op op,String why)throws Exception{boolean bad=false;try{op.run();}catch(Exception e){bad=true;}check(bad,why);}
 static final class Link extends ChargingFaultResetTest.Link {
  boolean stock,badMarker,badTimingAfter;int voltage;
  Link(String ear,String history,int voltage){super(ear,history);this.voltage=voltage;}
  public void send(int c){
   if(c=='4'){sent.add(c);chunks.add(new ChargingProtocolTest.Chunk(clock,ChargingProtocolTest.b("EARSIDE "+ear+"local_bat_volt "+voltage)));}
   else if(c=='7' && stock){sent.add(c);chunks.add(new ChargingProtocolTest.Chunk(clock,ChargingProtocolTest.b("EARSIDE "+(badMarker?(ear.equals("L")?"R":"L"):ear))));}
   else {super.send(c);if(c=='2' && didClear && badTimingAfter){chunks.clear();chunks.add(new ChargingProtocolTest.Chunk(clock,ChargingProtocolTest.b("BROKEN")));}}
  }
 }
 public static void main(String[] args)throws Exception {
  String root=args.length==0?"tests/fixtures":"src/test/resources/repair-fixtures";
  for(String side:new String[]{"left","right"}){
   JSONObject d=new JSONObject(new String(Files.readAllBytes(Paths.get(root,"experiment-"+side+".json")),StandardCharsets.UTF_8));
   String ear=d.getString("ear"),wire=ChargingFaultResetTest.wire(d.getJSONObject("main"));int voltage=d.getJSONObject("voltage").getInt("cachedBatteryVoltage");
   Link prepare=new Link(ear,wire,voltage);ChargingFaultReset.Snapshot baseline=ChargingFaultReset.read(prepare,true);
   ChargingResetExperiment.guard(baseline);reject(()->ChargingFaultReset.guard(baseline),"normal three-fault guard stays closed");
   Link custom=new Link(ear,wire,voltage);final int[] claims={0};final JSONObject out=new JSONObject();
   ChargingResetExperiment.custom(custom,baseline,e->{check(custom.clearCount==0,"claim before3");claims[0]++;},v->merge(out,v));
   check(custom.clearCount==1 && claims[0]==1,"exactly one custom attempt");check(out.getBoolean("ramResetVerified") && !out.getBoolean("persistenceVerified"),"zero in RAM only");
   check(out.getJSONObject("after").getJSONObject("main").getInt("mainVoltageLimitFaultCounter")==0,"actual zero readback");
   Link failClaim=new Link(ear,wire,voltage);reject(()->ChargingResetExperiment.custom(failClaim,baseline,e->{throw new java.io.IOException();},v->{}),"failed claim");check(failClaim.clearCount==0,"failed durable claim never sends3");
   Link changed=new Link(ear,wire.replace("nor:"+d.getJSONObject("main").getInt("normalCount"),"nor:99"),voltage);reject(()->ChargingResetExperiment.custom(changed,baseline,e->{},v->{}),"changed baseline");check(changed.clearCount==0,"changed records never clear");
   Link lost=new Link(ear,wire,voltage);lost.loseAck=true;reject(()->ChargingResetExperiment.custom(lost,baseline,e->{},v->{}),"lost custom ack");check(lost.clearCount==1,"lost ack no retry");
   Link stock=new Link(ear,wire,voltage);stock.stock=true;JSONObject stockOut=new JSONObject();
   ChargingResetExperiment.stock(stock,ear,e->{check(stock.clearCount==0,"stock claim before3");},v->merge(stockOut,v));
   check(stock.clearCount==1,"stock one clear");check(stock.sent.equals(Arrays.asList((int)'4',(int)'7',(int)'2',(int)'3',(int)'2')),"stock only bounded4/7/2/3/2");
   check(stockOut.getBoolean("clearAcknowledged") && stockOut.isNull("mainVoltageLimitFaultCounter") && !stockOut.getBoolean("ramResetVerified") && !stockOut.getBoolean("persistenceVerified"),"never invent main count zero on stock");
   check(stockOut.getBoolean("timingRecordZero"),"timing zero is distinct evidence");
   Link diagnostic=new Link(ear,wire,voltage);reject(()->ChargingResetExperiment.stock(diagnostic,ear,e->{},v->{}),"diagnostic reply cannot pass stock marker probe");check(diagnostic.clearCount==0,"diagnostic firmware rejected before stock reset");
   Link wrong=new Link(ear,wire,voltage);wrong.stock=true;wrong.badMarker=true;reject(()->ChargingResetExperiment.stock(wrong,ear,e->{},v->{}),"wrong marker");check(wrong.clearCount==0,"wrong ear no clear");
   Link pending=new Link(ear,wire,voltage);pending.stock=true;pending.chunks.add(new ChargingProtocolTest.Chunk(0,ChargingProtocolTest.b("unsolicited")));reject(()->ChargingResetExperiment.stock(pending,ear,e->{},v->{}),"dirty stream");check(pending.clearCount==0,"dirty stream no clear");
   Link ackOnly=new Link(ear,wire,voltage);ackOnly.stock=true;ackOnly.badTimingAfter=true;JSONObject partial=new JSONObject();reject(()->ChargingResetExperiment.stock(ackOnly,ear,e->{},v->merge(partial,v)),"followup failure");check(partial.getBoolean("clearAcknowledged") && partial.isNull("mainVoltageLimitFaultCounter"),"ack retained even if timing verification fails");
   Link stockLost=new Link(ear,wire,voltage);stockLost.stock=true;stockLost.loseAck=true;reject(()->ChargingResetExperiment.stock(stockLost,ear,e->{},v->{}),"lost stock ack");check(stockLost.clearCount==1,"stock lost ack never resent");
  }
  System.out.println("Native reset experiment passed: "+checks+" offline checks");
 }
 @org.junit.Test public void engine()throws Exception {main(new String[]{"native"});}
}
