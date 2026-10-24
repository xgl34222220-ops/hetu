// 河图 eBPF 规则匹配器 — written from scratch for Hetu; no BoxProxy/boxbpf code or layout is used.
//
// Four socket-filter programs for iptables `-m bpf --object-pinned`. A match is a non-zero
// return value. The programs answer "is this packet's owner UID in the list" and "is this
// packet's destination inside one of the bypass networks", so a list change only rewrites a
// pinned map instead of rebuilding N iptables rules.
//
// Legacy (pre-BTF) map definitions and section names only: the loader in native/ebpf/loader
// resolves them with plain ELF relocations, so no libbpf, no BTF and no vmlinux.h are needed
// and the object loads on every Android kernel that has xt_bpf (4.9+).
#define SEC(name) __attribute__((section(name), used))

typedef unsigned char __u8;
typedef unsigned short __u16;
typedef unsigned int __u32;
typedef unsigned long long __u64;

// The subset of __sk_buff a socket filter may read. Offsets are kernel ABI and never change.
struct __sk_buff {
	__u32 len;
	__u32 pkt_type;
	__u32 mark;
	__u32 queue_mapping;
	__u32 protocol;
	__u32 vlan_present;
	__u32 vlan_tci;
	__u32 vlan_proto;
	__u32 priority;
	__u32 ingress_ifindex;
	__u32 ifindex;
	__u32 tc_index;
	__u32 cb[5];
	__u32 hash;
	__u32 tc_classid;
	__u32 data;
	__u32 data_end;
};

struct bpf_map_def {
	__u32 type;
	__u32 key_size;
	__u32 value_size;
	__u32 max_entries;
	__u32 map_flags;
};

#define BPF_MAP_TYPE_HASH 1
#define BPF_MAP_TYPE_LPM_TRIE 11
#define BPF_F_NO_PREALLOC 1

static void *(*bpf_map_lookup_elem)(void *map, const void *key) = (void *)1;
static long (*bpf_skb_load_bytes)(const void *skb, __u32 offset, void *to, __u32 len) = (void *)26;
static __u32 (*bpf_get_socket_uid)(const void *skb) = (void *)47;

// uid -> flag bits. bit0: the session's direct UIDs (Hetu itself, DIRECT apps). bit1: the
// app-scope list (bypassed in blacklist mode, the only proxied apps in whitelist mode).
#define HETU_UID_DIRECT 1
#define HETU_UID_LIST 2
// Kernel overflowuid: bpf_get_socket_uid() answers it when the packet has no full socket.
#define HETU_NO_OWNER 65534

struct bpf_map_def SEC("maps") hetu_uid = {
	.type = BPF_MAP_TYPE_HASH,
	.key_size = sizeof(__u32),
	.value_size = sizeof(__u8),
	.max_entries = 4096,
	.map_flags = 0,
};

struct lpm_key4 {
	__u32 prefixlen;
	__u8 data[4];
};

struct lpm_key6 {
	__u32 prefixlen;
	__u8 data[16];
};

struct bpf_map_def SEC("maps") hetu_cidr4 = {
	.type = BPF_MAP_TYPE_LPM_TRIE,
	.key_size = sizeof(struct lpm_key4),
	.value_size = sizeof(__u8),
	.max_entries = 65536,
	.map_flags = BPF_F_NO_PREALLOC,
};

struct bpf_map_def SEC("maps") hetu_cidr6 = {
	.type = BPF_MAP_TYPE_LPM_TRIE,
	.key_size = sizeof(struct lpm_key6),
	.value_size = sizeof(__u8),
	.max_entries = 32768,
	.map_flags = BPF_F_NO_PREALLOC,
};

// Optional CN networks (CNIP kernel bypass when ipset is unavailable).
struct bpf_map_def SEC("maps") hetu_cn4 = {
	.type = BPF_MAP_TYPE_LPM_TRIE,
	.key_size = sizeof(struct lpm_key4),
	.value_size = sizeof(__u8),
	.max_entries = 65536,
	.map_flags = BPF_F_NO_PREALLOC,
};

struct bpf_map_def SEC("maps") hetu_cn6 = {
	.type = BPF_MAP_TYPE_LPM_TRIE,
	.key_size = sizeof(struct lpm_key6),
	.value_size = sizeof(__u8),
	.max_entries = 65536,
	.map_flags = BPF_F_NO_PREALLOC,
};

static __attribute__((always_inline)) int dst4_in(const struct __sk_buff *skb, void *map)
{
	struct lpm_key4 key = {};
	// IPv4 destination address: 16 bytes into the network header.
	if (bpf_skb_load_bytes(skb, 16, key.data, 4) < 0)
		return 0;
	key.prefixlen = 32;
	return bpf_map_lookup_elem(map, &key) ? 1 : 0;
}

static __attribute__((always_inline)) int dst6_in(const struct __sk_buff *skb, void *map)
{
	struct lpm_key6 key = {};
	// IPv6 destination address: 24 bytes into the network header.
	if (bpf_skb_load_bytes(skb, 24, key.data, 16) < 0)
		return 0;
	key.prefixlen = 128;
	return bpf_map_lookup_elem(map, &key) ? 1 : 0;
}

static __attribute__((always_inline)) int uid_has(const struct __sk_buff *skb, __u8 want)
{
	__u32 uid = bpf_get_socket_uid(skb);
	if (uid == HETU_NO_OWNER)
		return 0;
	__u8 *flags = bpf_map_lookup_elem(&hetu_uid, &uid);
	if (!flags)
		return 0;
	return (*flags & want) ? 1 : 0;
}

// Packets whose owner is on the session's direct list.
SEC("socket/hetu_uid_direct")
int hetu_uid_direct(struct __sk_buff *skb)
{
	return uid_has(skb, HETU_UID_DIRECT);
}

// Packets whose owner is on the app-scope list.
SEC("socket/hetu_uid_list")
int hetu_uid_list(struct __sk_buff *skb)
{
	return uid_has(skb, HETU_UID_LIST);
}

SEC("socket/hetu_bypass4")
int hetu_bypass4(struct __sk_buff *skb)
{
	return dst4_in(skb, &hetu_cidr4);
}

SEC("socket/hetu_bypass6")
int hetu_bypass6(struct __sk_buff *skb)
{
	return dst6_in(skb, &hetu_cidr6);
}

SEC("socket/hetu_cn4")
int hetu_cn4_match(struct __sk_buff *skb)
{
	return dst4_in(skb, &hetu_cn4);
}

SEC("socket/hetu_cn6")
int hetu_cn6_match(struct __sk_buff *skb)
{
	return dst6_in(skb, &hetu_cn6);
}

char _license[] SEC("license") = "GPL";
