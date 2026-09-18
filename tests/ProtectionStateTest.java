package io.github.xgl34222220.hetu;

public final class ProtectionStateTest {
    private static int checks;
    private static ProtectionState.State state(int bits) {
        return ProtectionState.resolve((bits&1)!=0,(bits&2)!=0,(bits&4)!=0,(bits&8)!=0,
                (bits&16)!=0,(bits&32)!=0,(bits&64)!=0,(bits&128)!=0,(bits&256)!=0,
                (bits&512)!=0,(bits&1024)!=0,(bits&2048)!=0);
    }
    private static void expect(int bits, ProtectionState.State wanted) {
        if(state(bits)!=wanted)throw new AssertionError(bits+" -> "+state(bits)+" expected "+wanted);
        checks++;
    }
    public static void main(String[] args) {
        expect(0,ProtectionState.State.CHECKING);
        expect(1,ProtectionState.State.UNKNOWN);
        expect(1|2,ProtectionState.State.UNKNOWN);
        expect(1|2|4,ProtectionState.State.ABSENT);
        expect(1|2|4|8,ProtectionState.State.PAUSED);
        expect(1|2|4|8|16,ProtectionState.State.UNVERIFIED);
        expect(1|2|4|8|32,ProtectionState.State.UNVERIFIED);
        expect(1|2|4|8|16|32,ProtectionState.State.MODULE_ACTIVE);
        expect(1|4|8|16|32,ProtectionState.State.UNKNOWN);
        expect(1|2|4|8|16|32|64,ProtectionState.State.DISABLED);
        expect(1|2|4|8|16|32|128,ProtectionState.State.REMOVAL);
        expect(1|2|4|8|16|32|256,ProtectionState.State.PENDING_REBOOT);
        expect(1|2|4|256,ProtectionState.State.PENDING_REBOOT);
        expect(512,ProtectionState.State.VPN_ACTIVE);
        expect(1024,ProtectionState.State.VPN_STARTING);
        expect(2048,ProtectionState.State.RESTORE_PENDING);
        expect(512|1024|2048,ProtectionState.State.VPN_ACTIVE);
        expect(1024|2048,ProtectionState.State.VPN_STARTING);
        expect(1|2|4|8|16|32|2048,ProtectionState.State.RESTORE_PENDING);
        for(int bits=0;bits<4096;bits++) {
            boolean vpn=(bits&512)!=0;
            boolean module=(bits&63)==63 && (bits&(64|128|256|1024|2048))==0;
            if(state(bits).active()!=(vpn||module))throw new AssertionError("false active "+bits);
        }
        System.out.println("PASS: protection state "+checks+" explicit checks and 4096 flag combinations");
    }
}
