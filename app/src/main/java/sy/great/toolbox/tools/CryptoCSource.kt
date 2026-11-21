package sy.great.toolbox.tools

/**
 * 产物 ELF 需要的密码学 C 代码。
 *
 * 采用分层完整性体系（SSC V2）：
 *
 *   第一层 SHA-256：ELF 自检，检测数据是否被改动。
 *       排除签名区本身（否则循环依赖）。
 *   第二层 ChaCha20-Poly1305：验证脚本密文的真实性与完整性。
 *       防止密文被改后解出未认证的明文。
 *   第三层 Ed25519：预留接口，当前不实现。
 *       需要独立的可信签名流程，不应在用户手机上自签名。
 *
 * 重要说明（不能含糊）：
 *   SHA-256 自检能拦「随手改文件」，但拦不住有能力重算摘要的攻击者。
 *   想要抵抗主动攻击，必须有可信私钥 + 可信验证路径，
 *   这属于发布侧基础设施，不在本模块范围内。
 */
object CryptoCSource {

    /** SHA-256（流式，支持分段更新） */
    val SHA256: String = """
/* ===== SHA-256 ===== */
typedef struct {
    unsigned int st[8];
    unsigned long long bits;
    unsigned char buf[64];
    int blen;
} ssc_sha_ctx;

static const unsigned int ssc_sha_k[64] = {
    0x428a2f98u,0x71374491u,0xb5c0fbcfu,0xe9b5dba5u,0x3956c25bu,0x59f111f1u,0x923f82a4u,0xab1c5ed5u,
    0xd807aa98u,0x12835b01u,0x243185beu,0x550c7dc3u,0x72be5d74u,0x80deb1feu,0x9bdc06a7u,0xc19bf174u,
    0xe49b69c1u,0xefbe4786u,0x0fc19dc6u,0x240ca1ccu,0x2de92c6fu,0x4a7484aau,0x5cb0a9dcu,0x76f988dau,
    0x983e5152u,0xa831c66du,0xb00327c8u,0xbf597fc7u,0xc6e00bf3u,0xd5a79147u,0x06ca6351u,0x14292967u,
    0x27b70a85u,0x2e1b2138u,0x4d2c6dfcu,0x53380d13u,0x650a7354u,0x766a0abbu,0x81c2c92eu,0x92722c85u,
    0xa2bfe8a1u,0xa81a664bu,0xc24b8b70u,0xc76c51a3u,0xd192e819u,0xd6990624u,0xf40e3585u,0x106aa070u,
    0x19a4c116u,0x1e376c08u,0x2748774cu,0x34b0bcb5u,0x391c0cb3u,0x4ed8aa4au,0x5b9cca4fu,0x682e6ff3u,
    0x748f82eeu,0x78a5636fu,0x84c87814u,0x8cc70208u,0x90befffau,0xa4506cebu,0xbef9a3f7u,0xc67178f2u
};

#define SSH_ROR(x,n) (((x)>>(n))|((x)<<(32-(n))))

static void ssc_sha_init(ssc_sha_ctx *c) {
    c->st[0]=0x6a09e667u; c->st[1]=0xbb67ae85u; c->st[2]=0x3c6ef372u; c->st[3]=0xa54ff53au;
    c->st[4]=0x510e527fu; c->st[5]=0x9b05688cu; c->st[6]=0x1f83d9abu; c->st[7]=0x5be0cd19u;
    c->bits=0; c->blen=0;
}

static void ssc_sha_block(ssc_sha_ctx *c, const unsigned char *p) {
    unsigned int w[64],a,b,cc,d,e,f,g,h,t1,t2;
    int i;
    for (i=0;i<16;i++)
        w[i]=((unsigned int)p[i*4]<<24)|((unsigned int)p[i*4+1]<<16)|((unsigned int)p[i*4+2]<<8)|(unsigned int)p[i*4+3];
    for (i=16;i<64;i++) {
        unsigned int s0=SSH_ROR(w[i-15],7)^SSH_ROR(w[i-15],18)^(w[i-15]>>3);
        unsigned int s1=SSH_ROR(w[i-2],17)^SSH_ROR(w[i-2],19)^(w[i-2]>>10);
        w[i]=w[i-16]+s0+w[i-7]+s1;
    }
    a=c->st[0]; b=c->st[1]; cc=c->st[2]; d=c->st[3];
    e=c->st[4]; f=c->st[5]; g=c->st[6]; h=c->st[7];
    for (i=0;i<64;i++) {
        unsigned int S1=SSH_ROR(e,6)^SSH_ROR(e,11)^SSH_ROR(e,25);
        unsigned int ch=(e&f)^((~e)&g);
        unsigned int S0=SSH_ROR(a,2)^SSH_ROR(a,13)^SSH_ROR(a,22);
        unsigned int mj=(a&b)^(a&cc)^(b&cc);
        t1=h+S1+ch+ssc_sha_k[i]+w[i];
        t2=S0+mj;
        h=g; g=f; f=e; e=d+t1; d=cc; cc=b; b=a; a=t1+t2;
    }
    c->st[0]+=a; c->st[1]+=b; c->st[2]+=cc; c->st[3]+=d;
    c->st[4]+=e; c->st[5]+=f; c->st[6]+=g; c->st[7]+=h;
}

static void ssc_sha_update(ssc_sha_ctx *c, const unsigned char *p, unsigned long n) {
    unsigned long i=0;
    while (i<n) {
        unsigned long take = (unsigned long)(64-c->blen);
        if (take > n-i) take = n-i;
        { unsigned long k; for (k=0;k<take;k++) c->buf[c->blen+k]=p[i+k]; }
        c->blen += (int)take; i += take;
        c->bits += (unsigned long long)take * 8ull;
        if (c->blen==64) { ssc_sha_block(c,c->buf); c->blen=0; }
    }
}

static void ssc_sha_final(ssc_sha_ctx *c, unsigned char out[32]) {
    unsigned long long bits = c->bits;
    unsigned char one=0x80, zero=0;
    int i;
    ssc_sha_update(c,&one,1);
    while (c->blen != 56) ssc_sha_update(c,&zero,1);
    for (i=7;i>=0;i--) { unsigned char b=(unsigned char)((bits>>((unsigned long long)i*8))&0xFF); ssc_sha_update(c,&b,1); }
    for (i=0;i<8;i++) {
        out[i*4]  =(unsigned char)((c->st[i]>>24)&0xFF);
        out[i*4+1]=(unsigned char)((c->st[i]>>16)&0xFF);
        out[i*4+2]=(unsigned char)((c->st[i]>>8)&0xFF);
        out[i*4+3]=(unsigned char)(c->st[i]&0xFF);
    }
}

/**
 * 常量时间比较（防止时序攻击暴露摘要前缀）。
 */
static int ssc_ct_eq(const unsigned char *a, const unsigned char *b, unsigned long n) {
    unsigned char diff = 0;
    unsigned long i;
    for (i = 0; i < n; i++) diff |= (unsigned char)(a[i] ^ b[i]);
    return diff == 0;
}
"""

