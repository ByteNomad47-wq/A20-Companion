// SPDX-License-Identifier: GPL-3.0-only
package io.github.bytenomad47.a20.repair;
import org.json.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
/** Synthetic eligibility tests. No Bluetooth/network or device operations. */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk={35}, application=android.app.Application.class)
public final class ChargingFaultEligibilityTest {
 @org.junit.Test public void auditedOfflineCases() throws Exception { main(new String[]{"src/test/resources/repair-fixtures/case-left-history.json","src/test/resources/repair-fixtures/case-right-history.json"}); }
 static int checks;
 static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
 interface Edit {void apply(JSONObject d)throws Exception;}
 static JSONObject copy(JSONObject d)throws Exception {return new JSONObject(d.toString());}
 static JSONObject main(JSONObject d)throws Exception {return d.getJSONObject("main");}
 static JSONObject row(JSONObject d,int n)throws Exception {return main(d).getJSONArray("storedHistory").getJSONObject(n);}
 static JSONObject sample(JSONObject d,int n,int s)throws Exception {return row(d,n).getJSONArray("storedSamples").getJSONObject(s);}
 static void bad(JSONObject d,Edit edit,String why)throws Exception {
  JSONObject changed=copy(d);edit.apply(changed);boolean refused=false;
  try{ChargingFaultEligibility.check(changed);}catch(Exception e){refused=true;}
  check(refused,why);
 }
 public static void main(String[] args)throws Exception {
  for(String file:args){JSONObject h=new JSONObject(new String(Files.readAllBytes(Paths.get(file)),StandardCharsets.UTF_8)).getJSONArray("reads").getJSONObject(1).getJSONObject("result");
   ChargingFaultReset.Snapshot original=ChargingFaultReset.read(new ChargingFaultResetTest.Link(h.getString("ear"),ChargingFaultResetTest.wire(h)),true);
   JSONObject d=original.data;ChargingFaultEligibility.check(d);check(true,"both case profiles accepted");
   JSONObject translated=copy(d);JSONArray rows=main(translated).getJSONArray("storedHistory");
   for(int n=0;n<20;n++){JSONObject row=rows.getJSONObject(n);if(row.getBoolean("recordMarkedEmpty"))continue;
    row.put("rawChargeTime",row.getLong("rawChargeTime")+123456);
    for(int i=0;i<8;i++){JSONObject q=row.getJSONArray("storedSamples").getJSONObject(i);q.put("rawTime",q.getLong("rawTime")+123456).put("rawVoltage",q.getInt("rawVoltage")+4);}}
   ChargingFaultEligibility.check(translated);check(!ChargingFaultResetTest.wire(main(translated)).equals(ChargingFaultResetTest.wire(h)),"different history accepted without original fingerprint");
   JSONObject wrapped=copy(d);for(int n=0;n<20;n++){JSONObject w=row(wrapped,n);if(w.getBoolean("recordMarkedEmpty"))continue;w.put("rawChargeTime",(int)(w.getLong("rawChargeTime")+2146000000L));for(int i=0;i<8;i++){JSONObject q=sample(wrapped,n,i);q.put("rawTime",(int)(q.getLong("rawTime")+2146000000L));}}
   ChargingFaultEligibility.check(wrapped);check(true,"signed 32-bit sample clock wrap accepted");
   String syntheticWire=ChargingFaultResetTest.wire(main(translated));
   ChargingFaultReset.Snapshot synthetic=ChargingFaultReset.read(new ChargingFaultResetTest.Link(h.getString("ear"),syntheticWire),true);
   ChargingFaultResetTest.Link syntheticReset=new ChargingFaultResetTest.Link(h.getString("ear"),syntheticWire);
   ChargingFaultReset.reset(syntheticReset,synthetic,ear->{},state->{});check(syntheticReset.clearCount==1,"generic synthetic history completes exactly one simulated reset");
   bad(d,x->x.put("ear","X"),"invalid ear");bad(d,x->x.getJSONObject("voltage").put("ear","X"),"mixed ears");
   bad(d,x->main(x).put("diagnosticFormat","stock"),"unsupported formatter");bad(d,x->main(x).put("historyComplete",false),"partial history");
   bad(d,x->main(x).put("recordValid",false),"invalid marker");
   bad(d,x->x.getJSONObject("voltage").put("cachedBatteryVoltage",4001),"cached voltage too high");bad(d,x->x.getJSONObject("voltage").put("cachedBatteryVoltage",3399),"cached voltage too low");
   bad(d,x->main(x).put("mainVoltageLimitFaultCounter",4),"other main count");bad(d,x->main(x).put("error2",2),"other lock count");bad(d,x->main(x).put("error3",1),"larger-fault count");
   bad(d,x->x.getJSONObject("timing").put("timingRecordLock",1),"timing lock");bad(d,x->x.getJSONObject("timing").put("recordValid",false),"invalid timing");
   bad(d,x->main(x).put("index",(main(x).getInt("index")+1)%20),"ring index inconsistent");bad(d,x->main(x).put("normalCount",-1),"negative lifetime count");
   bad(d,x->main(x).getJSONArray("storedHistory").remove(19),"missing physical slot");bad(d,x->row(x,0).put("slot",1),"slot order");
   bad(d,x->row(x,0).getJSONArray("storedSamples").remove(9),"missing stored pair");bad(d,x->sample(x,0,0).put("slot",1),"pair order");
   bad(d,x->row(x,0).put("recordMarkedEmpty",true),"empty flag contradicts clock");bad(d,x->row(x,0).put("rawChargeTime",0),"missing charge clock");
   bad(d,x->sample(x,0,8).put("rawVoltage",4000),"unsupported ninth sample");bad(d,x->sample(x,0,0).put("rawVoltage",4400),"stored voltage range");
   bad(d,x->sample(x,0,0).put("rawTime",2147483648L),"clock out of int32");bad(d,x->sample(x,0,0).put("rawTime",sample(x,0,0).getLong("rawTime")+20000),"wrong first-sample delay");
   bad(d,x->sample(x,0,4).put("rawTime",sample(x,0,4).getLong("rawTime")+15000),"wrong sampling cadence");
   bad(d,x->{int[] v={4050,4045,4040,4035,4020,4015,4010,4005};for(int i=0;i<8;i++)sample(x,0,i).put("rawVoltage",v[i]);},"four-interval drop30 refused despite gradual adjacent steps");
   bad(d,x->sample(x,0,1).put("rawVoltage",sample(x,0,0).getInt("rawVoltage")+40),"abrupt change");
   int last=(main(d).getInt("index")+19)%20;
   bad(d,x->{int v=sample(x,last,0).getInt("rawVoltage");for(int i=0;i<8;i++)sample(x,last,i).put("rawVoltage",v);},"all lock events and most recent qualifying event required");
   // Move the latest qualifying event one physical slot earlier without changing totals.
   bad(d,x->{JSONArray a=main(x).getJSONArray("storedHistory");int other=(last+19)%20;JSONObject one=a.getJSONObject(last),two=a.getJSONObject(other);a.put(last,two.put("slot",last));a.put(other,one.put("slot",other));},"last-written event must qualify");
   if(h.getString("ear").equals("L")){
    bad(d,x->sample(x,19,0).put("rawVoltage",4000),"empty slots cannot contain data");
    bad(d,x->main(x).put("error1",8).put("normalCount",4),"unwrapped classification must reproduce counters");
   }
  }
  System.out.println("Generic eligibility passed: "+checks+" offline checks; no device operations");
 }
}
