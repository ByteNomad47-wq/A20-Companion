// SPDX-License-Identifier: GPL-3.0-only
// Reused from the author’s generic A20 reset research, version 1.15; see docs/REPAIR.md.
package io.github.bytenomad47.a20.repair;

import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.*;

/** Explicit, guarded one-use service-record clear. Ordinary reader still rejects3. */
final class ChargingFaultReset {
    static final class Refused extends IOException {
        final String reason;
        Refused(String reason){super("Reset eligibility: "+reason);this.reason=reason;}
    }
    interface Ledger {void claim(String ear)throws Exception;}
    interface Sink {void update(JSONObject value)throws Exception;}
    static final class Snapshot {
        final String ear,hash,timingHash;final JSONObject data;
        Snapshot(String ear,String hash,String timingHash,JSONObject data){this.ear=ear;this.hash=hash;this.timingHash=timingHash;this.data=data;}
    }
    static Snapshot read(ChargingProtocol.Link link,boolean timing)throws Exception {return read(link,timing,value->{});}
    static Snapshot read(ChargingProtocol.Link link,boolean timing,Sink sink)throws Exception {
        sink.update(new JSONObject().put("readStage","cached-voltage4"));
        JSONObject voltage=ChargingProtocol.parse('4',ChargingProtocol.exchange(link,'4'));
        sink.update(new JSONObject().put("lastVoltage",voltage).put("readStage","compact-main7"));
        byte[] wire=ChargingProtocol.exchange(link,'7');JSONObject main=ChargingProtocol.parse('7',wire);
        String ear=voltage.getString("ear");
        if(!ear.equals(main.getString("ear")) || !"compact-history-1.13".equals(main.optString("diagnosticFormat")) || !main.optBoolean("historyComplete"))throw new IOException("Verified compact-history firmware required on same ear");
        JSONObject data=new JSONObject().put("ear",ear).put("voltage",voltage).put("main",main);
        sink.update(new JSONObject().put("lastMain",main));
        String timingHash=null;
        if(timing){sink.update(new JSONObject().put("readStage","timing2"));byte[] timingWire=ChargingProtocol.exchange(link,'2');timingHash=RepairHash.hash(timingWire,"SHA-256");JSONObject time=ChargingProtocol.parse('2',timingWire);
            if(!ear.equals(time.getString("ear")))throw new IOException("Timing ear changed");data.put("timing",time);sink.update(new JSONObject().put("lastTiming",time));}
        return new Snapshot(ear,RepairHash.hash(wire,"SHA-256"),timingHash,data);
    }
    static void guard(Snapshot s)throws Exception {ChargingFaultEligibility.check(s.data);}
    static byte[] clear(ChargingProtocol.Link link,String ear)throws Exception {
        if(link.cancelled())throw new IOException("Cancelled before clear");
        if(link.available()!=0)throw new IOException("Unexpected pending data before clear");
        link.send('3'); // ONLY state-changing command; called after ledger claim and explicit UI consent.
        byte[] expected=("EARSIDE "+ear+"CLEAR_CHARGE_INF").getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();long start=link.now(),last=start;
        while(link.now()-start<ChargingProtocol.READ_MS){
            if(link.cancelled())throw new IOException("Reset outcome unknown after cancellation");
            int available=link.available();
            if(available>0){
                if(available>expected.length-bytes.size())throw new IOException("Unexpected reset acknowledgement length");
                byte[] chunk=new byte[expected.length];int n=link.read(chunk,available);
                if(n<0)throw new IOException("Reset stream closed");
                if(n>0){bytes.write(chunk,0,n);last=link.now();byte[] actual=bytes.toByteArray();
                    for(int i=0;i<actual.length;i++)if(actual[i]!=expected[i])throw new IOException("Unexpected reset acknowledgement");}
            }else if(bytes.size()==expected.length && link.now()-last>=ChargingProtocol.QUIET_MS)return bytes.toByteArray();
            else link.pause(25);
        }
        throw new IOException("Reset acknowledgement timeout; do not retry");
    }
    static void verifyCleared(Snapshot s,String ear)throws Exception {
        if(!s.ear.equals(ear))throw new IOException("Post-reset ear changed");JSONObject h=s.data.getJSONObject("main");
        for(String key:new String[]{"mainVoltageLimitFaultCounter","index","error1","error2","error3","normalCount"})if(h.getLong(key)!=0)throw new IOException("Charging record not fully cleared");
        JSONArray records=h.getJSONArray("storedHistory");
        for(int i=0;i<records.length();i++){
            JSONObject r=records.getJSONObject(i);if(r.getLong("rawChargeTime")!=0)throw new IOException("History not cleared");
            JSONArray samples=r.getJSONArray("storedSamples");for(int j=0;j<samples.length();j++){
                JSONObject q=samples.getJSONObject(j);if(q.getLong("rawTime")!=0 || q.getInt("rawVoltage")!=0)throw new IOException("History samples not cleared");}
        }
        JSONObject t=s.data.getJSONObject("timing");
        if(!t.optBoolean("recordValid"))throw new IOException("Timing record invalid after clear");
        for(String key:new String[]{"timingRecordLock","index","error1","error2","error3","normalCount"})if(t.getLong(key)!=0)throw new IOException("Timing record not cleared");
        if(t.getJSONArray("samples").length()!=0)throw new IOException("Timing samples not cleared");
    }
    static void reset(ChargingProtocol.Link link,Snapshot prepared,Ledger ledger,Sink sink)throws Exception {
        guard(prepared);Snapshot current=read(link,true,sink);guard(current);
        if(!current.ear.equals(prepared.ear) || !current.hash.equals(prepared.hash) || !current.timingHash.equals(prepared.timingHash))throw new IOException("Prepared ear or history changed");
        if(link.cancelled())throw new IOException("Cancelled before claim");
        sink.update(new JSONObject().put("state","Fresh checks passed; about to claim single reset attempt").put("verifiedBefore",current.data));
        ledger.claim(current.ear); // Must persist before ANY command3, including ambiguous transport failures.
        sink.update(new JSONObject().put("state","One-time attempt claimed; reset outcome unverified").put("attemptClaimed",true));
        clear(link,current.ear);
        sink.update(new JSONObject().put("state","Service clear acknowledged; checking actual records").put("clearAcknowledged",true));
        Snapshot after=read(link,true,sink);sink.update(new JSONObject().put("after",after.data));verifyCleared(after,current.ear);
        sink.update(new JSONObject().put("state","RAM records verified cleared. Save report; persistence and charging repair unverified.")
            .put("ramResetVerified",true).put("persistenceVerified",false).put("chargingFixVerified",false));
    }
}
