package io.github.xgl34222220.bichen;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/** Immutable settings used to build ONE VPN session, never a live preferences view.
 * Bypassed apps leave the entire VPN (proxy AND DNS filtering), not just ads. */
final class ProxyAppPolicy {
    final boolean filterEnabled;
    final Set<String> requested;
    final Set<String> applied;
    final Set<String> missing;

    ProxyAppPolicy(boolean filter, Set<String> selected, Set<String> accepted, String self) {
        filterEnabled = filter;
        TreeSet<String> wanted = new TreeSet<>();
        if (selected != null) for (String pkg : selected) {
            if (pkg == null || pkg.length() > 255 || !pkg.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*"))
                throw new IllegalArgumentException("应用放行名单包含无效包名，请重新选择");
            if (!pkg.equals(self)) wanted.add(pkg);
        }
        requested = Collections.unmodifiableSet(wanted);
        TreeSet<String> installed = new TreeSet<>();
        if (accepted != null) installed.addAll(accepted);
        installed.retainAll(wanted);
        applied = Collections.unmodifiableSet(installed);
        TreeSet<String> absent = new TreeSet<>(wanted); absent.removeAll(installed);
        missing = Collections.unmodifiableSet(absent);
    }
    boolean differs(boolean filter, Set<String> selected, String self) {
        try { return filterEnabled != filter || !requested.equals(new ProxyAppPolicy(filter, selected, null, self).requested); }
        catch (IllegalArgumentException invalid) { return true; }
    }
}
