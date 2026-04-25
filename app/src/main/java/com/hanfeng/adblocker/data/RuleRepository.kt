package com.HanFeng.data

import android.content.Context
import com.HanFeng.R
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.HanFeng.model.BlockRule
import com.HanFeng.model.RuleSource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

object RuleRepository {
    private const val PREFS = "rule_repo"
    private const val KEY_RULES = "rules"
    private const val KEY_UNSUPPORTED_RULES = "unsupported_rules"
    private const val KEY_CUSTOM_VENDORS = "custom_vendors"
    private const val KEY_UNKNOWN_VENDOR_SAMPLES = "unknown_vendor_samples"
    private const val KEY_BUNDLED_RULES_VERSION = "bundled_rules_version"
    private const val DEFAULT_VENDOR = "其它 (Other)"
    private const val GENERIC_AD_VENDOR = "通用广告/追踪 (Generic Ad/Tracking)"
    private const val BYPASS_PROTECTION_VENDOR = "加密 DNS 反绕过 (Encrypted DNS)"
    private const val UNSUPPORTED_VENDOR = "暂不支持/复杂规则 (Unsupported)"
    private const val BUNDLED_RULES_VERSION = 17
    private const val SUSPICIOUS_SAMPLE_DEBOUNCE_MILLIS = 5_000L
    private val gson = Gson()
    private val timeFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val cacheLock = Any()
    @Volatile private var cachedRules: List<BlockRule>? = null
    @Volatile private var cachedUnsupportedRules: List<BlockRule>? = null
    @Volatile private var cachedBlockedDomains: Set<String>? = null
    @Volatile private var cachedRuleMap: Map<String, BlockRule>? = null
    @Volatile private var cachedCustomVendors: Map<String, String>? = null
    private val adKeywords = listOf(
        "ad",
        "ads",
        "adn",
        "adnet",
        "adservice",
        "adserver",
        "adview",
        "admob",
        "adx",
        "track",
        "tracking",
        "analytics",
        "beacon",
        "monitor",
        "sdk",
        "ssp",
        "dsp",
        "rtb",
        "bid",
        "union",
        "unionad",
        "promotion",
        "advert",
        "measure",
        "mediation",
        "interstitial",
        "reward",
        "splash",
        "nativead",
        "mbridge",
        "pangle",
        "gdt",
        "qxm",
        "ubix",
        "zghd",
        "zhongguan",
        "doubleclick"
    )
    private val novelVendorNames = setOf(
        "番茄小说 (Fanqie Novel)",
        "七猫小说 (Qimao Novel)",
        "起点读书 (Qidian Reader)",
        "QQ阅读 (QQ Reader)",
        "书旗小说 (Shuqi Novel)",
        "掌阅 (iReader)",
        "咪咕阅读 (Migu Read)",
        "米读小说 (Midu Novel)",
        "纵横小说 (Zongheng Novel)",
        "17K 小说 (17K Novel)",
        "长读小说 (Changdu Novel)"
    )
    private val novelAppIdentifiers = listOf(
        "番茄小说", "番茄免费小说", "fanqie", "fqnovel", "dragon.read",
        "七猫小说", "七猫免费小说", "qimao", "kmxs", "wtzw",
        "起点读书", "qidian", "qdreader", "yuewen",
        "qq阅读", "qqreader", "qqread", "weread",
        "书旗小说", "shuqi", "aliwx",
        "掌阅", "ireader", "zhangyue",
        "咪咕阅读", "migu", "cmread",
        "米读小说", "midu", "miduread", "lechuan",
        "纵横小说", "zongheng", "zhread",
        "17k", "17k小说", "book17k",
        "长读小说", "changdu"
    )
    private val novelAppProtectedSuffixes = setOf(
        "wtzw.com",
        "qimao.com",
        "kmxs.com",
        "fqnovel.com",
        "fanqienovel.com",
        "zijieapi.com",
        "qidian.com",
        "yuewen.com",
        "readnovel.com",
        "hongxiu.com",
        "xxsy.net",
        "qqreader.com",
        "reader.qq.com",
        "shuqi.com",
        "ireader.com",
        "zhangyue.com",
        "cmread.com",
        "migu.cn",
        "migu.com",
        "midu.com",
        "zongheng.com",
        "17k.com",
        "changdu.com"
    )
    private val novelAggressiveVendorNames = setOf(
        "字节跳动 (ByteDance)",
        "快手 (Kuaishou)",
        "腾讯 (Tencent)",
        "网易 (NetEase)",
        "优比客思 (UBIX Ads)",
        "QXM (QXM Ads)",
        "中关互动 (ZGHD)",
        "趣盟广告 (Qumeng Ads)",
        "AdScope 聚合广告 (AdScope)",
        "通用广告/追踪 (Generic Ad/Tracking)"
    )
    private val novelAggressiveExactDomains = setOf(
        "ad.hunyuan.tencent.com",
        "dsp-creative.ubixioe.com",
        "adx-data-u1.ubixioe.com",
        "ade-rtb.netease.com",
        "adtrack.e.kuaishou.com",
        "v4-lm.adukwai.com",
        "log-api.pangolin-sdk-toutiao-b.com",
        "p2-pro.a.yximgs.com"
    )
    private val bypassProtectionDomains = setOf(
        "dns.alidns.com",
        "httpdns.alicdn.com",
        "doh.pub",
        "dot.pub",
        "dns.google",
        "dns.google.com",
        "dns64.dns.google",
        "cloudflare-dns.com",
        "one.one.one.one",
        "mozilla.cloudflare-dns.com",
        "chrome.cloudflare-dns.com",
        "security.cloudflare-dns.com",
        "family.cloudflare-dns.com",
        "dns.quad9.net",
        "dns10.quad9.net",
        "dns11.quad9.net",
        "dns.adguard-dns.com",
        "dns-family.adguard.com",
        "dns-unfiltered.adguard.com",
        "dns.nextdns.io",
        "doh.opendns.com",
        "dns.umbrella.com",
        "dns64.steward.net",
        "family-filter-dns.cleanbrowsing.org",
        "security-filter-dns.cleanbrowsing.org",
        "adult-filter-dns.cleanbrowsing.org",
        "httpdns.bcelive.com",
        "httpdns.baidu.com",
        "httpdns.n.netease.com",
        "httpdns.music.163.com",
        "dns.weilter.net",
        "doh.360.cn",
        "dot.360.cn",
        "dns.baidu.com",
        "doh.baidu.com"
    )
    private val vendorPatterns = linkedMapOf(
        "腾讯 (Tencent)" to listOf(
            "gdt", "qq", "e.qq", "tencent", "wechat", "weixin", "qcloud", "bugly", "qzone", "qzs",
            "gtimg", "imtt", "myapp", "sogou", "iegcom", "tmead", "music.qq", "y.qq", "kuwo", "kugou", "kgimg",
            "qpic", "idqqimg", "tenpay", "tenvideo", "qlogo", "wechatpay", "qweather"
        ),
        "字节跳动 (ByteDance)" to listOf(
            "pangle", "pangolin", "oceanengine", "bytedance", "bytecdn", "toutiao", "snssdk", "douyin",
            "amemv", "volces", "tiktok", "musical.ly", "toutiaocloud", "jinritemai", "zijieapi", "isnssdk", "ibytedtos",
            "lf3", "lf6", "ixigua", "bdxigua"
        ),
        "番茄小说 (Fanqie Novel)" to listOf(
            "fanqie", "fanqienovel", "fqnovel", "dragon.read", "reading.snssdk", "tomato.read", "fqnovelvod", "novel.snssdk"
        ),
        "七猫小说 (Qimao Novel)" to listOf(
            "qimao", "kmxs", "wtzw", "sevencat", "qmread", "qmks", "qimaoad"
        ),
        "起点读书 (Qidian Reader)" to listOf(
            "qidian", "qdreader", "yuewen", "readnovel", "hongxiu", "xxsy", "qdbook", "ywstatic", "qdmm"
        ),
        "QQ阅读 (QQ Reader)" to listOf(
            "qqreader", "reader.qq", "qqbook", "qqread", "weread", "book.qq"
        ),
        "书旗小说 (Shuqi Novel)" to listOf(
            "shuqi", "shuqiapi", "aliwx", "sqnovel", "shuqireader", "shuqiimg", "sqxs"
        ),
        "掌阅 (iReader)" to listOf(
            "ireader", "zhangyue", "zyreader", "chaozh", "iread", "zyad", "ireadad"
        ),
        "咪咕阅读 (Migu Read)" to listOf(
            "migu", "miguread", "cmread", "wap.cmread", "miguvideo", "migulive"
        ),
        "米读小说 (Midu Novel)" to listOf(
            "midu", "miduread", "miduoke", "lechuan", "midubook", "miduad", "midusdk"
        ),
        "纵横小说 (Zongheng Novel)" to listOf(
            "zongheng", "zongheng.com", "zhread", "zonghengad"
        ),
        "17K 小说 (17K Novel)" to listOf(
            "17k", "17k.com", "book17k", "read17k", "17kimg"
        ),
        "长读小说 (Changdu Novel)" to listOf(
            "changdu", "changdu.com", "changduad", "cdsdk"
        ),
        "阿里巴巴集团 (Alibaba Group)" to listOf(
            "alibaba", "alibabagroup", "alipay", "taobao", "tmall", "aliyun", "alimama", "tanx", "umeng",
            "ucads", "ucweb", "mmstat", "ut.taobao", "union.taobao", "ad.aliyun", "youku", "ykimg", "ykad", "amap", "eleme",
            "alicdn", "adashx", "koubei", "fliggy", "etao", "xiami", "gaode"
        ),
        "百度 (Baidu)" to listOf(
            "baidu", "mobads", "duapps", "baidustatic", "cpro", "dueros", "hao123", "baidubce", "bdimg",
            "bdstatic", "baidubcs", "tieba", "haokan", "quanmin", "box.baidu"
        ),
        "快手 (Kuaishou)" to listOf(
            "kuaishou", "kwai", "kwad", "kwaiad", "adkwai", "ksad", "yximgs", "gifshow"
        ),
        "华为 (Huawei)" to listOf(
            "huawei", "hicloud", "hispace", "hms", "hwcloud", "openalliance", "ads-drcn", "petal"
        ),
        "小米 (Xiaomi)" to listOf(
            "xiaomi", "miui", "mistat", "ad.xiaomi", "tracking.miui", "mi.com", "duokan"
        ),
        "OPPO (HeyTap)" to listOf(
            "oppo", "heytap", "coloros", "aps", "adx.ads.heytap", "cp01", "oppomobile"
        ),
        "vivo (vivo Ads)" to listOf(
            "vivo", "iqoo", "ads.vivo", "adlog.vivo", "vivoglobal", "bbk"
        ),
        "QXM (QXM Ads)" to listOf(
            "qxm", "qxmad", "qxmads", "52qumao", "qumao"
        ),
        "UBIX (UBIX Ads)" to listOf(
            "ubix", "ubixio", "ubixad", "ubxi", "ubixai", "ubiadx"
        ),
        "中关互动 (ZGHD)" to listOf(
            "zghd", "zhongguan", "zgad", "zhghd", "hxltad", "adintl"
        ),
        "荣耀 (Honor)" to listOf("honor", "honormagic", "ads.honor", "hihonor"),
        "京东 (JD.com)" to listOf("jingdong", "jad", "jrad", "ads-union.jd", "3.cn", "jcloud", "jdcloud", "jdwl"),
        "美团 (Meituan)" to listOf("meituan", "dianping", "maoyan", "union.meituan", "ad.meituan", "media.meituan", "sankuai", "meituan.net", "meituanstatic", "meituanad"),
        "趣盟广告 (Qumeng Ads)" to listOf("qumeng", "qmob", "qtmojo", "qmadsdk", "qumengad"),
        "网易 (NetEase)" to listOf("netease", "163", "youdao", "music.126", "adgeo.163", "netease.im"),
        "微博 (Weibo)" to listOf("weibo", "sinaimg", "alitui.weibo", "ad.weibo", "sina.cn"),
        "哔哩哔哩 (Bilibili)" to listOf("bilibili", "biliapi", "bilivideo", "cm.bilibili", "hdslb"),
        "爱奇艺 (iQIYI)" to listOf("iqiyi", "qiyi", "pps", "adx.qiyi", "msg.qy.net"),
        "搜狐 (Sohu)" to listOf("sohu", "sohucs", "aty.sohu"),
        "芒果 (MangoTV)" to listOf("mgtv", "hunantv", "ad.mgtv"),
        "拼多多 (PDD)" to listOf("pinduoduo", "yangkeduo", "pddpic", "pddimg"),
        "小红书 (Xiaohongshu)" to listOf("xiaohongshu", "xhscdn", "xhslink", "xhsimg"),
        "携程 (Trip.com)" to listOf("ctrip", "trip.com", "qunar", "tieshujia"),
        "360 (Qihoo 360)" to listOf("360.cn", "qhimg", "qhmsg", "so.com", "360safe", "360buyimg"),
        "极光 (Jiguang)" to listOf("jiguang", "jpush", "jmessage", "aurora", "jiguang.cn"),
        "个推 (Getui)" to listOf("getui", "igexin", "gexin", "getui.net"),
        "TalkingData (TalkingData)" to listOf("talkingdata", "tendcloud", "talkingdata.net"),
        "神策数据 (Sensors Data)" to listOf("sensorsdata", "sa-sdk", "sensorsdata.cn"),
        "秒针系统 (Miaozhen)" to listOf("miaozhen", "miaozhen.com"),
        "AdMaster (AdMaster)" to listOf("admaster", "admasterapi"),
        "Sigmob (Sigmob)" to listOf("sigmob", "sigmob.cn"),
        "MobTech (MobTech)" to listOf("mob.com", "mobpush", "sharesdk"),
        "Alphabet (Google)" to listOf(
            "google", "doubleclick", "admob", "googlesyndication", "googleadservices", "googleads", "gstatic",
            "googletagmanager", "google-analytics", "analytics.google", "firebase", "firebasead", "youtube",
            "ytimg", "crashlytics", "adservice.google"
        ),
        "Meta (Meta Platforms)" to listOf(
            "facebook", "fbcdn", "fbsbx", "meta", "instagram", "audiencenetwork", "whatsapp", "oculus"
        ),
        "Amazon (Amazon Ads)" to listOf("amazon", "amzn", "amazon-adsystem", "aaxads", "twitch", "imdb"),
        "Microsoft (Microsoft Ads)" to listOf(
            "microsoft", "msn", "bing", "xandr", "linkedin", "skype"
        ),
        "Apple (Apple Ads)" to listOf(
            "apple", "icloud", "itunes", "iad.apple", "appleadservices", "mzstatic", "cdn-apple"
        ),
        "Samsung (Samsung Ads)" to listOf(
            "samsung", "samsungads", "samsungacr", "samsungcloudcdn"
        ),
        "X (Twitter)" to listOf("twitter", "t.co", "twimg", "ads-twitter", "x.com"),
        "Snap (Snapchat)" to listOf("snapchat", "sc-cdn", "snapads", "snapkit", "feelinsonice"),
        "Pinterest (Pinterest)" to listOf("pinterest", "pinimg", "ads.pinterest"),
        "Reddit (Reddit)" to listOf("reddit", "redd.it", "redditmedia", "ads.reddit"),
        "Unity (Unity Ads)" to listOf("unityads", "unity3d", "unityads.unity3d", "delta-dna"),
        "AppLovin (AppLovin)" to listOf("applovin", "applvn", "applovinsdk", "maxads"),
        "ironSource (ironSource)" to listOf("ironsrc", "ironsource", "supersonicads", "unity-ironsource"),
        "Vungle (Liftoff)" to listOf("vungle", "liftoff", "vungleads", "liftoff.io"),
        "Chartboost (Chartboost)" to listOf("chartboost", "chartboosts"),
        "InMobi (InMobi)" to listOf("inmobi", "aerserv", "glancecdn"),
        "Mintegral (Mintegral)" to listOf("mintegral", "mobvista", "mbridge", "mtgads"),
        "Moloco (Moloco)" to listOf("moloco", "molocoads"),
        "The Trade Desk (TTD)" to listOf("thetradedesk", "adsrvr", "uidapi"),
        "PubMatic (PubMatic)" to listOf("pubmatic", "ads.pubmatic", "hbopenbid"),
        "PubNative (PubNative)" to listOf("pubnative", "pubnative.net", "hybid"),
        "Magnite (Magnite)" to listOf("magnite", "rubiconproject", "spotxchange", "spotx.tv"),
        "OpenX (OpenX)" to listOf("openx", "openx.net"),
        "Index Exchange (Index Exchange)" to listOf("indexww", "casalemedia", "indexexchange", "js-sec.indexww"),
        "Media.net (Media.net)" to listOf("media.net", "medianet", "contextual.media.net"),
        "Taboola (Taboola)" to listOf("taboola", "taboolasyndication"),
        "Outbrain (Outbrain)" to listOf("outbrain", "outbrainimg", "odb.outbrain"),
        "TripleLift (TripleLift)" to listOf("triplelift", "3lift"),
        "AdColony (AdColony)" to listOf("adcolony", "adc3"),
        "Ogury (Ogury)" to listOf("ogury", "adogy"),
        "Digital Turbine (DT Exchange)" to listOf("fyber", "inner-active", "iaacdn", "digitalturbine", "colossusssp"),
        "Smaato (Smaato)" to listOf("smaato", "smaato.net"),
        "Start.io (Start.io)" to listOf("startappservice", "start.io", "startapp"),
        "Tapjoy (Tapjoy)" to listOf("tapjoy", "tjvid", "ws.tapjoyads"),
        "Adjoe (adjoe)" to listOf("adjoe", "adjoe.zone"),
        "LoopMe (LoopMe)" to listOf("loopme", "loopme.me"),
        "Verve (Verve Group)" to listOf("verve", "adtilt", "vervewireless"),
        "HyprMX (HyprMX)" to listOf("hyprmx", "hyprmx.com"),
        "Smadex (Smadex)" to listOf("smadex", "smadex.com"),
        "Maio (Maio)" to listOf("maio", "maio.jp"),
        "Verizon Media (Yahoo/AOL)" to listOf("yahoo", "yimg", "aol", "flurry", "verizonmedia"),
        "Oracle (Oracle Ads)" to listOf("oracle", "moatads", "addthis", "bluekai"),
        "Criteo (Criteo)" to listOf("criteo", "criteo.net"),
        "Yandex (Yandex Ads)" to listOf("yandex", "yandexadexchange", "yastatic"),
        "VK (VK Ads)" to listOf("vk.com", "vkuser", "mytarget", "mail.ru"),
        "传音 (Transsion)" to listOf("transsion", "tecno", "infinix", "itel-mobile")
    )
    private val vendorKeywords = linkedMapOf(
        "腾讯 (Tencent)" to listOf(
            "tencent", "wechat", "weixin", "qq", "gdt", "bugly", "qcloud", "myapp", "kuwo", "kugou", "sogou", "tenpay", "qzone", "qimei"
        ),
        "字节跳动 (ByteDance)" to listOf(
            "bytedance", "douyin", "tiktok", "toutiao", "pangle", "oceanengine", "snssdk", "amemv", "ixigua", "gromore", "csj"
        ),
        "番茄小说 (Fanqie Novel)" to listOf(
            "fanqie", "fanqienovel", "fqnovel", "dragonread", "tomatonovel", "tomatoread", "novelsnssdk", "fqnovelvod"
        ),
        "七猫小说 (Qimao Novel)" to listOf(
            "qimao", "kmxs", "wtzw", "sevencat", "qimaoreader", "qmread", "qimaoad"
        ),
        "起点读书 (Qidian Reader)" to listOf(
            "qidian", "qdreader", "yuewen", "readnovel", "hongxiu", "xxsy", "qdbook", "qdmm", "ywstatic"
        ),
        "QQ阅读 (QQ Reader)" to listOf(
            "qqreader", "qqread", "qqbook", "readerqq", "weread", "bookqq"
        ),
        "书旗小说 (Shuqi Novel)" to listOf(
            "shuqi", "shuqinovel", "sqnovel", "aliwx", "shuqireader", "shuqiimg"
        ),
        "掌阅 (iReader)" to listOf(
            "ireader", "zhangyue", "chaozh", "zyreader", "iread", "zyad"
        ),
        "咪咕阅读 (Migu Read)" to listOf(
            "migu", "miguread", "cmread"
        ),
        "米读小说 (Midu Novel)" to listOf(
            "midu", "miduread", "lechuan", "midubook", "miduad"
        ),
        "纵横小说 (Zongheng Novel)" to listOf(
            "zongheng", "zhread"
        ),
        "17K 小说 (17K Novel)" to listOf(
            "17k", "book17k", "read17k"
        ),
        "长读小说 (Changdu Novel)" to listOf(
            "changdu", "changduad", "cdsdk"
        ),
        "阿里巴巴集团 (Alibaba Group)" to listOf(
            "alibaba", "taobao", "tmall", "alipay", "aliyun", "alimama", "umeng", "uc", "youku", "amap", "gaode", "eleme", "fliggy", "tanx", "mmstat", "adash"
        ),
        "百度 (Baidu)" to listOf(
            "baidu", "mobads", "cpro", "duapp", "tieba", "hao123", "dueros", "haokan", "baidumobads", "bdunion"
        ),
        "快手 (Kuaishou)" to listOf(
            "kuaishou", "kwai", "kwad", "gifshow", "ksad", "kwaiads", "kwaicdn"
        ),
        "华为 (Huawei)" to listOf(
            "huawei", "hms", "hicloud", "petal", "honor", "hispace", "openalliance", "hwads", "appgallery", "hwclouds"
        ),
        "小米 (Xiaomi)" to listOf(
            "xiaomi", "miui", "mistat", "miad", "mishop", "mipush", "miglobal", "redmi", "mitv", "mibox", "duokan", "mi"
        ),
        "OPPO (HeyTap)" to listOf(
            "oppo", "heytap", "coloros", "realme", "breeno", "oppomobile", "nearme"
        ),
        "vivo (vivo Ads)" to listOf(
            "vivo", "iqoo", "bbk", "vivoglobal", "jovi"
        ),
        "荣耀 (Honor)" to listOf(
            "honor", "hihonor", "magicui"
        ),
        "京东 (JD.com)" to listOf(
            "jd", "jingdong", "jrad", "jad", "3cn", "jdcloud", "jingxi", "paipai"
        ),
        "美团 (Meituan)" to listOf(
            "meituan", "dianping", "sankuai", "maoyan", "kuailv", "wmapi", "meituanad"
        ),
        "趣盟广告 (Qumeng Ads)" to listOf(
            "qumeng", "qmob", "qtmojo", "qmadsdk", "qumengad"
        ),
        "网易 (NetEase)" to listOf(
            "netease", "163", "youdao", "lofter", "music126", "mail163"
        ),
        "微博 (Weibo)" to listOf(
            "weibo", "sina", "sinaimg", "weibocdn"
        ),
        "哔哩哔哩 (Bilibili)" to listOf(
            "bilibili", "bili", "hdslb", "bilivideo", "biliapi"
        ),
        "爱奇艺 (iQIYI)" to listOf(
            "iqiyi", "qiyi", "pps", "qy", "71edge"
        ),
        "搜狐 (Sohu)" to listOf(
            "sohu", "sohucs", "focus"
        ),
        "芒果 (MangoTV)" to listOf(
            "mgtv", "mango", "hunantv", "mgad"
        ),
        "拼多多 (PDD)" to listOf(
            "pinduoduo", "yangkeduo", "pdd", "jinbao", "pddpic"
        ),
        "小红书 (Xiaohongshu)" to listOf(
            "xiaohongshu", "xiaohong", "xhs", "xhscdn", "xhslink"
        ),
        "携程 (Trip.com)" to listOf(
            "ctrip", "trip", "qunar", "tripcdn", "qunarzz"
        ),
        "360 (Qihoo 360)" to listOf(
            "360", "qihoo", "360safe", "qhimg", "so"
        ),
        "极光 (Jiguang)" to listOf(
            "jiguang", "jpush", "aurora", "janalytics", "jverification"
        ),
        "个推 (Getui)" to listOf(
            "getui", "gexin", "igexin", "gtpush"
        ),
        "TalkingData (TalkingData)" to listOf(
            "talkingdata", "tendcloud", "tdid"
        ),
        "神策数据 (Sensors Data)" to listOf(
            "sensorsdata", "sensors"
        ),
        "秒针系统 (Miaozhen)" to listOf(
            "miaozhen"
        ),
        "AdMaster (AdMaster)" to listOf(
            "admaster"
        ),
        "Sigmob (Sigmob)" to listOf(
            "sigmob"
        ),
        "MobTech (MobTech)" to listOf(
            "mobtech", "sharesdk", "mobpush", "moblink", "mobsec"
        ),
        "热云数据 (Reyun)" to listOf(
            "reyun", "trackingio", "reyun.com", "reyunad"
        ),
        "友盟+ (Umeng+)" to listOf(
            "umeng", "utdevice", "uappstat", "umtrack", "umtrack2", "utsystem"
        ),
        "穿山甲 (Pangle)" to listOf(
            "pangle", "pangolin", "csj", "gromore", "pangleglobal"
        ),
        "腾讯广告 (Tencent Ads)" to listOf(
            "gdt", "tmead", "eqq", "qqe2", "gdtimg", "gdt.qq"
        ),
        "百度联盟 (Baidu Union)" to listOf(
            "mobads", "cpro", "baidubes", "baidustat", "hm.baidu"
        ),
        "优量汇 (Tencent Marketing)" to listOf(
            "gdt", "eqq", "qqe2", "youlianghui"
        ),
        "快手联盟 (Kwai Business)" to listOf(
            "kwai", "kwad", "kuaishou", "kwaibusiness"
        ),
        "华为广告 (Huawei Ads)" to listOf(
            "openalliance", "hwads", "ads-drcn", "huaweiads"
        ),
        "小米广告 (Xiaomi Ads)" to listOf(
            "miad", "mistat", "ad.xiaomi", "tracking.miui"
        ),
        "OPPO 广告 (OPPO Ads)" to listOf(
            "heytap", "oppo", "nearme", "ads.heytap"
        ),
        "vivo 广告 (vivo Ads)" to listOf(
            "ads.vivo", "adlog.vivo", "vivoad", "vivo"
        ),
        "QXM (QXM Ads)" to listOf(
            "qxm", "qxmad", "qxmads", "52qumao", "qumao"
        ),
        "UBIX (UBIX Ads)" to listOf(
            "ubix", "ubixad", "ubixio", "ubxi", "ubixai", "ubiadx"
        ),
        "中关互动 (ZGHD)" to listOf(
            "zghd", "zhongguan", "zgad", "zhghd", "hxltad", "adintl"
        ),
        "Mintegral China (Mintegral)" to listOf(
            "mintegral", "mobvista", "mbridge", "mtgads"
        ),
        "TopOn (TopOn)" to listOf(
            "topon", "anythink", "toponad"
        ),
        "TradPlus (TradPlus)" to listOf(
            "tradplus", "tpbid", "tradplusad"
        ),
        "Beizi (Beizi)" to listOf(
            "beizi", "bzadx", "beizisdk"
        ),
        "AdScope (AdScope)" to listOf(
            "adscope", "aiclk", "adscopead"
        ),
        "Youmi (Youmi)" to listOf(
            "youmi", "adwo", "youmioffer"
        ),
        "多盟 (Domob)" to listOf(
            "domob", "duomeng"
        ),
        "易传媒 (Adsame)" to listOf(
            "adsame", "smartmad"
        ),
        "MediaV (MediaV)" to listOf(
            "mediav", "mvad", "mvads"
        ),
        "Bigo Ads (Bigo)" to listOf(
            "bigo", "bigo.sg", "likee"
        ),
        "Vpon (Vpon)" to listOf(
            "vpon", "vpadn"
        ),
        "Maticoo (Maticoo)" to listOf(
            "maticoo"
        ),
        "Kidoz (Kidoz)" to listOf(
            "kidoz"
        ),
        "Alphabet (Google)" to listOf(
            "google", "admob", "doubleclick", "firebase", "youtube", "gma", "adsense", "googleadmanager", "adservice"
        ),
        "Meta (Meta Platforms)" to listOf(
            "meta", "facebook", "instagram", "fb", "audiencenetwork", "whatsapp", "messenger"
        ),
        "Amazon (Amazon Ads)" to listOf(
            "amazon", "amzn", "aax", "twitch", "imdb", "aps.amazon"
        ),
        "Microsoft (Microsoft Ads)" to listOf(
            "microsoft", "msn", "bing", "xandr", "linkedin", "appnexus"
        ),
        "Apple (Apple Ads)" to listOf(
            "apple", "icloud", "itunes", "iad"
        ),
        "Samsung (Samsung Ads)" to listOf(
            "samsung"
        ),
        "X (Twitter)" to listOf(
            "twitter", "twimg", "tweet"
        ),
        "Snap (Snapchat)" to listOf(
            "snap", "snapchat"
        ),
        "Pinterest (Pinterest)" to listOf(
            "pinterest", "pin"
        ),
        "Reddit (Reddit)" to listOf(
            "reddit"
        ),
        "Unity (Unity Ads)" to listOf(
            "unity", "unityads", "delta-dna"
        ),
        "AppLovin (AppLovin)" to listOf(
            "applovin", "applvn", "max", "axon", "sparklabs"
        ),
        "ironSource (ironSource)" to listOf(
            "ironsource", "ironsrc", "supersonic", "levelplay"
        ),
        "Vungle (Liftoff)" to listOf(
            "vungle", "liftoff", "jetfuel"
        ),
        "Chartboost (Chartboost)" to listOf(
            "chartboost"
        ),
        "InMobi (InMobi)" to listOf(
            "inmobi", "aerserv"
        ),
        "Mintegral (Mintegral)" to listOf(
            "mintegral", "mobvista", "mbridge"
        ),
        "Moloco (Moloco)" to listOf(
            "moloco"
        ),
        "The Trade Desk (TTD)" to listOf(
            "ttd", "tradedesk", "adsrvr", "uid2", "uidapi"
        ),
        "PubMatic (PubMatic)" to listOf(
            "pubmatic", "openwrap", "hbopenbid"
        ),
        "PubNative (PubNative)" to listOf(
            "pubnative", "hybid"
        ),
        "Magnite (Magnite)" to listOf(
            "magnite", "rubicon", "spotx", "springserve"
        ),
        "OpenX (OpenX)" to listOf(
            "openx"
        ),
        "Index Exchange (Index Exchange)" to listOf(
            "indexexchange", "indexww", "casale", "jssecindexww"
        ),
        "Media.net (Media.net)" to listOf(
            "medianet", "contextualmedianet"
        ),
        "Taboola (Taboola)" to listOf(
            "taboola", "taboolasyndication"
        ),
        "Outbrain (Outbrain)" to listOf(
            "outbrain", "odboutbrain"
        ),
        "TripleLift (TripleLift)" to listOf(
            "triplelift"
        ),
        "AdColony (AdColony)" to listOf(
            "adcolony"
        ),
        "Ogury (Ogury)" to listOf(
            "ogury"
        ),
        "Digital Turbine (DT Exchange)" to listOf(
            "digitalturbine", "fyber", "inneractive", "dtexchange", "colossusssp"
        ),
        "Smaato (Smaato)" to listOf(
            "smaato"
        ),
        "Start.io (Start.io)" to listOf(
            "startio", "startapp"
        ),
        "Tapjoy (Tapjoy)" to listOf(
            "tapjoy"
        ),
        "Adjoe (adjoe)" to listOf(
            "adjoe"
        ),
        "LoopMe (LoopMe)" to listOf(
            "loopme"
        ),
        "Verve (Verve Group)" to listOf(
            "verve", "adtilt"
        ),
        "HyprMX (HyprMX)" to listOf(
            "hyprmx"
        ),
        "Smadex (Smadex)" to listOf(
            "smadex"
        ),
        "Maio (Maio)" to listOf(
            "maio"
        ),
        "Verizon Media (Yahoo/AOL)" to listOf(
            "yahoo", "aol", "flurry", "verizonmedia"
        ),
        "Oracle (Oracle Ads)" to listOf(
            "oracle", "moat", "bluekai", "addthis", "grapeshot"
        ),
        "Criteo (Criteo)" to listOf(
            "criteo", "hooklogic"
        ),
        "Yandex (Yandex Ads)" to listOf(
            "yandex", "appmetrica"
        ),
        "VK (VK Ads)" to listOf(
            "vk", "mytarget", "mailru", "vkad"
        ),
        "传音 (Transsion)" to listOf(
            "transsion", "tecno", "infinix", "itel", "phoenixbrowser"
        )
    )
    private val vendorSdkIdentifiers = linkedMapOf(
        "番茄小说 (Fanqie Novel)" to listOf(
            "番茄小说",
            "fanqie",
            "fqnovel",
            "com.dragon.read",
            "dragon.read"
        ),
        "七猫小说 (Qimao Novel)" to listOf(
            "七猫小说",
            "qimao",
            "com.kmxs.reader",
            "kmxs",
            "wtzw"
        ),
        "起点读书 (Qidian Reader)" to listOf(
            "起点读书",
            "qidian",
            "qdreader",
            "com.qidian.QDReader",
            "yuewen"
        ),
        "QQ阅读 (QQ Reader)" to listOf(
            "QQ阅读",
            "qqreader",
            "com.qq.reader",
            "qqread"
        ),
        "书旗小说 (Shuqi Novel)" to listOf(
            "书旗小说",
            "shuqi",
            "com.shuqi.controller",
            "aliwx"
        ),
        "掌阅 (iReader)" to listOf(
            "掌阅",
            "ireader",
            "com.chaozh.iReaderFree",
            "zhangyue"
        ),
        "咪咕阅读 (Migu Read)" to listOf(
            "咪咕阅读",
            "migu",
            "cmread",
            "com.ophone.reader.ui"
        ),
        "米读小说 (Midu Novel)" to listOf(
            "米读小说",
            "midu",
            "miduread",
            "com.lechuan.mdwz"
        ),
        "纵横小说 (Zongheng Novel)" to listOf(
            "纵横小说",
            "zongheng",
            "com.zongheng.reader"
        ),
        "17K 小说 (17K Novel)" to listOf(
            "17k小说",
            "17k",
            "book17k"
        ),
        "长读小说 (Changdu Novel)" to listOf(
            "长读小说",
            "changdu",
            "com.changdu.ereader"
        ),
        "QXM (QXM Ads)" to listOf(
            "qxm",
            "趣小猫广告",
            "com.qxm.ad",
            "com.qumao.ad",
            "52qumao"
        ),
        "UBIX (UBIX Ads)" to listOf(
            "ubxi",
            "ubix",
            "ubiadx",
            "ubixai",
            "com.ubix.ad",
            "com.ubixai.sdk"
        ),
        "中关互动 (ZGHD)" to listOf(
            "中关互动",
            "hxltad",
            "adintl",
            "com.zghd.ad",
            "com.hxltad.sdk",
            "com.adintl.ad"
        ),
        "趣盟广告 (Qumeng Ads)" to listOf(
            "qumeng",
            "qmob",
            "qtmojo",
            "qmadsdk",
            "com.qumeng.ad",
            "com.qumeng.advlib"
        )
    )
    private val vendorAliases = mapOf(
        "Google (Google Ads)" to "Alphabet (Google)",
        "Meta (Facebook)" to "Meta (Meta Platforms)",
        "阿里 (Alibaba)" to "阿里巴巴集团 (Alibaba Group)",
        "友盟+ (Umeng+)" to "阿里巴巴集团 (Alibaba Group)",
        "优酷 (Youku)" to "阿里巴巴集团 (Alibaba Group)",
        "快手联盟 (Kwai Business)" to "快手 (Kuaishou)",
        "优量汇 (Tencent Marketing)" to "腾讯 (Tencent)",
        "腾讯广告 (Tencent Ads)" to "腾讯 (Tencent)",
        "京东 (JD)" to "京东 (JD.com)",
        "Fyber (Digital Turbine)" to "Digital Turbine (DT Exchange)",
        "穿山甲 (Pangle)" to "字节跳动 (ByteDance)",
        "百度联盟 (Baidu Union)" to "百度 (Baidu)",
        "华为广告 (Huawei Ads)" to "华为 (Huawei)",
        "小米广告 (Xiaomi Ads)" to "小米 (Xiaomi)",
        "OPPO 广告 (OPPO Ads)" to "OPPO (HeyTap)",
        "vivo 广告 (vivo Ads)" to "vivo (vivo Ads)",
        "Mintegral China (Mintegral)" to "Mintegral (Mintegral)",
        "VIVO (vivo Ads)" to "vivo (vivo Ads)",
        "QXM (QXM Ads)" to "趣小猫 (QXM Ads)",
        "UBIX (UBIX Ads)" to "优比客思 (UBIX Ads)",
        "TalkingData (TalkingData)" to "腾云天下 (TalkingData)",
        "AdMaster (AdMaster)" to "精硕科技 (AdMaster)",
        "Sigmob (Sigmob)" to "Sigmob 聚效广告 (Sigmob)",
        "MobTech (MobTech)" to "MobTech 魔方科技 (MobTech)",
        "TopOn (TopOn)" to "TopOn 聚合广告 (TopOn)",
        "TradPlus (TradPlus)" to "TradPlus 聚合广告 (TradPlus)",
        "Beizi (Beizi)" to "Beizi 广告 (Beizi)",
        "AdScope (AdScope)" to "AdScope 聚合广告 (AdScope)",
        "Youmi (Youmi)" to "有米广告 (Youmi)",
        "MediaV (MediaV)" to "MediaV 广告 (MediaV)",
        "Bigo Ads (Bigo)" to "Bigo 广告 (Bigo)",
        "Vpon (Vpon)" to "Vpon 广告 (Vpon)",
        "Maticoo (Maticoo)" to "Maticoo 广告 (Maticoo)",
        "Kidoz (Kidoz)" to "Kidoz 广告 (Kidoz)",
        "Alphabet (Google)" to "谷歌 (Google)",
        "Meta (Meta Platforms)" to "Meta 平台 (Meta Platforms)",
        "Amazon (Amazon Ads)" to "亚马逊 (Amazon Ads)",
        "Microsoft (Microsoft Ads)" to "微软 (Microsoft Ads)",
        "Apple (Apple Ads)" to "苹果 (Apple Ads)",
        "Samsung (Samsung Ads)" to "三星 (Samsung Ads)",
        "X (Twitter)" to "X 平台 (Twitter)",
        "Snap (Snapchat)" to "Snap 平台 (Snapchat)",
        "Pinterest (Pinterest)" to "Pinterest 平台 (Pinterest)",
        "Reddit (Reddit)" to "Reddit 平台 (Reddit)",
        "Unity (Unity Ads)" to "Unity 广告 (Unity Ads)",
        "AppLovin (AppLovin)" to "AppLovin 广告 (AppLovin)",
        "ironSource (ironSource)" to "ironSource 广告 (ironSource)",
        "Vungle (Liftoff)" to "Vungle 广告 (Liftoff)",
        "Chartboost (Chartboost)" to "Chartboost 广告 (Chartboost)",
        "InMobi (InMobi)" to "InMobi 广告 (InMobi)",
        "Mintegral (Mintegral)" to "Mintegral 广告 (Mintegral)",
        "Moloco (Moloco)" to "Moloco 广告 (Moloco)",
        "The Trade Desk (TTD)" to "Trade Desk 广告 (TTD)",
        "PubMatic (PubMatic)" to "PubMatic 广告 (PubMatic)",
        "Magnite (Magnite)" to "Magnite 广告 (Magnite)",
        "OpenX (OpenX)" to "OpenX 广告 (OpenX)",
        "Index Exchange (Index Exchange)" to "Index Exchange 广告 (Index Exchange)",
        "Media.net (Media.net)" to "Media.net 广告 (Media.net)",
        "Taboola (Taboola)" to "Taboola 广告 (Taboola)",
        "Outbrain (Outbrain)" to "Outbrain 广告 (Outbrain)",
        "TripleLift (TripleLift)" to "TripleLift 广告 (TripleLift)",
        "AdColony (AdColony)" to "AdColony 广告 (AdColony)",
        "Ogury (Ogury)" to "Ogury 广告 (Ogury)",
        "Digital Turbine (DT Exchange)" to "Digital Turbine 广告 (DT Exchange)",
        "Smaato (Smaato)" to "Smaato 广告 (Smaato)",
        "Start.io (Start.io)" to "Start.io 广告 (Start.io)",
        "Tapjoy (Tapjoy)" to "Tapjoy 广告 (Tapjoy)",
        "Verizon Media (Yahoo/AOL)" to "Verizon Media 广告 (Yahoo/AOL)",
        "Oracle (Oracle Ads)" to "甲骨文广告 (Oracle Ads)",
        "Criteo (Criteo)" to "Criteo 广告 (Criteo)",
        "Yandex (Yandex Ads)" to "Yandex 广告 (Yandex Ads)",
        "VK (VK Ads)" to "VK 广告 (VK Ads)"
    )
    private val unsupportedAdGuardModifiers = setOf(
        "badfilter",
        "third-party",
        "domain",
        "app",
        "denyallow",
        "redirect",
        "redirect-rule",
        "replace",
        "removeparam",
        "cookie",
        "csp",
        "header",
        "method",
        "from",
        "to",
        "urlblock",
        "path",
        "generichide",
        "genericblock",
        "elemhide",
        "jsinject",
        "content",
        "extension"
    )

