package io.github.xgl34222220.bichen;

import java.util.Arrays;
import java.util.Collections;

public final class DomainRuleProjectionTest {
    private static int checks;
    private static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);checks++;System.out.println("PASS "+label);}
    public static void main(String[] args){
        DomainRuleProjection p=DomainRuleProjection.build(
                Arrays.asList("ads.example","tracker.example","manual.block"),
                Arrays.asList("tracker.example","not-enabled.example"),
                Arrays.asList("safe.tracker.example"));
        check(p.exact.equals(Arrays.asList("ads.example","manual.block")),"non-suffix effective rules remain exact");
        check(p.suffix.equals(Collections.singletonList("tracker.example")),"only active suffix candidates become suffix rules");
        check(p.allow.equals(Collections.singletonList("safe.tracker.example")),"exact allow exceptions preserved separately");
        check(p.blockedCount()==3,"blocked count has no double counting");
        boolean immutable=false;try{p.suffix.add("x.example");}catch(UnsupportedOperationException expected){immutable=true;}
        check(immutable,"projection lists are immutable");
        DomainRuleProjection empty=DomainRuleProjection.build(Collections.emptyList(),Collections.singleton("x.example"),null);
        check(empty.exact.isEmpty()&&empty.suffix.isEmpty()&&empty.allow.isEmpty(),"inactive suffix candidates never become live rules");
        System.out.println("DomainRuleProjectionTest passed: "+checks);
    }
}
