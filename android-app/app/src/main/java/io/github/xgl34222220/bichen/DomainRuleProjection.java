package io.github.xgl34222220.bichen;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Projects one immutable rule generation into exact, suffix and exception views for Mihomo. */
final class DomainRuleProjection {
    final List<String> exact;
    final List<String> suffix;
    final List<String> allow;

    private DomainRuleProjection(Set<String> exactRules, Set<String> suffixRules, Set<String> allowRules) {
        exact = immutableSorted(exactRules);
        suffix = immutableSorted(suffixRules);
        allow = immutableSorted(allowRules);
    }

    static DomainRuleProjection build(Collection<String> effective, Collection<String> suffixCandidates,
                                      Collection<String> allowRules) {
        TreeSet<String> all = new TreeSet<>();
        if (effective != null) all.addAll(effective);
        TreeSet<String> suffix = new TreeSet<>();
        if (suffixCandidates != null) suffix.addAll(suffixCandidates);
        suffix.retainAll(all);
        TreeSet<String> exact = new TreeSet<>(all);
        exact.removeAll(suffix);
        TreeSet<String> allow = new TreeSet<>();
        if (allowRules != null) allow.addAll(allowRules);
        return new DomainRuleProjection(exact, suffix, allow);
    }

    int blockedCount() { return exact.size() + suffix.size(); }

    private static List<String> immutableSorted(Set<String> values) {
        return Collections.unmodifiableList(new ArrayList<>(values));
    }
}