    fun getRules(context: Context): List<BlockRule> {
        cachedRules?.let { return it }
        synchronized(cacheLock) {
            cachedRules?.let { return it }
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val json = prefs.getString(KEY_RULES, "[]") ?: "[]"
            val type = object : TypeToken<List<BlockRule>>() {}.type
            val rules = (gson.fromJson<List<BlockRule>>(json, type) ?: emptyList())
                .map { it.copy(vendor = normalizeVendorName(it.vendor)) }
                .sortedBy { it.domain }
            updateRuleCache(rules)
            return rules
        }
    }

    fun addRule(context: Context, rawDomain: String, source: RuleSource): BlockRule? {
        val domain = sanitizeDomain(rawDomain) ?: return null
        val current = getRules(context).toMutableList()
        if (current.any { it.domain == domain }) return null
        val rule = BlockRule(
            id = UUID.randomUUID().toString(),
            domain = domain,
            vendor = classifyVendor(context, domain),
            source = source
        )
        current += rule
        save(context, current)
        return rule
    }

    fun addRules(context: Context, rawInput: String, source: RuleSource): List<BlockRule> {
        val current = getRules(context).toMutableList()
        val existingDomains = current.mapTo(linkedSetOf()) { it.domain }
        val added = mutableListOf<BlockRule>()
        parseManualInput(rawInput).forEach { domain ->
            if (existingDomains.add(domain)) {
                added += BlockRule(
                    id = UUID.randomUUID().toString(),
                    domain = domain,
                    vendor = classifyVendor(context, domain),
                    source = source
                )
            }
        }
        if (added.isNotEmpty()) {
            current += added
            save(context, current)
        }
        return added
    }

