package io.github.xgl34222220.bichen;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Random;

/** Run on a host JVM; no Android, root or network access is used. */
public final class DnsPacketTest {
    private static int checks;
    private static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }
    private static void put16(byte[] b, int p, int n) { b[p]=(byte)(n >>> 8); b[p+1]=(byte)n; }
    private static int u16(byte[] b, int p) { return ((b[p]&255)<<8)|(b[p+1]&255); }
    private static byte[] query(String host, int type) {
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        byte[] h = new byte[12]; put16(h,0,0xabcd); put16(h,2,0x0110); put16(h,4,1);
        wire.write(h,0,h.length);
        for (String part : host.split("\\.")) { wire.write(part.length()); for (char c : part.toCharArray()) wire.write(c); }
        wire.write(0); wire.write(type>>>8); wire.write(type); wire.write(0); wire.write(1);
        return packet(wire.toByteArray());
    }
    private static byte[] packet(byte[] dns) {
        byte[] p = new byte[28+dns.length]; p[0]=0x45; put16(p,2,p.length); p[8]=64; p[9]=17;
        p[12]=10;p[13]=111;p[15]=1; p[16]=10;p[17]=111;p[19]=2;
        put16(p,20,45678); put16(p,22,53); put16(p,24,dns.length+8);
        System.arraycopy(dns,0,p,28,dns.length); return p;
    }
    private static void checksum(byte[] p) {
        check(DnsPacket.checksum(p,0,20,0)==0,"IPv4 checksum");
        int pseudo = u16(p,12)+u16(p,14)+u16(p,16)+u16(p,18)+(p[9]&255)+p.length-20;
        check(DnsPacket.checksum(p,20,p.length-20,pseudo)==0,"Transport checksum");
    }
    public static void main(String[] args) {
        byte[] packet=query("Ads.Example.COM",1);
        DnsPacket.Query q=DnsPacket.parse(packet,packet.length);
        check(q!=null,"parse IPv4 UDP query"); check(q.domain.equals("ads.example.com"),"case normalized");
        check(q.type==1 && q.queryClass==1 && q.sourcePort==45678 && q.id==0xabcd,"question and tuple");
        for(int rcode:new int[]{2,3}) {
            byte[] dns=DnsPacket.error(q,rcode), out=DnsPacket.responsePacket(q,dns);
            check(DnsPacket.validResponse(q,dns),"generated response validates");
            check((dns[3]&15)==rcode && u16(dns,4)==1 && u16(dns,6)==0,"negative response counts");
            check((u16(dns,2)&0x8110)==0x8110,"RD/CD preserved, QR set");
            check(u16(out,20)==53 && u16(out,22)==45678 && out[15]==2 && out[19]==1,"response direction");
            checksum(out);
            dns[1]^=1; check(!DnsPacket.validResponse(q,dns),"wrong transaction rejected");
        }
        byte[] aaaa=query("ipv6.example.com",28);
        check(DnsPacket.parse(aaaa,aaaa.length).type==28,"AAAA query accepted over IPv4 DNS");
        byte[] mismatch=DnsPacket.error(q,3); mismatch[mismatch.length-3]=28;
        check(!DnsPacket.validResponse(q,mismatch),"question type mismatch rejected");
        byte[] loop=Arrays.copyOf(q.dns,18); loop[12]=(byte)0xc0;loop[13]=12;
        check(DnsPacket.parse(packet(loop),packet(loop).length)==null,"compression cycle rejected");
        loop[13]=(byte)250; check(DnsPacket.parse(packet(loop),packet(loop).length)==null,"out-of-bounds compression rejected");
        byte[] malformed=packet.clone(); put16(malformed,24,65535);
        check(DnsPacket.parse(malformed,malformed.length)==null,"oversized UDP rejected");
        malformed=packet.clone();put16(malformed,2,15);
        check(DnsPacket.parse(malformed,malformed.length)==null,"short IP payload rejected");
        malformed=packet.clone();put16(malformed,6,0x2000);
        check(DnsPacket.parse(malformed,malformed.length)==null,"fragments rejected");
        malformed=packet.clone();put16(malformed,22,5353);
        check(DnsPacket.parse(malformed,malformed.length)==null,"unrelated port ignored");
        for(int size=0;size<packet.length;size++) check(DnsPacket.parse(packet,size)==null,"truncated input rejected "+size);
        check(DnsPacket.parse(packet,packet.length+1)==null,"caller size beyond array rejected");
        byte[] tcp=new byte[40];tcp[0]=0x45;put16(tcp,2,40);tcp[9]=6;tcp[12]=10;tcp[15]=1;tcp[16]=10;tcp[19]=2;
        put16(tcp,20,49123);put16(tcp,22,53);tcp[27]=99;tcp[32]=0x50;tcp[33]=2;
        byte[] reset=DnsPacket.tcpReset(tcp,tcp.length);
        check(reset!=null && reset[33]==20 && reset[31]==100,"TCP SYN gets RST/ACK, advances SYN"); checksum(reset);
        tcp[33]=4;check(DnsPacket.tcpReset(tcp,tcp.length)==null,"do not respond to RST");
        tcp[33]=16;tcp[31]=77;reset=DnsPacket.tcpReset(tcp,tcp.length);
        check(reset[33]==4 && reset[27]==77,"TCP ACK gets RST with correct sequence");checksum(reset);
        byte[] odd=DnsPacket.error(q,2);odd=Arrays.copyOf(odd,odd.length+1);checksum(DnsPacket.responsePacket(q,odd));
        Random random=new Random(701);
        for(int i=0;i<100000;i++) {
            byte[] bytes=new byte[random.nextInt(1024)];random.nextBytes(bytes);
            DnsPacket.parse(bytes,bytes.length);DnsPacket.tcpReset(bytes,bytes.length);
            DnsPacket.validResponse(q,bytes);
        }
        check(true,"100000 random malformed packets did not throw");
        System.out.println("PASS: "+checks+" assertions and 100000 malformed-packet cases");
    }
}
