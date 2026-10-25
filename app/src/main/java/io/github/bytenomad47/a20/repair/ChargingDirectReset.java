// SPDX-License-Identifier: GPL-3.0-only
package io.github.bytenomad47.a20.repair;

import java.io.IOException;
import org.json.*;

/** Confirmed manual reset on the examined official or compact-diagnostic 01.57.
 * No selector7 or main-count read is required; no command is retried. */
final class ChargingDirectReset {
    static JSONObject timing(ChargingProtocol.Link link,String ear)throws Exception {
        JSONObject t=ChargingProtocol.parse('2',ChargingProtocol.exchange(link,'2'));
        if(!ear.equals(t.getString("ear")) || !t.optBoolean("recordValid") || t.getInt("timingRecordLock")!=0)
            throw new IOException("Timing record outside tested reset profile");
        return t;
    }
    static void run(ChargingProtocol.Link link,String ear,ChargingFaultReset.Ledger journal,ChargingFaultReset.Sink sink)throws Exception {
        if(!"L".equals(ear) && !"R".equals(ear))throw new IOException("Invalid reset ear");
        sink.update(new JSONObject().put("readStage","cached-voltage4").put("mainVoltageLimitFaultCounter",JSONObject.NULL)
            .put("mainCountReadBeforeReset",false).put("ramResetVerified",false).put("persistenceVerified",false).put("chargingFixVerified",false));
        JSONObject v=ChargingProtocol.parse('4',ChargingProtocol.exchange(link,'4'));
        int mv=v.getInt("cachedBatteryVoltage");
        if(!ear.equals(v.getString("ear")))throw new IOException("Reset ear changed");
        if(mv<3400 || mv>4200)throw new IOException("Voltage reading outside tested reset range");
        sink.update(new JSONObject().put("lastVoltage",v).put("readStage","timing2"));
        JSONObject before=timing(link,ear);
        sink.update(new JSONObject().put("beforeTiming",before).put("state","Identity checks complete; saving this manual attempt"));
        if(link.cancelled())throw new IOException("Cancelled before reset attempt");
        journal.claim(ear); // Durably record this unique UI request BEFORE the only clear byte.
        sink.update(new JSONObject().put("attemptClaimed",true).put("state","Manual attempt recorded; outcome unverified"));
        ChargingFaultReset.clear(link,ear);
        sink.update(new JSONObject().put("clearAcknowledged",true).put("state","Reset acknowledged; main count unverified"));
        JSONObject after=timing(link,ear);boolean zero=after.getJSONArray("samples").length()==0;
        for(String key:new String[]{"timingRecordLock","index","error1","error2","error3","normalCount"})zero &= after.getLong(key)==0;
        sink.update(new JSONObject().put("afterTiming",after).put("timingRecordZero",zero)
            .put("state","Reset acknowledged; timing readback complete. Main count and power-loss persistence unverified."));
    }
}
