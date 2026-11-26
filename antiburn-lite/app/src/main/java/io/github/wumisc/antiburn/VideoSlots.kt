package io.github.wumisc.antiburn

/**
 * v3.4.2：跨 App 同语义的区域槽位表。
 *
 * <p><b>为什么要这张表</b>：过去同一个位置在四个 App 里叫四种名字 —— 抖音「右侧按钮列」、
 * 快手「右侧互动列」、红果「右侧按钮」、B站「右侧点赞区」。用户换一个 App 就得重新理解一遍，
 * 一级页的「已开启 N 项」也因为各页手写计数而口径不一。这里把「位置」和「App 是否支持」
 * 拆开：位置只有一套名字，App 只声明支持哪些。</p>
 *
 * <p>纯数据、无副作用：read/write 只读写既有 AbState 字段，不新增任何 prefs 键。</p>
 */
internal data class RegionSlot(
    val id: String,
    val title: String,
    val desc: String,
    val read: (AbState) -> Boolean,
    val write: (AbState, Boolean) -> AbState,
    /** 同一槽位在个别 App 上需要额外联动别的字段时用（如 B站 主开关 + 区域开关）。 */
    val note: String = "",
)

internal object VideoSlots {

    /** 四个 App 共用的位置名。取词优先「人话」，不跟着各 App 的 UI 术语走。 */
    const val TOP_BAR = "顶部栏"
    const val BOTTOM_BAR = "底部栏"
    const val RIGHT_ACTIONS = "右侧互动列"
    const val CORNER_TR = "右上角"
    const val CORNER_TL = "左上角"
    const val CORNER = "角落按钮"
    const val CAPTION = "标题栏"
    const val RED_DOT = "红点"
    const val LIVE_CHROME = "直播间外框"
    const val LIVE_PICK = "圈选隐藏（直播间）"
    const val FEED_PICK = "圈选隐藏（刷视频）"
    const val HINT_TEXT = "文案引导"
    const val INFO_BLOCK = "信息区与底部导航"

    /** 抖音：9 项，全部是已验证能力。 */
    val DOUYIN: List<RegionSlot> = listOf(
        RegionSlot("douyin.topBar", TOP_BAR, "直播/团购/关注/商城",
            { it.enableDouyinTop }, { s, v -> s.copy(enableDouyinTop = v) }),
        RegionSlot("douyin.bottomBar", BOTTOM_BAR, "首页/朋友/消息/我",
            { it.enableDouyinBottom }, { s, v -> s.copy(enableDouyinBottom = v) }),
        RegionSlot("douyin.right", RIGHT_ACTIONS, "点赞/评论/收藏/分享",
            { it.enableDouyinLike }, { s, v -> s.copy(enableDouyinLike = v) }),
        RegionSlot("douyin.tr", CORNER_TR, "搜索/分享/更多",
            { it.enableDouyinRt }, { s, v -> s.copy(enableDouyinRt = v) }),
        RegionSlot("douyin.tl", CORNER_TL, "三横线菜单按钮",
            { it.enableDouyinLt }, { s, v -> s.copy(enableDouyinLt = v) }),
        RegionSlot("douyin.live", LIVE_CHROME, "顶栏主播条 / 底部非输入按钮 / 礼物列",
            { it.enableLive }, { s, v -> s.copy(enableLive = v) }),
        RegionSlot("douyin.livePick", LIVE_PICK, "直播间内只藏圈选静态元素",
            { it.enableDyLiveHits }, { s, v -> s.copy(enableDyLiveHits = v) }),
    )

