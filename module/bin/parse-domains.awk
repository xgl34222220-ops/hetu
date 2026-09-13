# Only exact ASCII domains and blocking hosts syntax are supported. Never execute input.
function valid(s, n,a,i) {
  if (length(s)>253 || s !~ /^[a-z0-9.-]+$/ || s !~ /\./) return 0
  n=split(s,a,".")
  for (i=1;i<=n;i++) if(length(a[i])<1 || length(a[i])>63 || a[i] !~ /^[a-z0-9]/ || a[i] !~ /[a-z0-9]$/) return 0
  return a[n] ~ /[a-z]/
}
function localname(s) { return s=="localhost" || s=="localhost.localdomain" || s=="local" || s=="broadcasthost" || s=="ip6-localhost" || s=="ip6-loopback" || s=="ip6-localnet" || s=="ip6-mcastprefix" || s=="ip6-allnodes" || s=="ip6-allrouters" || s=="ip6-allhosts" }
{
  sub(/\r$/, ""); sub(/#.*/, ""); gsub(/^[ \t]+|[ \t]+$/, "")
  if ($0=="" || $0 ~ /^!/) next
  if (NR>500000 || length($0)>8192) { bad=1; exit }
  start=1
  if ($1=="0.0.0.0" || $1=="127.0.0.1" || $1=="::" || $1=="::1") start=2
  else if (NF!=1) { bad=1; next }
  if (start==2 && NF<2) { bad=1; next }
  for (i=start;i<=NF;i++) {
    d=tolower($i); sub(/\.$/, "",d)
    if (localname(d)) continue
    if (!valid(d)) { bad=1; continue }
    if (!seen[d]++) { print d; count++; if(count>250000) {bad=1; exit} }
  }
}
END { if (bad || count==0) exit 1 }
