// SPDX-License-Identifier: GPL-3.0-only
// Reused from the author’s generic A20 reset research, version 1.15; see docs/REPAIR.md.
package io.github.bytenomad47.a20.repair;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.*;
import org.json.*;

/** Recognizes only the fixed compact diagnostic format; no commands or writes. */
final class ChargingHistory {
    static long signed32(String text)throws Exception {
        long n=Long.parseLong(text);if(n<Integer.MIN_VALUE || n>Integer.MAX_VALUE)throw new IOException("History time range");return n;
    }
    static JSONObject parse(String text,int bytes)throws Exception {
        if(bytes>4068)throw new IOException("Compact history reply limit");
        String ear=text.substring(0,9),body=text.substring(9);
        if(body.equals("CHG_DATA_NV invalid"))return new JSONObject().put("ear",ear.substring(8)).put("replyBytes",bytes)
            .put("recordValid",false).put("historyComplete",false).put("diagnosticFormat","compact-history-1.13");
        String[] lines=body.split("\\r?\\n",-1);
        // Fixed count, including unused physical slots. A quiet gap mid-stream
        // cannot masquerade as a complete shorter history.
        if(lines.length!=222 || !lines[221].isEmpty())throw new IOException("Incomplete compact history");
        String header=ear+lines[0].replace("CHG_DATA_NV","CHG_INFO_NV")+"\n";
        JSONObject result=ChargingProtocol.parse('7',header.getBytes(StandardCharsets.US_ASCII));
        JSONArray records=new JSONArray();
        for(int slot=0;slot<20;slot++){
            int start=1+slot*11;
            Matcher h=Pattern.compile("ct\\[([0-9]{1,2})\\]:(-?[0-9]{1,10})").matcher(lines[start]);
            if(!h.matches() || Integer.parseInt(h.group(1))!=slot)throw new IOException("Unexpected history slot");
            long chargeTime=signed32(h.group(2));JSONArray samples=new JSONArray();
            for(int sample=0;sample<10;sample++){
                Matcher v=Pattern.compile("(-?[0-9]{1,10}),([0-9]{1,5})").matcher(lines[start+1+sample]);
                if(!v.matches())throw new IOException("Unexpected history sample");
                long time=signed32(v.group(1));int voltage=Integer.parseInt(v.group(2));
                if(voltage>65535)throw new IOException("History voltage range");
                samples.put(new JSONObject().put("slot",sample).put("rawTime",time).put("rawVoltage",voltage));
            }
            records.put(new JSONObject().put("slot",slot).put("rawChargeTime",chargeTime)
                .put("recordMarkedEmpty",chargeTime==0).put("storedSamples",samples));
        }
        return result.put("replyBytes",bytes).put("diagnosticFormat","compact-history-1.13")
            .put("historyComplete",true).put("storedHistory",records)
            .put("scope","All20 physical record slots and10 stored time/voltage pairs each. Zero charge-time marks an empty slot. Unused/stale sample slots can remain within nonempty records; stored sample count and time units are not verified. No clearing or charger/protection changes.");
    }
}
