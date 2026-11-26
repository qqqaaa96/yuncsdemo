# 组件加载器
set -u
IFS= read -r M <&3 || exit 2
IFS= read -r H1 <&3 || exit 2
IFS= read -r H2 <&3 || exit 2
IFS= read -r AL <&3 || exit 2
IFS= read -r KY <&3 || exit 2
IFS= read -r PM <&3 || exit 2
IFS= read -r WA <&3 || exit 2
IFS= read -r EX <&3 || exit 2
IFS= read -r PG <&3 || exit 2
CLEN=$(( 0x$(printf '%s' "$H2" | cut -c1-8) ))
PLEN=$(( 0x$(printf '%s' "$H2" | cut -c9-16) ))
N=$(printf '%s,' "$PM" | tr ',' '\n' | wc -l)
K0=$(( 0x$(printf '%s' "$KY" | cut -c1-2) ))

KS=''
i=0
while [ $i -lt $N ]; do
    KS="$KS$(printf '%s' "$KY:$i" | sha256sum | cut -c1-64)"
    i=$((i+1))
done

ROWS=$(awk -v al="$AL" -v ex="$EX" -v pg="$PG" '
BEGIN {
    m = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+-";
    for (i = 1; i <= 64; i++) MI[substr(m, i, 1)] = i - 1;
    n = split(ex, E, ","); IMG = E[1]; K1 = E[2] + 0; K2 = E[3] + 0;
    G = E[4] + 0; R = E[5] + 0; T = E[6] + 0; L = E[7] + 0;
    for (i = 1; i <= 16; i++) IV[substr(IMG, i, 1)] = i - 1;
    gn = split(pg, P, ",");
}
/^CQDAT_END$/ { exit }
{ S = S $0 }
END {
    body = substr(S, R + 1, length(S) - R - T);
    t = ""; for (i = 1; i <= length(body); i += 2) t = t substr(body, i, 1);
    s = t;
    t = ""; k = 0; i = 1;
    while (i <= length(s)) { t = t substr(s, i, 1); k++; i++; if (k == 5) { i++; k = 0 } }
    s = t;
    if (length(s) != L) L = length(s);
    q = int(L / G); r = L % G; p = 0;
    for (j = 1; j <= G; j++) { k = P[j]; nn = q + (k < r ? 1 : 0); seg[j] = substr(s, p + 1, nn); p += nn }
    for (j = 1; j <= G; j++) orig[P[j] + 1] = seg[j];
    s = ""; for (j = 1; j <= G; j++) s = s orig[j];
    t = ""; for (i = 1; i <= length(s); i++) { v = MI[substr(s, i, 1)]; v = (v - ((i - 1) * K1 + K2)) % 64; if (v < 0) v += 64; t = t substr(m, v + 1, 1) }
    s = t;
    t = ""; for (i = 1; i <= length(s); i++) { c = substr(s, i, 1); t = t substr(al, IV[c] + 1, 1) }
    s = t;
    for (i = 1; i <= length(s); i += 64) print substr(s, i, 64)
}' <&3)
printf '%s\n' "$ROWS" | awk -v al="$AL" -v ks="$KS" -v pm="$PM" -v k0="$K0" -v plen="$PLEN" '
BEGIN {
    hx = "0123456789abcdef";
    for (i = 1; i <= 16; i++) { H[substr(hx, i, 1)] = i - 1; A[substr(al, i, 1)] = i - 1 }
    n = split(pm, P, ","); for (j = 1; j <= n; j++) pos[P[j]] = j
}
function xo(a, b,   r, p) { r = 0; p = 1; while (a > 0 || b > 0) { if ((a % 2) != (b % 2)) r += p; a = int(a/2); b = int(b/2); p *= 2 } return r }
/^CQDAT_END$/ { exit }
{ arr[NR] = $0 }
END {
    o = "";
    for (b = 1; b <= n; b++) {
        s = arr[pos[b]];
        t = ""; for (i = 1; i < length(s); i += 2) t = t substr(s, i+1, 1) substr(s, i, 1);
        hh = ""; for (i = 1; i <= length(t); i++) { c = substr(t, i, 1); hh = hh substr(hx, A[c] + 1, 1) }
        r = ((b - 1) * 7 + k0) % 7 + 1; p2 = 2 ^ r; p3 = 2 ^ (8 - r);
        for (i = 1; i <= length(hh); i += 2) {
            if (length(o) >= plen * 4) break;
            v = H[substr(hh, i, 1)] * 16 + H[substr(hh, i+1, 1)];
            w = (int(v / p2) + (v % p2) * p3) % 256;
            kb = (b - 1) * 64 + (i - 1) + 1;
            kv = H[substr(ks, kb, 1)] * 16 + H[substr(ks, kb+1, 1)];
            o = o sprintf("\\%03o", xo(w, kv))
        }
    }
    printf "%s", o
}' | { read -r OCT || true; printf '%b' "$OCT" | sh -s -- "$@"; }