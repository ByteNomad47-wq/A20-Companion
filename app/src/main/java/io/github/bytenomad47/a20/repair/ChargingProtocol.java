// SPDX-License-Identifier: GPL-3.0-only
// Reused from the author’s generic A20 reset research, version 1.15; see docs/REPAIR.md.
package io.github.bytenomad47.a20.repair;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Fixed read-only SPP requests from the examined A6611 1.57 handler. */
final class ChargingProtocol {
    static final int MAX_REPLY=4096;
    static final long READ_MS=8000, QUIET_MS=600;
    interface Link {
        void send(int value) throws Exception;
        int available() throws Exception;
        int read(byte[] bytes,int count) throws Exception;
        long now();
        void pause(long ms) throws Exception;
        boolean cancelled();
    }
    interface Sink { void update(JSONObject result) throws Exception; }
    static boolean supportedName(String name) {
        return name!=null && name.matches("(?i)^(?:anker\\s+)?(?:soundcore\\s+)?sleep\\s+a20$");
    }
    static JSONObject parse(int command,byte[] bytes) throws Exception {
        if(command!='4' && command!='2' && command!='7')throw new IllegalArgumentException("Unsupported diagnostic command");
        if(bytes.length>MAX_REPLY)throw new IOException("Reply limit");
        for(byte b:bytes) {int c=b&255;if(c!=10 && c!=13 && (c<32 || c>126))throw new IOException("Non-text reply");}
        String text=new String(bytes,StandardCharsets.US_ASCII);
        if(!text.startsWith("EARSIDE L") && !text.startsWith("EARSIDE R"))throw new IOException("Missing ear marker");
        JSONObject r=new JSONObject().put("ear",text.substring(8,9)).put("replyBytes",bytes.length);
        String body=text.substring(9);
        if(command=='7'){
            if(body.startsWith("CHG_DATA_NV"))return ChargingHistory.parse(text,bytes.length);
            if(bytes.length>103)throw new IOException("Diagnostic header limit");
            if(body.equals("CHG_INFO_NV invalid"))return r.put("recordValid",false);
            Matcher h=Pattern.compile("CHG_INFO_NV lock:([0-9]{1,5}), index:([0-9]{1,5}), err1:([0-9]{1,5}), err2:([0-9]{1,5}), err3:([0-9]{1,5}), nor:(-?[0-9]{1,10})\\r?\\n").matcher(body);
            if(!h.matches())throw new IOException("Diagnostic header unavailable");
            String[] keys={"rawMainCounter","rawIndex","error1","error2","error3","normalCount"};
            for(int i=0;i<keys.length;i++){
                long n=Long.parseLong(h.group(i+1));
                // Actual Thumb LDRH loads zero-extend all five 16-bit fields;
                // inferred signed short types in decompiler output are misleading.
                if(i<5 && n>65535 || i==5 && (n<Integer.MIN_VALUE || n>Integer.MAX_VALUE))throw new IOException("Main record range");
                r.put(keys[i],n);
            }
            return r.put("recordValid",true).put("mainVoltageLimitFaultCounter",r.getInt("rawMainCounter"))
                .put("index",r.getInt("rawIndex"))
                .put("scope","Main charging fault header only; no clearing or full history. A fault count is not a battery-health diagnosis.");
        }
        if(command=='4') {
            Matcher m=Pattern.compile("local_bat_volt ([0-9]{1,5})\\s*").matcher(body);
            if(!m.matches())throw new IOException("Unexpected voltage reply");
            int value=Integer.parseInt(m.group(1));if(value>65535)throw new IOException("Voltage range");
            return r.put("cachedBatteryVoltage",value).put("voltageUnits","mV interpretation from firmware; cached value, freshness unverified");
        }
        if(body.matches("CHG_TIME_NV invalid\\r?\\n"))return r.put("recordValid",false);
        String[] lines=body.split("\\r?\\n",-1);
        if(lines.length<2 || !lines[lines.length-1].isEmpty())throw new IOException("Incomplete time record");
        Matcher h=Pattern.compile("CHG_TIME_NV valid, lock:(-?[0-9]{1,5}), index:(-?[0-9]{1,5}), err1:(-?[0-9]{1,5}), err2:(-?[0-9]{1,5}), err3:(-?[0-9]{1,5}), nor:(-?[0-9]{1,10})").matcher(lines[0]);
        if(!h.matches())throw new IOException("Unexpected time header");
        String[] keys={"timingRecordLock","index","error1","error2","error3","normalCount"};
        for(int i=0;i<keys.length;i++) {
            long value=Long.parseLong(h.group(i+1));
            if(i<5 && (value< -32768 || value>65535) || i==5 && (value<Integer.MIN_VALUE || value>Integer.MAX_VALUE))throw new IOException("Counter range");
            r.put(keys[i],value);
        }
        JSONArray samples=new JSONArray();int previous=-1;
        for(int i=1;i<lines.length-1;i++) {
            Matcher m=Pattern.compile("i:([0-9]{1,2}), t:(-?[0-9]{1,10})").matcher(lines[i]);
            if(!m.matches())throw new IOException("Unexpected time sample");
            int index=Integer.parseInt(m.group(1));long value=Long.parseLong(m.group(2));
            if(index<=previous || index>=50 || value<Integer.MIN_VALUE || value>Integer.MAX_VALUE)throw new IOException("Sample range");
            samples.put(new JSONObject().put("index",index).put("rawTime",value));previous=index;
        }
        return r.put("recordValid",true).put("samples",samples)
            .put("scope","Separate timing record only. Its lock is NOT the main voltage-limit fault counter. Stream quiet boundary; full sample coverage unverified.");
    }
    static byte[] exchange(Link link,int command) throws Exception {
        if(command!='4' && command!='2' && command!='7')throw new IllegalArgumentException("Unsupported diagnostic command");
        if(link.cancelled())throw new IOException("Cancelled");
        // Do not let an unsolicited banner/reply be attributed to this command.
        if(link.available()!=0)throw new IOException("Unexpected pending diagnostic data");
        link.send(command);
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] block=new byte[512];
        long start=link.now(),last=start;boolean complete=false;
        while(link.now()-start<READ_MS) {
            if(link.cancelled())throw new IOException("Cancelled");
            int count=link.available();
            if(count>0) {
                if(count>MAX_REPLY-out.size())throw new IOException("Reply limit");
                int n=link.read(block,Math.min(block.length,count));
                if(n<0)throw new IOException("Diagnostic stream closed");
                if(n>0) {
                    out.write(block,0,n);last=link.now();
                    try {parse(command,out.toByteArray());complete=true;}catch(Exception invalid){complete=false;}
                }
            } else if(complete && link.now()-last>=QUIET_MS)return out.toByteArray();
            else link.pause(25);
        }
        throw new IOException("Diagnostic reply timeout");
    }
    static void run(Link link,JSONObject report,Sink sink) throws Exception {
        run(link,report,sink,false);
    }
    static void run(Link link,JSONObject report,Sink sink,boolean mainHeader) throws Exception {
        JSONArray reads=new JSONArray();report.put("reads",reads).put("mainVoltageLimitFaultCounter",JSONObject.NULL)
            .put("mainCounterStatus","Not read: full-history formatter bounds unverified");
        String ear=null;
        int[] commands=mainHeader?new int[]{'4','7'}:new int[]{'4','2'};
        for(int command:commands) {
            if(link.cancelled())throw new IOException("Cancelled");
            JSONObject q=new JSONObject().put("command",String.valueOf((char)command)).put("state","reading");
            reads.put(q);report.put("state",command=='4'?"Reading cached battery voltage":command=='7'?"Reading diagnostic main fault header":"Reading separate charging-time record");sink.update(report);
            JSONObject value=parse(command,exchange(link,command));
            if(ear!=null && !ear.equals(value.getString("ear")))throw new IOException("Ear marker changed");
            ear=value.getString("ear");q.put("result",value).put("state","complete");sink.update(report);
            if(command=='7'){
                report.put("mainVoltageLimitFaultCounter",value.has("mainVoltageLimitFaultCounter")?value.get("mainVoltageLimitFaultCounter"):JSONObject.NULL);
                report.put("voltageHistoryRetrieved",value.optBoolean("historyComplete",false));
                report.put("mainCounterStatus",value.optBoolean("recordValid")?"Main header read; do not infer a battery-health diagnosis from the counter alone":"Main header invalid; no main counter available");
            }
        }
        report.put("state",mainHeader && !report.optBoolean("voltageHistoryRetrieved",false)
            ?"Counter read; compact voltage history unavailable. Save charging report."
            :"Charging snapshot complete; save charging report").put("complete",true);sink.update(report);
    }
}