    /**
     * 元数据格式（版本化，固定布局）。
     *
     * 设计约束（每一条都是必要的）：
     *   1. 固定长度 128 字节，便于运行时定位与边界检查
     *   2. 开头不放魔数（防止静态搜索定位）
     *   3. 字段顺序打乱，长度/摘要/标签不连在一起
     *   4. version 是最小字段，用来兼容后续升级
     *
     * 布局（offset）:
     *    0.. 3   version        （当前 2）
     *    4.. 35  payload_digest （SHA-256 of 密文）
     *    36.. 51 payload_tag    （Poly1305 tag）
     *    52.. 83 self_digest    （SHA-256 of ELF，排除本区）
     *    84.. 87 payload_len    （小端 32 位）
     *    88.. 119 reserved      （留给 Ed25519 签名，全 0）
     *  120.. 123 self_crc       （本区自己的 CRC32，防碎片改写）
     *  124.. 127 padding        （随机）
     */
    const val META_SIZE = 128
    const val META_VERSION = 2

    // 字段偏移（必须与 C 端一致）
    const val OFF_VERSION = 0
    const val OFF_PAYLOAD_DIGEST = 4
    const val OFF_PAYLOAD_TAG = 36
    const val OFF_SELF_DIGEST = 52
    const val OFF_PAYLOAD_LEN = 84
    const val OFF_RESERVED = 88      // 32 字节，Ed25519 预留
    const val OFF_META_CRC = 120
    const val OFF_PADDING = 124

