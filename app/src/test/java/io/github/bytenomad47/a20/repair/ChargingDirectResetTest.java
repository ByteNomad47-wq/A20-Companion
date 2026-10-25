// SPDX-License-Identifier: GPL-3.0-only
package io.github.bytenomad47.a20.repair;
import org.json.*;
import org.junit.*;
import static org.junit.Assert.*;
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk={35}, application=android.app.Application.class)
public final class ChargingDirectResetTest {
 static void merge(JSONObject out,JSONObject value)throws Exception {for(java.util.Iterator<String> keys=value.keys();keys.hasNext();){String k=keys.next();out.put(k,value.get(k));}}
 @Test public void resetWithoutMainReadUsesOneClaimAndNeverClaimsMainZero()throws Exception {
  for(String ear:new String[]{"L","R"}){
   ChargingFaultResetTest.Link link=new ChargingFaultResetTest.Link(ear,"EARSIDE "+ear);JSONObject out=new JSONObject();int[] claims={0};
   ChargingDirectReset.run(link,ear,e->{assertEquals(0,link.clearCount);assertEquals(ear,e);claims[0]++;},v->merge(out,v));
   assertEquals(java.util.Arrays.asList((int)'4',(int)'2',(int)'3',(int)'2'),link.sent);
   assertEquals(1,claims[0]);assertEquals(1,link.clearCount);assertTrue(out.getBoolean("clearAcknowledged"));assertTrue(out.getBoolean("timingRecordZero"));
   assertTrue(out.isNull("mainVoltageLimitFaultCounter"));assertFalse(out.getBoolean("mainCountReadBeforeReset"));assertFalse(out.getBoolean("ramResetVerified"));assertFalse(out.getBoolean("persistenceVerified"));
  }
 }
 @Test public void noMainCountDoesNotDisableWrongEarOrFailedClaimGuards()throws Exception {
  ChargingFaultResetTest.Link wrong=new ChargingFaultResetTest.Link("R","unused");
  try{ChargingDirectReset.run(wrong,"L",e->{fail();},v->{});fail();}catch(java.io.IOException expected){}
  assertEquals(0,wrong.clearCount);
  ChargingFaultResetTest.Link failed=new ChargingFaultResetTest.Link("L","unused");
  try{ChargingDirectReset.run(failed,"L",e->{throw new java.io.IOException();},v->{});fail();}catch(java.io.IOException expected){}
  assertEquals(0,failed.clearCount);
 }
}
