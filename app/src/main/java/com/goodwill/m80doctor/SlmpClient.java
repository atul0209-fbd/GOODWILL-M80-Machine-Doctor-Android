package com.goodwill.m80doctor;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class SlmpClient {
    private final String host;
    private final int port;
    public SlmpClient(String host, int port) { this.host = host; this.port = port; }

    private static int deviceCode(String f) {
        switch (f) {
            case "SM": return 0x91; case "X": return 0x9C; case "Y": return 0x9D; case "M": return 0x90;
            case "L": return 0x92; case "F": return 0x93; case "B": return 0xA0; case "R": return 0xAF;
            case "TN": return 0xC2; default: throw new IllegalArgumentException("Unsupported family " + f);
        }
    }
    private static void le16(ByteArrayOutputStream o, int v) { o.write(v & 0xFF); o.write((v >>> 8) & 0xFF); }
    private static void le24(ByteArrayOutputStream o, int v) { o.write(v & 0xFF); o.write((v >>> 8)&0xFF); o.write((v>>>16)&0xFF); }
    private static byte[] readExact(InputStream in, int n) throws Exception {
        byte[] b = new byte[n]; int off=0; while(off<n){int r=in.read(b,off,n-off); if(r<=0) throw new Exception("SLMP connection closed"); off+=r;} return b;
    }
    private byte[] readFrame(Socket sock, String family, int head, int points, boolean bitAccess) throws Exception {
        ByteArrayOutputStream req=new ByteArrayOutputStream();
        le16(req,0x0010); le16(req,0x0401); le16(req,bitAccess?0x0001:0x0000); le24(req,head); req.write(deviceCode(family)); le16(req,points);
        ByteArrayOutputStream frame=new ByteArrayOutputStream();
        frame.write(new byte[]{0x50,0x00,0x00,(byte)0xFF,(byte)0xFF,0x03,0x00}); le16(frame,req.size()); frame.write(req.toByteArray());
        OutputStream out=sock.getOutputStream(); out.write(frame.toByteArray()); out.flush();
        InputStream in=sock.getInputStream(); byte[] hdr=readExact(in,9); if((hdr[0]&0xFF)!=0xD0) throw new Exception("Unexpected SLMP response");
        int len=(hdr[7]&0xFF)|((hdr[8]&0xFF)<<8); byte[] body=readExact(in,len); if(body.length<2) throw new Exception("SLMP response too short");
        int end=(body[0]&0xFF)|((body[1]&0xFF)<<8); if(end!=0) throw new Exception(String.format(Locale.US,"SLMP end 0x%04X",end));
        byte[] data=new byte[body.length-2]; System.arraycopy(body,2,data,0,data.length); return data;
    }
    private int[] readBitWords(Socket s,String family,int head,int words)throws Exception{
        byte[] raw=readFrame(s,family,head,words,false); int[] bits=new int[words*16];
        for(int w=0;w<words;w++){int v=(raw[w*2]&0xFF)|((raw[w*2+1]&0xFF)<<8);for(int b=0;b<16;b++)bits[w*16+b]=(v>>>b)&1;} return bits;
    }
    private int[] readWords(Socket s,String family,int head,int count)throws Exception{
        byte[] raw=readFrame(s,family,head,count,false); int[] vals=new int[count];
        for(int i=0;i<count;i++)vals[i]=(raw[i*2]&0xFF)|((raw[i*2+1]&0xFF)<<8); return vals;
    }
    public int readSingle(String family,int address)throws Exception{
        try(Socket s=open()){
            boolean bit=family.equals("SM")||family.equals("X")||family.equals("Y")||family.equals("M")||family.equals("L")||family.equals("F")||family.equals("B");
            byte[] raw=readFrame(s,family,address,1,bit); if(bit) return (raw[0]>>>4)&0x0F; return (raw[0]&0xFF)|((raw[1]&0xFF)<<8);
        }
    }
    public Snapshot readSnapshot()throws Exception{
        long t0=System.nanoTime(); try(Socket s=open()){
            int[] xc=readBitWords(s,"X",hex("C00"),16); int[] xl=readBitWords(s,"X",0,48); int[] y=readBitWords(s,"Y",hex("200"),18);
            int[] m=readBitWords(s,"M",592,19); int[] f=readBitWords(s,"F",0,128); int[] tn=readWords(s,"TN",0,2);
            Snapshot x=new Snapshot();
            x.controllerReady=xc[hex("C10")-hex("C00")]; x.servoReady=xc[hex("C11")-hex("C00")]; x.autoMode=xc[hex("C12")-hex("C00")];
            x.cycle=xc[hex("C13")-hex("C00")]; x.feedHold=xc[hex("C14")-hex("C00")]; x.ncAlarm=xc[hex("C98")-hex("C00")];
            x.servoAlarm=xc[hex("C99")-hex("C00")]; x.programError=xc[hex("C9A")-hex("C00")]; x.operationError=xc[hex("C9B")-hex("C00")]; x.m30=xc[hex("C43")-hex("C00")];
            x.plcAlarm=m[600-592]; x.lubePulse=m[707-592]; x.lubeCommand=m[708-592];
            x.lubeLevelRaw=xl[hex("B")]; x.lubePressureRaw=xl[hex("C")]; x.lubeFloat=xl[hex("240")]; x.lubePressureSwitch=xl[hex("241")]; x.lubeMotorOverload=xl[hex("249")];
            x.lubeMotorY227=y[hex("227")-hex("200")]; x.lubeMotorY305=y[hex("305")-hex("200")]; x.tn0=tn[0]; x.tn1=tn[1];
            List<Integer> active=new ArrayList<>(); for(int i=0;i<2048;i++) if(f[i]==1) active.add(i); x.activeFAlarms=active; x.latencyMs=(System.nanoTime()-t0)/1_000_000.0; return x;
        }
    }
    public JobData readJobData()throws Exception{
        JobData d=new JobData(); d.mCode=readSingle("R",504); d.sCode=readSingle("R",512); d.tCode=readSingle("R",536);
        d.feedOverride=readSingle("R",2500); d.rapidOverride=readSingle("R",2502); d.xLoad=readSingle("R",4884); d.zLoad=readSingle("R",4888);
        d.toolChangeStatus=readSingle("R",6437); d.spindleSpeed=readSingle("R",6500); d.spindleOverride=readSingle("R",7008);
        d.currentPositionRaw=readSingle("R",8701); d.targetWindowRaw=readSingle("R",8702); d.waitingPocket=readSingle("R",9100); d.spindleTool=readSingle("R",10620); return d;
    }

    private static long s32(int lo,int hi){return (long)(int)(((hi&0xFFFF)<<16)|(lo&0xFFFF));}

    public AxisData readAxisData()throws Exception{
        try(Socket s=open()){
            int[] cmd=readWords(s,"R",4500,10);
            int[] fb=readWords(s,"R",4628,10);
            AxisData a=new AxisData();
            a.xRaw=s32(cmd[0],cmd[1]); a.yRaw=s32(cmd[4],cmd[5]); a.zRaw=s32(cmd[8],cmd[9]);
            a.xFbRaw=s32(fb[0],fb[1]); a.yFbRaw=s32(fb[4],fb[5]); a.zFbRaw=s32(fb[8],fb[9]);
            a.xMm=a.xRaw*0.001; a.yMm=a.yRaw*0.001; a.zMm=a.zRaw*0.001;
            a.xFbMm=a.xFbRaw*0.001; a.yFbMm=a.yFbRaw*0.001; a.zFbMm=a.zFbRaw*0.001;
            return a;
        }
    }

    public WindowProbe readPlcWindowProbe()throws Exception{
        WindowProbe p=new WindowProbe();
        try(Socket s=open()){
            p.config=readWords(s,"R",424,12);
            int total=0;
            for(int g=0;g<3 && total<6;g++){
                int start=p.config[g*4], nraw=p.config[g*4+1];
                int count=nraw>=1001?nraw-1000:nraw;
                int stride=nraw>=1001?18:16;
                if(start<8300 || count<=0) continue;
                int limit=Math.min(count,6-total);
                for(int i=0;i<limit;i++){
                    try{
                        int[] w=readWords(s,"R",start+i*stride,stride);
                        WindowEntry e=new WindowEntry();
                        e.address=start+i*stride; e.extended=stride==18; e.control=w[0]; e.section=w[1]; e.subId=w[2];
                        if(e.extended){
                            e.subSection=((w[4]&0xFFFF)<<16)|(w[3]&0xFFFF);
                            e.dataNo=((w[6]&0xFFFF)<<16)|(w[5]&0xFFFF);
                            e.method=w[7]; e.number=w[8]; e.result=w[9];
                            for(int k=0;k<4;k++) e.data[k]=s32(w[10+k*2],w[11+k*2]);
                        }else{
                            e.subSection=w[3]; e.dataNo=w[4]; e.method=w[5]; e.number=w[6]; e.result=w[7];
                            for(int k=0;k<4;k++) e.data[k]=s32(w[8+k*2],w[9+k*2]);
                        }
                        p.windows.add(e); total++;
                        if(e.section==45 && (e.result&0xFF)==1 && e.method==0){
                            for(int k=0;k<Math.min(4,e.number);k++){
                                int dn=e.dataNo+k; long val=e.data[k];
                                if(dn==101)p.mainO=val; if(dn==102)p.mainN=val; if(dn==103)p.mainB=val;
                                if(dn==201)p.subO=val; if(dn==202)p.subN=val; if(dn==203)p.subB=val;
                            }
                        }
                    }catch(Exception ignored){}
                }
            }
        }
        return p;
    }

    private Socket open()throws Exception{Socket s=new Socket();s.connect(new InetSocketAddress(host,port),2500);s.setSoTimeout(3000);return s;}
    private static int hex(String s){return Integer.parseInt(s,16);}
    public static final class Snapshot{public int controllerReady,servoReady,autoMode,cycle,feedHold,ncAlarm,servoAlarm,programError,operationError,m30,plcAlarm;public int lubeLevelRaw,lubePressureRaw,lubeFloat,lubePressureSwitch,lubeMotorOverload,lubePulse,lubeCommand,lubeMotorY227,lubeMotorY305,tn0,tn1;public double latencyMs;public List<Integer> activeFAlarms=new ArrayList<>();}
    public static final class JobData{public int mCode,sCode,tCode,feedOverride,rapidOverride,xLoad,zLoad,toolChangeStatus,spindleSpeed,spindleOverride,currentPositionRaw,targetWindowRaw,waitingPocket,spindleTool;}
    public static final class AxisData{public long xRaw,yRaw,zRaw,xFbRaw,yFbRaw,zFbRaw;public double xMm,yMm,zMm,xFbMm,yFbMm,zFbMm;}
    public static final class WindowEntry{public int address,control,section,subId,subSection,dataNo,method,number,result;public boolean extended;public long[] data=new long[4];}
    public static final class WindowProbe{public int[] config=new int[12];public java.util.List<WindowEntry>windows=new java.util.ArrayList<>();public Long mainO,mainN,mainB,subO,subN,subB;}
}