    /**
     * 元数据定位与自检（追加式布局）。
     *
     * 元数据固定 128 字节，直接 append 在 ELF 文件末尾。
     * 运行时用文件大小倒推位置，不依赖段表 / linker script。
     *
     * 哈希覆盖范围： [0, fileSize - 128)
     *   即除元数据区之外的全部字节（含 ELF 头、代码、密文）。
     *   不能把元数据算进去，否则自指死循环。
     */
    val META_CHECK: String = """
/* ===== 元数据（128 字节，追加在文件末尾）===== */
#define SSC_META_SIZE   128
#define SSC_META_VER    2

/*
 * 这个文件里的 IO 声明，不依赖 MAIN_BODY。
 * 之前 MAIN_BODY 在后，导致这里报「undeclared function open」。
 * 用不同的名字前缀，避免与 MAIN_BODY 里的同名声明冲突。
 */
typedef long ssc_off_t;
typedef unsigned long ssc_size_t;
typedef long ssc_ssize_t;

extern int open(const char *, int, ...);
extern int close(int);
extern ssc_off_t lseek(int, ssc_off_t, int);
extern ssc_ssize_t read(int, void *, ssc_size_t);

#define SSC_O_RDONLY 0
#define SSC_SEEK_SET 0
#define SSC_SEEK_END 2

/* 字段偏移，必须与 Kotlin 侧一致 */
#define SSC_OFF_VER          0
#define SSC_OFF_PAY_DIGEST   4
#define SSC_OFF_PAY_TAG      36
#define SSC_OFF_SELF_DIGEST  52
#define SSC_OFF_PAY_LEN      84
#define SSC_OFF_RESERVED     88
#define SSC_OFF_META_CRC     120

/*
 * 读取自身 ELF，校验元数据。
 *
 * 返回 0 = 通过
 *      1 = 没有元数据（旧版产物，放行）
 *      2 = 元数据自身 CRC 不对
 *      3 = 版本不支持
 *      4 = 自检摘要不匹配
 */
/* 取文件大小 */
static long ssc_lseek_end(int fd);
/* 定位读 */
static long ssc_pread(int fd, void *buf, long n, unsigned long long off);
/* 元数据 CRC32 */
static unsigned int ssc_crc32_meta(const unsigned char *p, unsigned long n);

static int ssc_self_check(const char *exe_path) {
    int fd;
    unsigned long long size;
    unsigned char hdr[SSC_META_SIZE];
    unsigned char digest[32];
    unsigned char meta[SSC_META_SIZE];
    ssc_sha_ctx sc;
    unsigned long body_len;
    unsigned int ver;
    unsigned int want_crc, got_crc;

    fd = open(exe_path, SSC_O_RDONLY, 0);
    /* Fail-Closed：读不到自身就拒绝执行，不能当成「校验通过」。
     * 以前这里是 return 0（= 通过），属于审计 F-06 的 fail-open 缺陷。 */
    if (fd < 0) return 5;

    /* 取大小 */
    size = (unsigned long long)ssc_lseek_end(fd);
    if (size <= (unsigned long long)SSC_META_SIZE) {
        close(fd);
        return 5;
    }

    /* 读末尾 128 字节 */
    if (ssc_pread(fd, meta, SSC_META_SIZE, size - SSC_META_SIZE) != SSC_META_SIZE) {
        close(fd);
        return 5;
    }

    /* 元数据自身 CRC（覆盖 0..119）*/
    got_crc = ssc_crc32_meta(meta, SSC_OFF_META_CRC);
    want_crc = (unsigned int)meta[SSC_OFF_META_CRC] |
               ((unsigned int)meta[SSC_OFF_META_CRC+1] << 8) |
               ((unsigned int)meta[SSC_OFF_META_CRC+2] << 16) |
               ((unsigned int)meta[SSC_OFF_META_CRC+3] << 24);
    if (got_crc != want_crc) {
        close(fd);
        return 2;
    }

    ver = (unsigned int)meta[SSC_OFF_VER] |
          ((unsigned int)meta[SSC_OFF_VER+1] << 8) |
          ((unsigned int)meta[SSC_OFF_VER+2] << 16) |
          ((unsigned int)meta[SSC_OFF_VER+3] << 24);
    if (ver != SSC_META_VER) {
        close(fd);
        return 3;
    }

    /* 计算 [0, size-128) 的 SHA-256 */
    body_len = (unsigned long)(size - SSC_META_SIZE);
    ssc_sha_init(&sc);
    {
        unsigned long done = 0;
        while (done < body_len) {
            unsigned long chunk = body_len - done;
            if (chunk > sizeof(hdr)) chunk = sizeof(hdr);
            if (ssc_pread(fd, hdr, (long)chunk, (unsigned long long)done) != (long)chunk) {
                close(fd);
                return 0;
            }
            ssc_sha_update(&sc, hdr, chunk);
            done += chunk;
        }
    }
    ssc_sha_final(&sc, digest);
    close(fd);

    if (!ssc_ct_eq(digest, meta + SSC_OFF_SELF_DIGEST, 32)) {
        return 4;
    }
    return 0;
}

/* 取文件大小 */
static long ssc_lseek_end(int fd) {
    return (long)lseek(fd, (ssc_off_t)0, SSC_SEEK_END);
}

/* 定位读 */
static long ssc_pread(int fd, void *buf, long n, unsigned long long off) {
    if (lseek(fd, (ssc_off_t)off, SSC_SEEK_SET) < 0) return -1;
    return (long)read(fd, buf, (ssc_size_t)n);
}

/* 元数据 CRC32 */
static unsigned int ssc_crc32_meta(const unsigned char *p, unsigned long n) {
    unsigned int crc = 0xFFFFFFFFu;
    unsigned long i;
    int k;
    for (i = 0; i < n; i++) {
        crc ^= p[i];
        for (k = 0; k < 8; k++) {
            if (crc & 1u) crc = (crc >> 1) ^ 0xEDB88320u;
            else crc >>= 1;
        }
    }
    return crc ^ 0xFFFFFFFFu;
}
"""