    /** 红果：6 项。没有标题栏/红点的样本，不做灰度假开关。 */
    val DRAMA: List<RegionSlot> = listOf(
        RegionSlot("drama.topBar", TOP_BAR, "视频页顶部栏",
            { it.enableDramaTop }, { s, v -> s.copy(enableDramaTop = v) }),
        RegionSlot("drama.right", RIGHT_ACTIONS, "点赞/收藏等按钮列",
            { it.enableDramaLike }, { s, v -> s.copy(enableDramaLike = v) }),
        RegionSlot("drama.tr", CORNER_TR, "右上角操作按钮",
            { it.enableDramaRt }, { s, v -> s.copy(enableDramaRt = v) }),
        RegionSlot("drama.tl", CORNER_TL, "左上角菜单按钮",
            { it.enableDramaLt }, { s, v -> s.copy(enableDramaLt = v) }),
        RegionSlot("drama.hint", HINT_TEXT, "右侧竖排「左滑查看详情」，按文案识别",
            { it.enableDramaSwipe }, { s, v -> s.copy(enableDramaSwipe = v) }),
        RegionSlot("drama.info", INFO_BLOCK, "剧集信息区与底部导航；选集入口不动",
            { it.enableDramaInfoHits }, { s, v -> s.copy(enableDramaInfoHits = v) }),
    )

    /** 快手：4 区域 + 2 圈选；主开关单独（关则全部不生效，不算槽位）。 */
    val KUAISHOU: List<RegionSlot> = listOf(
        RegionSlot("ks.right", RIGHT_ACTIONS, "点赞/评论/收藏/分享",
            { it.enableKsRight || it.enableRightActions },
            { s, v -> s.copy(enableKsRight = v, enableRightActions = v) },
            note = "同时打开「整列区域」与「按控件 id 精准」两套，缺一都会有按钮漏出"),
        RegionSlot("ks.topBar", TOP_BAR, "返回/搜索/更多/台标",
            { it.enableKsTop }, { s, v -> s.copy(enableKsTop = v) }),
        RegionSlot("ks.bottomBar", BOTTOM_BAR, "标签栏/进度条/评论入口",
            { it.enableKsBottom }, { s, v -> s.copy(enableKsBottom = v) }),
        RegionSlot("ks.corner", CORNER, "右上 / 左上角常驻按钮",
            { it.enableKsCorner }, { s, v -> s.copy(enableKsCorner = v) }),
        RegionSlot("ks.livePick", LIVE_PICK, "直播间内只藏圈选静态元素，输入条/礼物/画面不动",
            { it.enableKsLiveHits }, { s, v -> s.copy(enableKsLiveHits = v) }),
        RegionSlot("ks.feedPick", FEED_PICK, "只藏全屏观看 / AI 提示等静态元素，互动数字绝不藏",
            { it.enableKsFeedHits }, { s, v -> s.copy(enableKsFeedHits = v) }),
    )

    /** B站：只有右侧列真的稳定可用，其余不摆假开关。 */
    val BILIBILI: List<RegionSlot> = listOf(
        RegionSlot("bili.right", RIGHT_ACTIONS, "点赞/评论/收藏/投币/分享",
            { it.enableBilibili && it.enableBiliRight },
            { s, v -> s.copy(enableBilibili = v, enableBiliRight = v) }),
    )

    /**
     * 共用细节：这些字段在 Hook 侧是**全局**的，不分 App。
     *
     * <p>旧 UI 把「标题栏」「红点」在抖音 / 快手 / B站 三页各写一遍，看起来像三套开关，
     * 实际是同一个键 —— 在抖音开了，快手也跟着开。与其继续复制三份误导，不如收到一处说清楚。</p>
     */
    val SHARED: List<RegionSlot> = listOf(
        RegionSlot("shared.caption", CAPTION, "视频描述行；@作者与创作声明不动",
            { it.enableBottomCaption }, { s, v -> s.copy(enableBottomCaption = v) },
            note = "对已支持该区域的 App 生效（抖音 / 快手 / B站），不是按 App 分别设置"),
        RegionSlot("shared.redDot", RED_DOT, "未读角标；点赞数、进度条绝不动",
            { it.enableRedDot }, { s, v -> s.copy(enableRedDot = v) },
            note = "同上，全局一个开关"),
    )

    fun openCount(slots: List<RegionSlot>, s: AbState): Int = slots.count { it.read(s) }

    fun total(s: AbState): Int =
        DOUYIN.size + DRAMA.size + KUAISHOU.size + BILIBILI.size + SHARED.size

    fun openTotal(s: AbState): Int =
        openCount(DOUYIN, s) + openCount(DRAMA, s) + openCount(KUAISHOU, s) +
                openCount(BILIBILI, s) + openCount(SHARED, s)
}
