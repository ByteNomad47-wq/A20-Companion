// SPDX-License-Identifier: GPL-3.0-only
package io.github.bytenomad47.a20.repair;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.json.*;

/** Explicit research-only one-extra-attempt flow. Normal repair eligibility is unchanged. */
final class ChargingResetExperiment {
    static void guard(ChargingFaultReset.Snapshot s)throws Exception {ChargingFaultEligibility.checkExperiment(s.data);}
    static void custom(ChargingProtocol.Link link,ChargingFaultReset.Snapshot prepared,ChargingFaultReset.Ledger ledger,ChargingFaultReset.Sink sink)throws Exception {
        guard(prepared);
        ChargingFaultReset.Snapshot fresh=ChargingFaultReset.read(link,true,sink);guard(fresh);
        if(!fresh.ear.equals(prepared.ear) || !fresh.hash.equals(prepared.hash) || !fresh.timingHash.equals(prepared.timingHash))throw new IOException("Experiment baseline changed");
        sink.update(new JSONObject().put("verifiedBefore",fresh.data).put("experiment","custom-zero-readback-v1").put("persistenceVerified",false));
        if(link.cancelled())throw new IOException("Cancelled before experiment claim");
        ledger.claim(fresh.ear);
        sink.update(new JSONObject().put("attemptClaimed",true).put("state","Experiment attempt claimed; outcome unverified"));
        ChargingFaultReset.clear(link,fresh.ear);
        sink.update(new JSONObject().put("clearAcknowledged",true));
        ChargingFaultReset.Snapshot after=ChargingFaultReset.read(link,true,sink);
        sink.update(new JSONObject().put("after",after.data));ChargingFaultReset.verifyCleared(after,fresh.ear);
        sink.update(new JSONObject().put("ramResetVerified",true).put("persistenceVerified",false).put("chargingFixVerified",false)
            .put("state","Experiment: all records read back zero in RAM. Save report, then read again after a normal restart before reflashing. Persistence unverified."));
    }
    /** Only accepts the exact nine-byte stock selector7 marker, never the unsafe selector1. */
    static void stockMarker(ChargingProtocol.Link link,String ear)throws Exception {
        if(!"L".equals(ear) && !"R".equals(ear))throw new IOException("Invalid experiment ear");
        if(link.cancelled() || link.available()!=0)throw new IOException("Experiment stream not idle");
        link.send('7');byte[] expected=("EARSIDE "+ear).getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream out=new ByteArrayOutputStream();long start=link.now(),last=start;
        while(link.now()-start<ChargingProtocol.READ_MS){
            if(link.cancelled())throw new IOException("Experiment cancelled");
            int count=link.available();
            if(count>0){
                if(count>expected.length-out.size())throw new IOException("Stock marker unavailable; diagnostic/unknown firmware is not the official test target");
                byte[] block=new byte[expected.length];int n=link.read(block,count);
                if(n<0)throw new IOException("Experiment stream closed");
                if(n>0){out.write(block,0,n);last=link.now();byte[] got=out.toByteArray();
                    for(int i=0;i<got.length;i++)if(got[i]!=expected[i])throw new IOException("Stock ear marker mismatch");}
            }else if(out.size()==expected.length && link.now()-last>=ChargingProtocol.QUIET_MS)return;
            else link.pause(25);
        }
        throw new IOException("Stock marker timeout");
    }
    static JSONObject timing(ChargingProtocol.Link link,String ear)throws Exception {
        JSONObject t=ChargingProtocol.parse('2',ChargingProtocol.exchange(link,'2'));
        if(!ear.equals(t.getString("ear")) || !t.optBoolean("recordValid") || t.getInt("timingRecordLock")!=0)throw new IOException("Unsupported experiment timing record");return t;
    }
    static void stock(ChargingProtocol.Link link,String ear,ChargingFaultReset.Ledger ledger,ChargingFaultReset.Sink sink)throws Exception {
        JSONObject v=ChargingProtocol.parse('4',ChargingProtocol.exchange(link,'4'));
        int mv=v.getInt("cachedBatteryVoltage");
        if(!ear.equals(v.getString("ear")) || mv<3400 || mv>4200)throw new IOException("Experiment identity/voltage outside profile");
        sink.update(new JSONObject().put("lastVoltage",v));stockMarker(link,ear);
        JSONObject before=timing(link,ear);
        sink.update(new JSONObject().put("experiment","official-acknowledgement-v1").put("lastTiming",before)
            .put("stockMarkerObserved",true).put("mainVoltageLimitFaultCounter",JSONObject.NULL)
            .put("ramResetVerified",false).put("persistenceVerified",false).put("chargingFixVerified",false)
            .put("state","Stock-compatible marker observed; main count unavailable. About to claim one experiment attempt."));
        if(link.cancelled())throw new IOException("Cancelled before experiment claim");
        ledger.claim(ear);
        sink.update(new JSONObject().put("attemptClaimed",true).put("state","Official test attempt claimed; result unverified"));
        ChargingFaultReset.clear(link,ear);
        sink.update(new JSONObject().put("clearAcknowledged",true).put("state","Reset acknowledged; main count UNVERIFIED. Save report and inspect after diagnostic reinstall. Do not resend."));
        JSONObject after=timing(link,ear);boolean zero=true;
        for(String key:new String[]{"timingRecordLock","index","error1","error2","error3","normalCount"})zero &= after.getLong(key)==0;
        zero &= after.getJSONArray("samples").length()==0;
        sink.update(new JSONObject().put("afterTiming",after).put("timingRecordZero",zero)
            .put("state","Reset acknowledged; main count UNVERIFIED. Timing record "+(zero?"read back zero":"did not read back zero")+". Save report; do not resend. Reflashing is an intervening step, not immediate main-count verification."));
    }
}