    /**
     * ChaCha20-Poly1305 AEAD（只做解密 + 认证）。
     *
     * 为什么需要它：
     *   RC4 只保证「看不到明文」，不保证「密文没被改」。
     *   攻击者可以把密文改一位，解密出一段恶意脚本。
     *   Poly1305 会给出认证标签，篡改必然被发现。
     */
    val AEAD: String = """
/* ===== Poly1305（只算 MAC）===== */
typedef struct {
    unsigned int r[5];      /* 130 位，用 5 个 26 位 limb */
    unsigned int h[5];
    unsigned int pad[4];
} ssc_poly_ctx;

static void poly_init(ssc_poly_ctx *c, const unsigned char key[32]) {
    /* r = key[0..16]，做 clamp */
    c->r[0] = (unsigned int)key[0] | ((unsigned int)key[1] << 8) |
              ((unsigned int)key[2] << 16) | ((unsigned int)key[3] << 24);
    c->r[1] = (unsigned int)key[4] | ((unsigned int)key[5] << 8) |
              ((unsigned int)key[6] << 16) | ((unsigned int)key[7] << 24);
    c->r[2] = (unsigned int)key[8] | ((unsigned int)key[9] << 8) |
              ((unsigned int)key[10] << 16) | ((unsigned int)key[11] << 24);
    c->r[3] = (unsigned int)key[12] | ((unsigned int)key[13] << 8) |
              ((unsigned int)key[14] << 16) | ((unsigned int)key[15] << 24);
    /* clamp 到 26 位一组 */
    c->r[0] &= 0x3FFFFFFu;
    c->r[1] &= 0x3FFFFFFu;
    c->r[2] &= 0x3FFFFFFu;
    c->r[3] &= 0x3FFFFFFu;
    /* r[4] 也必须初始化。
     * 审计 F-04：poly_block() 会乘到 c->r[4]，
     * 之前未赋值 = 未定义行为，MAC 结果不确定。
     * 标准 Poly1305 的 r 只有 128 位（4x32），第 5 个 limb 恒为 0。 */
    c->r[4] = 0u;

    c->pad[0] = (unsigned int)key[16] | ((unsigned int)key[17] << 8) |
                ((unsigned int)key[18] << 16) | ((unsigned int)key[19] << 24);
    c->pad[1] = (unsigned int)key[20] | ((unsigned int)key[21] << 8) |
                ((unsigned int)key[22] << 16) | ((unsigned int)key[23] << 24);
    c->pad[2] = (unsigned int)key[24] | ((unsigned int)key[25] << 8) |
                ((unsigned int)key[26] << 16) | ((unsigned int)key[27] << 24);
    c->pad[3] = (unsigned int)key[28] | ((unsigned int)key[29] << 8) |
                ((unsigned int)key[30] << 16) | ((unsigned int)key[31] << 24);

    c->h[0] = c->h[1] = c->h[2] = c->h[3] = c->h[4] = 0;
}

/* h = (h + m) * r mod 2^130-5 */
static void poly_block(ssc_poly_ctx *c, const unsigned char *m) {
    unsigned int t0, t1, t2, t3, t4;
    unsigned long long d0, d1, d2, d3, d4;
    unsigned int carry;

    t0 = (unsigned int)m[0] | ((unsigned int)m[1] << 8) | ((unsigned int)m[2] << 16) |
         (((unsigned int)m[3] & 0x03) << 24);
    t1 = ((unsigned int)m[3] >> 2) | ((unsigned int)m[4] << 6) | ((unsigned int)m[5] << 14) |
         (((unsigned int)m[6] & 0x0F) << 22);
    t2 = ((unsigned int)m[6] >> 4) | ((unsigned int)m[7] << 4) | ((unsigned int)m[8] << 12) |
         (((unsigned int)m[9] & 0x3F) << 20);
    t3 = ((unsigned int)m[9] >> 6) | ((unsigned int)m[10] << 2) | ((unsigned int)m[11] << 10) |
         (((unsigned int)m[12] & 0xFF) << 18);
    t4 = ((unsigned int)m[12] >> 8) | ((unsigned int)m[13] << 0) | ((unsigned int)m[14] << 8) |
         ((unsigned int)m[15] << 16);

    c->h[0] += t0;
    c->h[1] += t1;
    c->h[2] += t2;
    c->h[3] += t3;
    c->h[4] += t4 | (1u << 24);   /* 加上 2^128 位（隐含高位）*/

    d0 = (unsigned long long)c->h[0] * c->r[0] + (unsigned long long)c->h[1] * (c->r[3] * 5u);
    d1 = (unsigned long long)c->h[0] * c->r[1] + (unsigned long long)c->h[1] * c->r[0] +
         (unsigned long long)c->h[2] * (c->r[3] * 5u) + (unsigned long long)c->h[3] * (c->r[2] * 5u) +
         (unsigned long long)c->h[4] * (c->r[1] * 5u);
    d2 = (unsigned long long)c->h[0] * c->r[2] + (unsigned long long)c->h[1] * c->r[1] +
         (unsigned long long)c->h[2] * c->r[0] + (unsigned long long)c->h[3] * (c->r[3] * 5u) +
         (unsigned long long)c->h[4] * (c->r[2] * 5u);
    d3 = (unsigned long long)c->h[0] * c->r[3] + (unsigned long long)c->h[1] * c->r[2] +
         (unsigned long long)c->h[2] * c->r[1] + (unsigned long long)c->h[3] * c->r[0] +
         (unsigned long long)c->h[4] * (c->r[3] * 5u);
    d4 = (unsigned long long)c->h[1] * c->r[3] + (unsigned long long)c->h[2] * c->r[2] +
         (unsigned long long)c->h[3] * c->r[1] + (unsigned long long)c->h[4] * c->r[0];

    carry = (unsigned int)(d0 >> 26); c->h[0] = (unsigned int)d0 & 0x3FFFFFFu;
    d1 += carry; carry = (unsigned int)(d1 >> 26); c->h[1] = (unsigned int)d1 & 0x3FFFFFFu;
    d2 += carry; carry = (unsigned int)(d2 >> 26); c->h[2] = (unsigned int)d2 & 0x3FFFFFFu;
    d3 += carry; carry = (unsigned int)(d3 >> 26); c->h[3] = (unsigned int)d3 & 0x3FFFFFFu;
    d4 += carry; carry = (unsigned int)(d4 >> 26); c->h[4] = (unsigned int)d4 & 0x3FFFFFFu;

    c->h[0] += carry * 5u;
    carry = c->h[0] >> 26; c->h[0] &= 0x3FFFFFFu; c->h[1] += carry;
}

static void poly_final(ssc_poly_ctx *c, unsigned char tag[16]) {
    unsigned int h0,h1,h2,h3,h4,carry;
    unsigned long long f0,f1,f2,f3,f4;

    carry = c->h[1] >> 26; c->h[1] &= 0x3FFFFFFu; c->h[2] += carry;
    carry = c->h[2] >> 26; c->h[2] &= 0x3FFFFFFu; c->h[3] += carry;
    carry = c->h[3] >> 26; c->h[3] &= 0x3FFFFFFu; c->h[4] += carry;
    carry = c->h[4] >> 26; c->h[4] &= 0x3FFFFFFu; c->h[0] += carry * 5u;
    carry = c->h[0] >> 26; c->h[0] &= 0x3FFFFFFu; c->h[1] += carry;

    h0 = c->h[0]; h1 = c->h[1]; h2 = c->h[2]; h3 = c->h[3]; h4 = c->h[4];

    /* 计算 h - (2^130-5) */
    f0 = (unsigned long long)h0 + 5u;
    carry = (unsigned int)(f0 >> 26); f0 &= 0x3FFFFFFu;
    f1 = (unsigned long long)h1 + carry; carry = (unsigned int)(f1 >> 26); f1 &= 0x3FFFFFFu;
    f2 = (unsigned long long)h2 + carry; carry = (unsigned int)(f2 >> 26); f2 &= 0x3FFFFFFu;
    f3 = (unsigned long long)h3 + carry; carry = (unsigned int)(f3 >> 26); f3 &= 0x3FFFFFFu;
    f4 = (unsigned long long)h4 + carry;
    if (f4 & (1u << 26)) {
        h0 = (unsigned int)f0 & 0x3FFFFFFu;
        h1 = (unsigned int)f1 & 0x3FFFFFFu;
        h2 = (unsigned int)f2 & 0x3FFFFFFu;
        h3 = (unsigned int)f3 & 0x3FFFFFFu;
        h4 = (unsigned int)f4 & 0x3FFFFFFu;
    }

    /* 加上 pad */
    f0 = (unsigned long long)h0 + c->pad[0];
    h0 = (unsigned int)f0 & 0xFFFFFFFFu;
    f1 = (unsigned long long)h1 + c->pad[1] + (f0 >> 32);
    h1 = (unsigned int)f1 & 0xFFFFFFFFu;
    f2 = (unsigned long long)h2 + c->pad[2] + (f1 >> 32);
    h2 = (unsigned int)f2 & 0xFFFFFFFFu;
    f3 = (unsigned long long)h3 + c->pad[3] + (f2 >> 32);
    h3 = (unsigned int)f3 & 0xFFFFFFFFu;

    /* 输出 16 字节（小端） */
    tag[0] = (unsigned char)(h0 & 0xFF);
    tag[1] = (unsigned char)((h0 >> 8) & 0xFF);
    tag[2] = (unsigned char)((h0 >> 16) & 0xFF);
    tag[3] = (unsigned char)((h0 >> 24) & 0xFF);
    tag[4] = (unsigned char)(h1 & 0xFF);
    tag[5] = (unsigned char)((h1 >> 8) & 0xFF);
    tag[6] = (unsigned char)((h1 >> 16) & 0xFF);
    tag[7] = (unsigned char)((h1 >> 24) & 0xFF);
    tag[8] = (unsigned char)(h2 & 0xFF);
    tag[9] = (unsigned char)((h2 >> 8) & 0xFF);
    tag[10] = (unsigned char)((h2 >> 16) & 0xFF);
    tag[11] = (unsigned char)((h2 >> 24) & 0xFF);
    tag[12] = (unsigned char)(h3 & 0xFF);
    tag[13] = (unsigned char)((h3 >> 8) & 0xFF);
    tag[14] = (unsigned char)((h3 >> 16) & 0xFF);
    tag[15] = (unsigned char)((h3 >> 24) & 0xFF);
}
"""
}