    fun importRules(context: Context, content: String, source: RuleSource = RuleSource.IMPORTED): Int {
        val current = getRules(context).associateBy { it.domain }.toMutableMap()
        val parsed = parseImportLines(content)

        parsed.blockedDomains.forEach { domain ->
            if (!current.containsKey(domain)) {
                current[domain] = BlockRule(
                    id = UUID.randomUUID().toString(),
                    domain = domain,
                    vendor = classifyVendor(context, domain),
                    source = source
                )
            }
        }

        parsed.exceptionDomains.forEach { exceptionDomain ->
            current.entries.removeIf { (domain, _) -> domain == exceptionDomain || domain.endsWith(".$exceptionDomain") }
        }

        save(context, current.values.sortedBy { it.domain })
        if (source == RuleSource.IMPORTED) {
            mergeUnsupportedRules(context, content)
        }
        return current.size
    }

    fun ensureBundledReferenceRules(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_BUNDLED_RULES_VERSION, 0) >= BUNDLED_RULES_VERSION) return 0
        val before = getRules(context).size
        val content = context.resources.openRawResource(R.raw.default_safe_ad_rules)
            .bufferedReader()
            .use { it.readText() }
        importRules(context, content, RuleSource.REFERENCE)
        val after = getRules(context).size
        prefs.edit().putInt(KEY_BUNDLED_RULES_VERSION, BUNDLED_RULES_VERSION).apply()
        return (after - before).coerceAtLeast(0)
    }

    fun removeByIds(context: Context, ids: Set<String>) {
        save(context, getRules(context).filterNot { ids.contains(it.id) })
        saveUnsupportedRules(context, getUnsupportedRules(context).filterNot { ids.contains(it.id) })
    }

    fun isBlocked(context: Context, domain: String): Boolean {
        val normalized = sanitizeDomain(domain) ?: return false
        val blockedDomains = getBlockedDomainSet(context)
        return buildDomainCandidates(normalized).any(blockedDomains::contains)
    }

    fun findMatchingRule(context: Context, domain: String): BlockRule? {
        val normalized = sanitizeDomain(domain) ?: return null
        val ruleMap = getRuleMap(context)
        return buildDomainCandidates(normalized)
            .mapNotNull(ruleMap::get)
            .firstOrNull()
    }

    fun shouldAggressivelyBlockForNovelApp(context: Context, domain: String, appName: String?, vendor: String): Boolean {
        val normalized = sanitizeDomain(domain) ?: return false
        if (!isNovelAppHint(appName)) return false
        if (hasMatchingRule(context, normalized)) return false
        if (isProtectedNovelAppDomain(normalized)) return false
        if (buildDomainCandidates(normalized).any(novelAggressiveExactDomains::contains)) return true
        val normalizedVendor = normalizeVendorName(vendor)
        if (!novelAggressiveVendorNames.contains(normalizedVendor)) return false
        return looksLikeAdDomain(normalized)
    }

    fun filterNonAds(context: Context): List<BlockRule> {
        val unsupported = getUnsupportedRules(context)
        val regular = getRules(context).filter { rule ->
            val effectiveVendor = if (rule.vendor == DEFAULT_VENDOR) classifyVendor(context, rule.domain) else normalizeVendorName(rule.vendor)
            effectiveVendor == DEFAULT_VENDOR && !looksLikeAdDomain(rule.domain) && !looksLikeBypassProtectionDomain(rule.domain)
        }
        return unsupported + regular
    }

    fun getRuleInventory(context: Context): RuleInventory {
        val rules = getRules(context)
        return RuleInventory(
            referenceCount = rules.count { it.source == RuleSource.REFERENCE },
            importedCount = rules.count { it.source == RuleSource.IMPORTED },
            manualCount = rules.count { it.source == RuleSource.MANUAL },
            unsupportedCount = getUnsupportedRules(context).size
        )
    }

    fun classifyVendor(context: Context, domain: String): String {
        val normalized = sanitizeDomain(domain) ?: return DEFAULT_VENDOR
        if (looksLikeBypassProtectionDomain(normalized)) return BYPASS_PROTECTION_VENDOR
        readCustomVendorMap(context)[normalized]?.let { return normalizeVendorName(it) }
        findMatchingRule(context, normalized)?.vendor?.let { return normalizeVendorName(it) }
        val lower = domain.lowercase()
        val normalizedTokens = lower.replace(Regex("[^a-z0-9]"), "")
        vendorPatterns.entries.firstOrNull { (_, patterns) -> patterns.any { lower.contains(it) } }?.let { return normalizeVendorName(it.key) }
        vendorKeywords.entries.firstOrNull { (_, keywords) -> keywords.any { keywordMatches(lower, normalizedTokens, it) } }?.let { return normalizeVendorName(it.key) }
        vendorSdkIdentifiers.entries.firstOrNull { (_, identifiers) -> identifiers.any { identifierMatches(lower, normalizedTokens, it) } }?.let {
            return normalizeVendorName(it.key)
        }
        if (looksLikeAdDomain(lower)) return GENERIC_AD_VENDOR
        return DEFAULT_VENDOR
    }

    fun classifyVendorFromHints(context: Context, domain: String, vararg hints: String?): String {
        val fromDomain = classifyVendor(context, domain)
        if (fromDomain != DEFAULT_VENDOR && fromDomain != GENERIC_AD_VENDOR) return fromDomain
        val matchedVendor = hints
            .asSequence()
            .filterNotNull()
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .mapNotNull { hint ->
                val normalizedTokens = hint.replace(Regex("[^a-z0-9\u4e00-\u9fff]"), "")
                vendorSdkIdentifiers.entries.firstOrNull { (_, identifiers) -> identifiers.any { identifierMatches(hint, normalizedTokens, it) } }?.key
            }
            .firstOrNull()
        return matchedVendor?.let(::normalizeVendorName) ?: fromDomain
    }

    fun reportUnknownVendorIfNeeded(context: Context, vendor: String, domain: String, appName: String? = null) {
        val normalizedVendor = normalizeVendorName(vendor)
        val normalized = sanitizeDomain(domain) ?: return
        if (hasMatchingRule(context, normalized)) return
        val normalizedAppName = normalizeSampleAppName(appName)
        val novelApp = isNovelAppHint(normalizedAppName)
        val shouldSample = normalizedVendor == DEFAULT_VENDOR ||
            normalizedVendor == GENERIC_AD_VENDOR ||
            (novelApp && looksLikeAdDomain(normalized))
        if (!shouldSample) return
        val samples = readUnknownVendorSamples(context).toMutableMap()
        val now = System.currentTimeMillis()
        val previous = samples[normalized]
        if (
            previous != null &&
            previous.lastAppName == normalizedAppName &&
            now - previous.lastSampleAt < SUSPICIOUS_SAMPLE_DEBOUNCE_MILLIS
        ) {
            return
        }
        val count = (previous?.count ?: 0) + 1
        val novelHits = (previous?.novelHits ?: 0) + if (novelApp) 1 else 0
        samples[normalized] = SuspiciousDomainRecord(
            count = count,
            lastSeenAt = now,
            lastAppName = normalizedAppName,
            lastVendor = normalizedVendor,
            novelHits = novelHits,
            lastSampleAt = now
        )
        val trimmed = samples.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, SuspiciousDomainRecord>> { it.value.novelHits }
                    .thenByDescending { it.value.count }
                    .thenByDescending { it.value.lastSeenAt }
                    .thenBy { it.key }
            )
            .take(120)
            .associate { it.key to it.value }
        saveUnknownVendorSamples(context, trimmed)
        if (count == 1 || count == 5 || count == 20) {
            val scope = if (novelApp) "Novel app suspicious" else "Unknown vendor sample"
            LogRepository.append(context, "$scope x$count: $normalized app=$normalizedAppName vendor=$normalizedVendor")
        }
    }

    fun exportUnknownVendorSamples(context: Context): String {
        val samples = readUnknownVendorSamples(context)
        if (samples.isEmpty()) return "No suspicious ad-like domains sampled\n"
        return buildString {
            append("Suspicious ad-like domains\n")
            append("domain,count,novel_hits,last_seen,last_app,last_vendor\n")
            samples.entries
                .sortedWith(
                    compareByDescending<Map.Entry<String, SuspiciousDomainRecord>> { it.value.novelHits }
                        .thenByDescending { it.value.count }
                        .thenByDescending { it.value.lastSeenAt }
                        .thenBy { it.key }
                )
                .forEach { entry ->
                    append(escapeCsvField(entry.key))
                    append(',')
                    append(entry.value.count)
                    append(',')
                    append(entry.value.novelHits)
                    append(',')
                    append(escapeCsvField(formatTimestamp(entry.value.lastSeenAt)))
                    append(',')
                    append(escapeCsvField(entry.value.lastAppName.ifBlank { "未知" }))
                    append(',')
                    append(escapeCsvField(entry.value.lastVendor.ifBlank { DEFAULT_VENDOR }))
                    append('\n')
                }
        }
    }

    fun getSuspiciousDomainSamples(context: Context): List<SuspiciousDomainSample> {
        return readUnknownVendorSamples(context)
            .entries
            .sortedWith(
                compareByDescending<Map.Entry<String, SuspiciousDomainRecord>> { it.value.novelHits }
                    .thenByDescending { it.value.count }
                    .thenByDescending { it.value.lastSeenAt }
                    .thenBy { it.key }
            )
            .map {
                SuspiciousDomainSample(
                    domain = it.key,
                    count = it.value.count,
                    lastSeenAt = it.value.lastSeenAt,
                    lastAppName = it.value.lastAppName,
                    lastVendor = it.value.lastVendor.ifBlank { DEFAULT_VENDOR },
                    novelHits = it.value.novelHits
                )
            }
    }

    fun isNovelAppHint(value: String?): Boolean {
        val text = value?.trim()?.lowercase().orEmpty()
        if (text.isBlank()) return false
        val normalized = text.replace(Regex("[^a-z0-9\u4e00-\u9fff]"), "")
        return novelAppIdentifiers.any { identifierMatches(text, normalized, it) }
    }

    fun isNovelVendor(vendor: String): Boolean = novelVendorNames.contains(normalizeVendorName(vendor))

    fun isProtectedNovelAppDomain(domain: String): Boolean {
        val normalized = sanitizeDomain(domain) ?: return false
        return buildDomainCandidates(normalized).any(novelAppProtectedSuffixes::contains)
    }

    fun hasMatchingRule(context: Context, domain: String): Boolean {
        return findMatchingRule(context, domain) != null
    }

    private fun keywordMatches(domain: String, normalizedTokens: String, keyword: String): Boolean {
        if (keyword.isBlank()) return false
        if (keyword.length <= 2) {
            val labels = domain.split('.', '-', '_').filter { it.isNotBlank() }
            return labels.any { it == keyword }
        }
        return domain.contains(keyword) || normalizedTokens.contains(keyword)
    }

    private fun identifierMatches(text: String, normalizedTokens: String, identifier: String): Boolean {
        if (identifier.isBlank()) return false
        val lowerIdentifier = identifier.lowercase()
        val normalizedIdentifier = lowerIdentifier.replace(Regex("[^a-z0-9\u4e00-\u9fff]"), "")
        return text.contains(lowerIdentifier) || normalizedTokens.contains(normalizedIdentifier)
    }

    fun availableVendors(context: Context): List<String> {
        return (vendorPatterns.keys + readCustomVendorMap(context).values + getRules(context).map { it.vendor })
            .map(::normalizeVendorName)
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
    }

    fun updateRuleVendor(context: Context, id: String, vendor: String) {
        val targetVendor = normalizeVendorName(vendor.trim().ifBlank { DEFAULT_VENDOR })
        val updated = getRules(context).map { rule ->
            if (rule.id == id) rule.copy(vendor = targetVendor) else rule
        }
        val targetRule = updated.firstOrNull { it.id == id } ?: return
        val customMap = readCustomVendorMap(context).toMutableMap()
        customMap[targetRule.domain] = targetVendor
        save(context, updated)
        saveCustomVendorMap(context, customMap)
    }

    fun analyzeImportContent(context: Context, content: String): RuleAnalysisReport {
        val existingRules = getRules(context)
        val existingDomains = existingRules.map(BlockRule::domain).toMutableSet()
        val simulatedDomains = existingDomains.toMutableSet()
        val seenBlocked = linkedSetOf<String>()
        val seenExceptions = linkedSetOf<String>()
        val unsupportedLines = mutableListOf<String>()
        val invalidLines = mutableListOf<String>()
        val vendorCount = linkedMapOf<String, Int>()
        var blankOrCommentLines = 0
        var safeBlockedRules = 0
        var safeExceptionRules = 0
        var duplicateExistingRules = 0
        var duplicateWithinFileRules = 0
        var unsupportedModifierRules = 0
        var cosmeticRules = 0
        var regexRules = 0
        var invalidRules = 0
        var exceptionRemovalEstimate = 0

        content.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.isBlank() || line.startsWith("#") || line.startsWith("!") -> {
                    blankOrCommentLines += 1
                }

                line.contains("##") || line.contains("#@#") || line.contains("#$#") || line.contains("#%#") -> {
                    cosmeticRules += 1
                }

                line.startsWith("/") && line.endsWith("/") -> {
                    regexRules += 1
                }

                else -> {
                    val isException = line.startsWith("@@")
                    val working = if (isException) line.removePrefix("@@") else line
                    val candidate = extractDomainCandidate(working)
                    if (candidate == null) {
                        if (looksLikeComplexRulePattern(working)) {
                            unsupportedModifierRules += 1
                            unsupportedLines += "$line    [complex-pattern]"
                        } else {
                            invalidRules += 1
                            invalidLines += line
                        }
                        return@forEach
                    }

                    val (patternPart, modifierPart) = candidate
                    val unsupportedModifiers = extractUnsupportedModifiers(modifierPart)
                    if (unsupportedModifiers.isNotEmpty()) {
                        unsupportedModifierRules += 1
                        unsupportedLines += "$line    [${unsupportedModifiers.joinToString(", ")}]"
                        return@forEach
                    }

                    val domains = parseDomainsFromPattern(patternPart)

                    if (domains.isEmpty()) {
                        if (looksLikeComplexRulePattern(patternPart)) {
                            unsupportedModifierRules += 1
                            unsupportedLines += "$line    [complex-pattern]"
                        } else {
                            invalidRules += 1
                            invalidLines += line
                        }
                        return@forEach
                    }

                    domains.forEach domainLoop@{ domain ->
                        if (isException) {
                            if (!seenExceptions.add(domain)) {
                                duplicateWithinFileRules += 1
                                return@domainLoop
                            }
                            safeExceptionRules += 1
                            val removed = simulatedDomains.count { it == domain || it.endsWith(".$domain") }
                            exceptionRemovalEstimate += removed
                            simulatedDomains.removeAll { it == domain || it.endsWith(".$domain") }
                        } else {
                            when {
                                !seenBlocked.add(domain) -> {
                                    duplicateWithinFileRules += 1
                                }

                                simulatedDomains.contains(domain) || existingDomains.contains(domain) -> {
                                    duplicateExistingRules += 1
                                }

                                else -> {
                                    safeBlockedRules += 1
                                    simulatedDomains += domain
                                    val vendor = classifyVendor(context, domain)
                                    vendorCount[vendor] = (vendorCount[vendor] ?: 0) + 1
                                }
                            }
                        }
                    }
                }
            }
        }

        return RuleAnalysisReport(
            totalLines = content.lineSequence().count(),
            existingRules = existingRules.size,
            estimatedFinalRules = simulatedDomains.size,
            blankOrCommentLines = blankOrCommentLines,
            safeBlockedRules = safeBlockedRules,
            safeExceptionRules = safeExceptionRules,
            duplicateExistingRules = duplicateExistingRules,
            duplicateWithinFileRules = duplicateWithinFileRules,
            unsupportedModifierRules = unsupportedModifierRules,
            cosmeticRules = cosmeticRules,
            regexRules = regexRules,
            invalidRules = invalidRules,
            exceptionRemovalEstimate = exceptionRemovalEstimate,
            vendorSummary = vendorCount.entries
                .sortedByDescending { it.value }
                .take(16)
                .map { VendorSummary(it.key, it.value) },
            sampleUnsupportedLines = unsupportedLines.distinct().take(10),
            sampleInvalidLines = invalidLines.distinct().take(10)
        )
    }

    fun parseImportLines(content: String): ParsedRules {
        val blocked = linkedSetOf<String>()
        val exceptions = linkedSetOf<String>()

        content.lineSequence().forEach { rawLine ->
            parseRuleLine(rawLine).forEach { parsedRule ->
                if (parsedRule.isException) {
                    exceptions += parsedRule.domain
                } else {
                    blocked += parsedRule.domain
                }
            }
        }

        return ParsedRules(blocked.toList(), exceptions.toList())
    }

    fun parseManualInput(rawInput: String): List<String> {
        val blocked = linkedSetOf<String>()
        rawInput.lineSequence().forEach { rawLine ->
            val trimmed = rawLine.trim()
            if (trimmed.isBlank()) return@forEach
            val parsedRules = parseRuleLine(trimmed)
            if (parsedRules.isNotEmpty()) {
                parsedRules.filterNot { it.isException }.forEach { blocked += it.domain }
            } else {
                trimmed.split(Regex("[\\s,;]+"))
                    .mapNotNull { sanitizeDomain(normalizeDomainToken(it)) }
                    .forEach { blocked += it }
            }
        }
        return blocked.toList()
    }

    private fun parseRuleLine(rawLine: String): List<ParsedRule> {
        val line = stripInlineRuleComment(rawLine)
        if (line.isBlank() || line.startsWith("#") || line.startsWith("!")) return emptyList()
        if (line.contains("##") || line.contains("#@#") || line.contains("#$#") || line.contains("#%#")) return emptyList()
        if (line.startsWith("/") && line.endsWith("/")) return emptyList()

        val isException = line.startsWith("@@")
        val working = if (isException) line.removePrefix("@@") else line

        val candidate = extractDomainCandidate(working) ?: return emptyList()
        val (patternPart, modifierPart) = candidate
        if (!isDnsSafeRule(modifierPart)) return emptyList()

        val domains = parseDomainsFromPattern(patternPart)

        return domains.map { domain -> ParsedRule(domain = domain, isException = isException) }
    }

    private fun parseUnsupportedRuleLines(content: String): List<String> {
        val unsupported = linkedSetOf<String>()
        content.lineSequence().forEach { rawLine ->
            val line = stripInlineRuleComment(rawLine)
            if (line.isBlank() || line.startsWith("#") || line.startsWith("!")) return@forEach
            if (line.contains("##") || line.contains("#@#") || line.contains("#$#") || line.contains("#%#")) {
                unsupported += line
                return@forEach
            }
            if (line.startsWith("/") && line.endsWith("/")) {
                unsupported += line
                return@forEach
            }
            val working = line.removePrefix("@@")
            val candidate = extractDomainCandidate(working)
            if (candidate == null) {
                if (looksLikeComplexRulePattern(working)) unsupported += line
                return@forEach
            }
            val (patternPart, modifierPart) = candidate
            if (extractUnsupportedModifiers(modifierPart).isNotEmpty()) {
                unsupported += line
                return@forEach
            }
            if (parseDomainsFromPattern(patternPart).isEmpty() && looksLikeComplexRulePattern(patternPart)) {
                unsupported += line
            }
        }
        return unsupported.toList()
    }

    private fun mergeUnsupportedRules(context: Context, content: String) {
        val current = getUnsupportedRules(context).associateBy { it.domain }.toMutableMap()
        parseUnsupportedRuleLines(content).forEach { line ->
            if (!current.containsKey(line)) {
                current[line] = BlockRule(
                    id = UUID.randomUUID().toString(),
                    domain = line,
                    vendor = UNSUPPORTED_VENDOR,
                    source = RuleSource.UNSUPPORTED
                )
            }
        }
        saveUnsupportedRules(context, current.values.sortedBy { it.domain })
    }

    private fun extractDomainCandidate(line: String): Pair<String, String?>? {
        val patternPart = line.substringBefore('$').trim()
        val modifierPart = line.substringAfter('$', missingDelimiterValue = "").trim().ifBlank { null }
        if (patternPart.isBlank()) return null
        return patternPart to modifierPart
    }

    private fun stripInlineRuleComment(rawLine: String): String {
        val commentMarkers = listOf(" #", " !", " ;", " //")
        val cutIndex = commentMarkers
            .map { marker -> rawLine.indexOf(marker) }
            .filter { it >= 0 }
            .minOrNull()
            ?: rawLine.length
        return rawLine.substring(0, cutIndex).trim()
    }

    private fun parseDomainsFromPattern(patternPart: String): List<String> {
        val trimmed = stripYamlListPrefix(patternPart.trim())
        if (trimmed.equals("payload:", ignoreCase = true) || trimmed.equals("payload", ignoreCase = true)) return emptyList()
        val dnsmasqPrefix = dnsmasqPrefixes.firstOrNull { trimmed.startsWith(it, ignoreCase = true) }
        return when {
            trimmed.startsWith("||") -> listOfNotNull(parseDomainAnchorPattern(trimmed.removePrefix("||")))
            trimmed.startsWith("|") -> listOfNotNull(parseExactAnchorPattern(trimmed.removePrefix("|").removeSuffix("|")))
            dnsmasqPrefix != null -> parseDnsmasqDomains(trimmed, dnsmasqPrefix)
            else -> parseStructuredDomainRule(trimmed).ifEmpty { parseHostsOrPlainDomains(trimmed) }
        }
    }

    private fun parseDomainAnchorPattern(pattern: String): String? {
        val trimmed = pattern.trim()
        val slashIndex = trimmed.indexOf('/')
        val caretIndex = trimmed.indexOf('^')
        val boundaryIndex = sequenceOf(slashIndex, caretIndex)
            .filter { it >= 0 }
            .minOrNull()
            ?: trimmed.length
        val domainToken = trimmed.substring(0, boundaryIndex)
        val suffix = trimmed.substring(boundaryIndex)
        if (domainToken.isBlank()) return null
        if (!isSafeDomainPatternSuffix(suffix)) return null
        return sanitizeDomain(normalizeDomainToken(domainToken))
    }

    private fun parseExactAnchorPattern(pattern: String): String? {
        val trimmed = pattern.trim()
        val withoutScheme = trimmed.removePrefix("https://").removePrefix("http://")
        val slashIndex = withoutScheme.indexOf('/')
        val questionIndex = withoutScheme.indexOf('?')
        val boundaryIndex = sequenceOf(slashIndex, questionIndex)
            .filter { it >= 0 }
            .minOrNull()
            ?: withoutScheme.length
        val domainToken = withoutScheme.substring(0, boundaryIndex)
        val suffix = withoutScheme.substring(boundaryIndex)
        if (domainToken.isBlank()) return null
        if (!isSafeDomainPatternSuffix(suffix)) return null
        return sanitizeDomain(normalizeDomainToken(domainToken))
    }

    private fun isSafeDomainPatternSuffix(suffix: String): Boolean {
        if (suffix.isBlank()) return true
        return suffix.all { it == '^' || it == '|' }
    }

    private fun parseHostsOrPlainDomains(patternPart: String): List<String> {
        val cleaned = patternPart.substringBefore('#').trim()
        val tokens = cleaned.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.size >= 2 && isHostsIpToken(tokens[0])) {
            return tokens.drop(1)
                .mapNotNull { sanitizeDomain(normalizeDomainToken(it)) }
                .distinct()
        }
        return listOfNotNull(sanitizeDomain(normalizeDomainToken(cleaned)))
    }

    private fun parseDnsmasqDomains(patternPart: String, matchedPrefix: String): List<String> {
        val body = patternPart.substring(matchedPrefix.length)
        return body.split('/')
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !looksLikeIpAddress(it) }
            .mapNotNull { sanitizeDomain(normalizeDomainToken(it)) }
            .distinct()
            .toList()
    }

    private fun parseStructuredDomainRule(patternPart: String): List<String> {
        val normalized = stripYamlListPrefix(patternPart)
        parsePrefixedDomainRule(normalized)?.let { return listOf(it) }
        val segments = normalized.split(',').map { it.trim() }.filter { it.isNotBlank() }
        if (segments.size < 2) return emptyList()
        val ruleType = segments.first().lowercase()
        val domainToken = segments.getOrNull(1) ?: return emptyList()
        return when (ruleType) {
            "domain-suffix", "domain", "host-suffix", "host", "hostname-suffix", "suffix" -> {
                listOfNotNull(parseStructuredDomainToken(domainToken))
            }
            "domain-wildcard", "host-wildcard", "hostname-wildcard" -> {
                listOfNotNull(parseStructuredDomainToken(domainToken.removePrefix("*.")))
            }
            "full", "full-domain", "hostname", "host-full", "hostname-full", "domain-full", "domain-exact", "host-exact" -> {
                listOfNotNull(parseStructuredDomainToken(domainToken))
            }
            "keyword", "domain-keyword", "host-keyword", "domain-regex", "host-regex", "url-regex",
            "ip-cidr", "ip-cidr6", "src-ip-cidr", "geoip", "geosite", "rule-set", "process-name",
            "process-path", "package-name", "user-agent", "dst-port", "src-port", "inbound", "network",
            "protocol", "and", "or", "not" -> {
                emptyList()
            }
            else -> emptyList()
        }
    }

    private fun parsePrefixedDomainRule(patternPart: String): String? {
        return parseDelimitedPrefixedDomainRule(patternPart, ':')
            ?: parseDelimitedPrefixedDomainRule(patternPart, '=')
    }

    private fun parseDelimitedPrefixedDomainRule(patternPart: String, delimiter: Char): String? {
        val exactPrefixes = listOf(
            "full",
            "full-domain",
            "domain",
            "host",
            "hostname",
            "domain-suffix",
            "host-suffix",
            "hostname-suffix",
            "suffix",
            "host-exact",
            "domain-exact",
            "domain-full",
            "hostname-full"
        )
        val wildcardPrefixes = listOf("domain-wildcard", "host-wildcard", "hostname-wildcard")
        val normalized = patternPart.trim()
        val exactPrefix = exactPrefixes.firstOrNull { normalized.startsWith("$it$delimiter", ignoreCase = true) }
        if (exactPrefix != null) {
            return parseStructuredDomainToken(normalized.substring(exactPrefix.length + 1))
        }
        val wildcardPrefix = wildcardPrefixes.firstOrNull { normalized.startsWith("$it$delimiter", ignoreCase = true) }
        if (wildcardPrefix != null) {
            return parseStructuredDomainToken(normalized.substring(wildcardPrefix.length + 1).removePrefix("*."))
        }
        return null
    }

    private fun stripYamlListPrefix(value: String): String {
        val trimmed = value.trim()
        return when {
            trimmed.startsWith("- ") -> trimmed.substring(2).trim()
            trimmed.startsWith("* ") -> trimmed.substring(2).trim()
            trimmed == "-" || trimmed == "*" -> ""
            else -> trimmed
        }
    }

    private val dnsmasqPrefixes = listOf("address=/", "server=/", "local=/", "ipset=/", "nftset=/")

    private fun parseStructuredDomainToken(raw: String): String? {
        return sanitizeDomain(
            normalizeDomainToken(
                raw.trim()
                    .removeSurrounding("\"")
                    .removeSurrounding("'")
                    .removeSurrounding("[", "]")
                    .removePrefix("+.")
                    .removePrefix(".")
            )
        )
    }

    private fun normalizeDomainToken(raw: String): String {
        var current = raw.trim()
        current = current.removeSurrounding("\"").removeSurrounding("'").removeSurrounding("[", "]")
        current = current.removePrefix("*://").removePrefix("://")
        current = current.substringAfter("://", missingDelimiterValue = current)
        return current
            .removePrefix("*.")
            .removePrefix(".")
            .removePrefix("[")
            .removeSuffix("]")
            .trim('*')
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('^')
            .substringBefore('|')
            .substringBefore(':')
            .substringBefore('#')
            .substringBefore('@')
            .trim()
    }

    private fun isHostsIpToken(token: String): Boolean {
        return token == "0.0.0.0" || token == "127.0.0.1" || token == "::" || token == "::1"
    }

    private fun looksLikeIpAddress(token: String): Boolean {
        val value = token.trim().trim('[').trim(']')
        if (value.isBlank()) return false
        if (value.contains(':')) return true
        return value.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}"))
    }

    private fun looksLikeAdDomain(domain: String): Boolean {
        val lower = domain.lowercase()
        val normalizedTokens = lower.replace(Regex("[^a-z0-9]"), "")
        return adKeywords.any { keywordMatches(lower, normalizedTokens, it) }
    }

    private fun looksLikeBypassProtectionDomain(domain: String): Boolean {
        val normalized = sanitizeDomain(domain) ?: return false
        return buildDomainCandidates(normalized).any(bypassProtectionDomains::contains)
    }

    private fun looksLikeComplexRulePattern(value: String): Boolean {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return false
        return trimmed.contains("://") ||
            trimmed.contains('*') ||
            trimmed.contains('^') ||
            trimmed.contains('|') ||
            trimmed.contains('=') ||
            trimmed.contains('@')
    }

    private fun isDnsSafeRule(modifierPart: String?): Boolean {
        return extractUnsupportedModifiers(modifierPart).isEmpty()
    }

    private fun extractUnsupportedModifiers(modifierPart: String?): List<String> {
        if (modifierPart == null) return emptyList()
        val modifiers = modifierPart.split(',')
            .map { it.trim().removePrefix("~").substringBefore('=').lowercase() }
            .filter { it.isNotBlank() }
        if (modifiers.isEmpty()) return emptyList()
        return modifiers.filter { unsupportedAdGuardModifiers.contains(it) }
    }

    private fun sanitizeDomain(raw: String): String? {
        val value = raw.trim().lowercase()
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('/')
            .substringBefore('^')
            .substringBefore(':')
            .trim('.')
            .trim()
        if (value.isBlank() || !value.contains('.')) return null
        if (!value.matches(Regex("[a-z0-9._-]+"))) return null
        return value
    }

    private fun save(context: Context, rules: List<BlockRule>) {
        val normalizedRules = rules.map { it.copy(vendor = normalizeVendorName(it.vendor)) }.sortedBy { it.domain }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RULES, gson.toJson(normalizedRules))
            .apply()
        updateRuleCache(normalizedRules)
    }

    private fun getUnsupportedRules(context: Context): List<BlockRule> {
        cachedUnsupportedRules?.let { return it }
        synchronized(cacheLock) {
            cachedUnsupportedRules?.let { return it }
            val type = object : TypeToken<List<BlockRule>>() {}.type
            val rules = (gson.fromJson<List<BlockRule>>(
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_UNSUPPORTED_RULES, "[]"),
                type
            ) ?: emptyList())
                .filter { it.source == RuleSource.UNSUPPORTED }
                .sortedBy { it.domain }
            cachedUnsupportedRules = rules
            return rules
        }
    }

    private fun saveUnsupportedRules(context: Context, rules: List<BlockRule>) {
        val normalizedRules = rules.map {
            it.copy(vendor = UNSUPPORTED_VENDOR, source = RuleSource.UNSUPPORTED)
        }.sortedBy { it.domain }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_UNSUPPORTED_RULES, gson.toJson(normalizedRules))
            .apply()
        cachedUnsupportedRules = normalizedRules
    }

    private fun readCustomVendorMap(context: Context): Map<String, String> {
        cachedCustomVendors?.let { return it }
        synchronized(cacheLock) {
            cachedCustomVendors?.let { return it }
            val type = object : TypeToken<Map<String, String>>() {}.type
            val map = gson.fromJson<Map<String, String>>(
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CUSTOM_VENDORS, "{}"),
                type
            ) ?: emptyMap()
            cachedCustomVendors = map
            return map
        }
    }

    private fun saveCustomVendorMap(context: Context, map: Map<String, String>) {
        val normalizedMap = map.mapValues { normalizeVendorName(it.value) }.toSortedMap()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CUSTOM_VENDORS, gson.toJson(normalizedMap))
            .apply()
        cachedCustomVendors = normalizedMap
    }

    private fun readUnknownVendorSamples(context: Context): Map<String, SuspiciousDomainRecord> {
        val prefsValue = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_UNKNOWN_VENDOR_SAMPLES, "{}") ?: "{}"
        runCatching {
            val type = object : TypeToken<Map<String, SuspiciousDomainRecord>>() {}.type
            gson.fromJson<Map<String, SuspiciousDomainRecord>>(prefsValue, type)
        }.getOrNull()?.let { parsed ->
            return parsed.filterValues { it.count > 0 }
        }
        val legacyType = object : TypeToken<Map<String, Int>>() {}.type
        val legacy = gson.fromJson<Map<String, Int>>(prefsValue, legacyType) ?: emptyMap()
        val migrated = legacy.mapValues { SuspiciousDomainRecord(count = it.value, lastSeenAt = 0L) }
        if (migrated.isNotEmpty()) {
            saveUnknownVendorSamples(context, migrated)
        }
        return migrated
    }

    private fun saveUnknownVendorSamples(context: Context, samples: Map<String, SuspiciousDomainRecord>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_UNKNOWN_VENDOR_SAMPLES, gson.toJson(samples))
            .apply()
    }

    private fun formatTimestamp(timestamp: Long): String {
        if (timestamp <= 0L) return "未知"
        return timeFormatter.format(Date(timestamp))
    }

    private fun normalizeSampleAppName(appName: String?): String {
        return appName
            ?.replace(Regex("[\\r\\n]+"), " ")
            ?.trim()
            ?.take(80)
            .orEmpty()
    }

    private fun escapeCsvField(value: String): String {
        if (!value.contains(',') && !value.contains('"') && !value.contains('\n')) return value
        return buildString {
            append('"')
            value.forEach { ch ->
                if (ch == '"') append("\"\"") else append(ch)
            }
            append('"')
        }
    }

    private fun normalizeVendorName(vendor: String): String {
        if (vendor.isBlank()) return DEFAULT_VENDOR
        var current = vendor.trim()
        val seen = linkedSetOf<String>()
        while (seen.add(current)) {
            val next = vendorAliases[current] ?: break
            current = next
        }
        return current
    }

    private fun getBlockedDomainSet(context: Context): Set<String> {
        cachedBlockedDomains?.let { return it }
        return getRules(context).mapTo(linkedSetOf(), BlockRule::domain)
    }

    private fun getRuleMap(context: Context): Map<String, BlockRule> {
        cachedRuleMap?.let { return it }
        return getRules(context).associateBy { it.domain }
    }

    private fun buildDomainCandidates(domain: String): Sequence<String> = sequence {
        yield(domain)
        var index = domain.indexOf('.')
        while (index in 1 until domain.lastIndex) {
            yield(domain.substring(index + 1))
            index = domain.indexOf('.', index + 1)
        }
    }

    private fun updateRuleCache(rules: List<BlockRule>) {
        cachedRules = rules
        cachedBlockedDomains = rules.mapTo(linkedSetOf(), BlockRule::domain)
        cachedRuleMap = rules.associateBy { it.domain }
    }

    data class ParsedRules(
        val blockedDomains: List<String>,
        val exceptionDomains: List<String>
    )

    data class RuleAnalysisReport(
        val totalLines: Int,
        val existingRules: Int,
        val estimatedFinalRules: Int,
        val blankOrCommentLines: Int,
        val safeBlockedRules: Int,
        val safeExceptionRules: Int,
        val duplicateExistingRules: Int,
        val duplicateWithinFileRules: Int,
        val unsupportedModifierRules: Int,
        val cosmeticRules: Int,
        val regexRules: Int,
        val invalidRules: Int,
        val exceptionRemovalEstimate: Int,
        val vendorSummary: List<VendorSummary>,
        val sampleUnsupportedLines: List<String>,
        val sampleInvalidLines: List<String>
    ) {
        val safeRuleCount: Int
            get() = safeBlockedRules + safeExceptionRules
    }

    data class VendorSummary(
        val vendor: String,
        val count: Int
    )

    data class RuleInventory(
        val referenceCount: Int,
        val importedCount: Int,
        val manualCount: Int,
        val unsupportedCount: Int
    ) {
        val totalSupportedCount: Int
            get() = referenceCount + importedCount + manualCount
    }

    data class SuspiciousDomainSample(
        val domain: String,
        val count: Int,
        val lastSeenAt: Long,
        val lastAppName: String,
        val lastVendor: String,
        val novelHits: Int
    )

    private data class SuspiciousDomainRecord(
        val count: Int = 0,
        val lastSeenAt: Long = 0L,
        val lastAppName: String = "",
        val lastVendor: String = "",
        val novelHits: Int = 0,
        val lastSampleAt: Long = 0L
    )

    private data class ParsedRule(
        val domain: String,
        val isException: Boolean
    )
}
