// SPDX-License-Identifier: GPL-3.0-only
package io.github.bytenomad47.a20.repair;

import org.json.*;

/** Generic eligibility for the observed A6611/1.57 diagnostic profile, not a cell-health test. */
final class ChargingFaultEligibility {
    static void require(boolean ok,String reason)throws ChargingFaultReset.Refused {if(!ok)throw new ChargingFaultReset.Refused(reason);}
    static void check(JSONObject data)throws Exception {check(data,false);}
    static void checkExperiment(JSONObject data)throws Exception {check(data,true);}
    private static void check(JSONObject data,boolean experiment)throws Exception {
        String ear=data.optString("ear");require("L".equals(ear)||"R".equals(ear),"ear-invalid");
        JSONObject voltage=data.getJSONObject("voltage"),h=data.getJSONObject("main"),timing=data.getJSONObject("timing");
        require(ear.equals(voltage.optString("ear")) && ear.equals(h.optString("ear")) && ear.equals(timing.optString("ear")),"ear-changed");
        require("compact-history-1.13".equals(h.optString("diagnosticFormat")) && h.optBoolean("historyComplete") && h.optBoolean("recordValid"),"diagnostic-profile-required");
        int cached=voltage.getInt("cachedBatteryVoltage");require(cached>=3400 && cached<=(experiment?4200:4000),"cached-voltage-outside-profile");
        int count=h.getInt("mainVoltageLimitFaultCounter");
        require((experiment?(count==1 || count==2):count==3) && h.getInt("error2")==count && h.getInt("error3")==0,"fault-counters-outside-profile");
        require(timing.optBoolean("recordValid") && timing.getInt("timingRecordLock")==0,"timing-record-outside-profile");
        int mild=h.getInt("error1"),normal=h.getInt("normalCount"),index=h.getInt("index");long lifetime=(long)mild+normal;
        require(mild>=count && normal>=0 && index>=0 && index<20 && lifetime>=count && lifetime%20==index,"lifetime-index-inconsistent");
        JSONArray records=h.getJSONArray("storedHistory");require(records.length()==20,"history-shape");
        int used=0,visibleMild=0,visibleNormal=0,visibleLock=0;boolean lastLock=false;
        for(int slot=0;slot<20;slot++){
            JSONObject row=records.getJSONObject(slot);require(row.getInt("slot")==slot,"history-order");JSONArray samples=row.getJSONArray("storedSamples");require(samples.length()==10,"sample-count");
            long charge=row.getLong("rawChargeTime");boolean empty=charge==0;require(row.getBoolean("recordMarkedEmpty")==empty,"empty-marker-inconsistent");
            boolean shouldBeUsed=lifetime>=20 || slot<lifetime;require(shouldBeUsed!=empty,"ring-layout-inconsistent");
            if(empty){for(int i=0;i<10;i++){JSONObject q=samples.getJSONObject(i);require(q.getInt("slot")==i && q.getLong("rawTime")==0 && q.getInt("rawVoltage")==0,"empty-slot-has-data");}continue;}
            used++;int[] volts=new int[8];long previous=0;
            for(int i=0;i<10;i++){
                JSONObject q=samples.getJSONObject(i);require(q.getInt("slot")==i,"sample-order");long time=q.getLong("rawTime");int v=q.getInt("rawVoltage");
                require(time>=Integer.MIN_VALUE && time<=Integer.MAX_VALUE,"clock-range");
                if(i>=8){require(time==0 && v==0,"unsupported-tail-slots");continue;}
                require(v>=3400 && v<=4350,"stored-voltage-outside-profile");volts[i]=v;
                long gap=(time-(i==0?charge:previous))&0xffffffffL;
                require(i==0?gap>=1790000 && gap<=1810000:gap>=295000 && gap<=305000,"sampling-cadence-outside-profile");
                if(i>0)require(Math.abs(v-volts[i-1])<30,"abrupt-sampled-change");previous=time;
            }
            boolean m=false;int qualifying=0;
            for(int i=0;i<4;i++){int drop=volts[i]-volts[i+4];require(drop<30,"larger-drop-in-history");if(drop>=15)m=true;if(drop>=20)qualifying++;}
            if(m)visibleMild++;else visibleNormal++;
            if(qualifying>=2){visibleLock++;if(slot==(index+19)%20)lastLock=true;}
        }
        require(used==Math.min(lifetime,20) && visibleMild<=mild && visibleNormal<=normal,"visible-counts-inconsistent");
        require(visibleLock==count && (experiment || lastLock),"all-three-lock-events-required");
        if(lifetime<=20)require(visibleMild==mild && visibleNormal==normal,"unwrapped-counts-inconsistent");
    }
}
