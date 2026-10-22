# 资金驱动选股 App（Android 项目源码）

## 这是什么
一个 Android 原生 App 项目（Kotlin），独立运行，不依赖电脑、不依赖开发平台。
打开后自动拉取东方财富免费实时接口的数据，分两层筛选：

**第一层：资金驱动（硬性主条件，不满足直接排除）**
- 仅主板（60/000/001/002开头）+ 创业板（300/301开头），排除科创板(688)和北交所
- 现价 ≤ 8 元
- 流通市值 ≤ 120 亿
- 主力净流入占比达标（按占比排序，不是净流入绝对金额，避免大盘股垄断排名）

**第二层：加分项（在资金驱动候选股里，前30只会逐个评估以下5项，命中数达到阈值才最终展示）**
1. 9:15价格 < 9:25价格（集合竞价期间价格走高）
2. 9:25竞价量 / 9:20竞价量 放大倍数达标
3. 所属行业命中当日热点概念板块（近似匹配，见下方"已知局限"）
4. 最近公告标题命中利好关键词
5. 近一年换手率/振幅/涨停次数任一达标（股性活跃）

界面支持下拉刷新、"自动刷新"开关（每15秒轮询一次）、手动记录9:15/9:20/9:25竞价快照的按钮。

## 🚀 不会用 Android Studio？用这个办法直接拿到 APK 文件

项目里已经放好了 `.github/workflows/build-apk.yml`，可以用 GitHub 的免费服务器自动编译，
你全程不需要在自己电脑上装任何开发工具，跟着下面步骤操作：

1. **注册 GitHub 账号**（如果没有）：打开 https://github.com/signup ，用邮箱注册，免费
2. **新建一个仓库**：登录后点右上角 `+` → `New repository`，仓库名随便填（比如 `AStockScreener`），
   选 `Public` 或 `Private` 都行，点 `Create repository`
3. **上传项目文件**：
   - 进入刚建的空仓库页面，会看到 `uploading an existing file` 这个链接，点它
   - 把我给你的这个压缩包解压后，**把 `AStockScreener` 文件夹里的所有文件和文件夹**
     （包括隐藏的 `.github` 文件夹，解压软件里要勾选"显示隐藏文件"才能看到）拖进网页上传框
   - 网页最下面填一句提交说明（随便写），点 `Commit changes`
4. **等待自动编译**：上传完成后，点仓库页面顶部的 `Actions` 标签页，
   能看到一个正在跑的任务（黄色圆点 = 进行中，绿色勾 = 成功，红叉 = 失败），
   一般 5-10 分钟编译完成
5. **下载 APK**：编译成功后，点进那个任务，往下翻到 `Artifacts`（产物）区域，
   点 `AStockScreener-debug-apk` 就能下载到压缩包，解压出来就是 `app-debug.apk`，
   传到手机上，开启"允许安装未知来源应用"，点击安装即可

**如果第4步显示红叉（编译失败）**：点进去看报错信息（一般是英文），截图发给我，
我帮你看是哪里的问题——大概率是我之前说过的那些未验证字段导致的，不是什么大故障。

这个是 **Debug 版 APK**（调试版），能正常安装使用，只是没有做正式签名，
以后项目升级重新安装时可能需要先卸载旧版本。如果需要正式签名版本，参考下面"怎么编译成APK"里
`Generate Signed Bundle / APK` 那段，自己在 Android Studio 里生成签名证书。

---

## ⚠️ 必须先看：我没有在真实环境里跑过这份代码

我写这份代码的环境没有联网权限，也没有 Android 编译工具链，所以：

1. **没有编译验证过**——语法上我已经尽量写对，但第一次在 Android Studio 里跑，
   大概率需要根据报错改几个小地方（版本号冲突、依赖下载等），这是正常的，不代表代码整体有问题。
2. **没有验证过东方财富接口的真实返回数据**——`EastmoneyApi.kt` 里的 `f12/f14/f2/f3/f20/f21/f62/f184`
   这些字段号,是我按社区公开资料整理的,东财接口没有官方文档,字段随时可能调整。
   **强烈建议你第一步先用电脑浏览器打开这个链接，看看返回的 JSON 长什么样：**

   ```
   https://push2.eastmoney.com/api/qt/clist/get?pn=1&pz=10&po=1&np=1&fltt=2&invt=2&fid=f184&fs=m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23&fields=f12,f14,f2,f3,f20,f21,f62,f184
   ```

   如果返回的字段和代码里对不上（比如看到的是别的字段号、或者数值明显不对，比如价格变成了几万几十万），
   照着实际返回的 JSON 调整 `EastmoneyApi.kt` 里的字段映射即可，逻辑框架不用动。

3. **这是免费公开接口，不是官方授权的商业数据服务**：
   - 延迟不保证，正常情况下大概 3-5 秒，行情剧烈波动、访问量大时可能更久甚至请求失败
   - 东财会按来源IP限流、临时封禁突发请求，`ScreenerConfig.POLL_INTERVAL_MS` 别调太快（建议不低于10秒）
   - 把这类接口做成App长期大量分发使用，存在一定的服务条款合规风险，仅建议自己使用，不要上架应用商店公开分发

4. **四个加分项用了四个不同的免费接口，同样都没联网验证过**（`SectorApi.kt` / `NewsApi.kt` /
   `HistoryApi.kt` / `EastmoneyApi.fetchPriceVolumeSnapshot`），每个文件顶部注释里都写了具体是哪个字段
   没把握、大概率需要调整。排查方法都一样：浏览器直接打开对应URL看真实返回的JSON，对照字段改代码。

5. **热点题材这项的匹配逻辑是粗略近似，不是精确匹配**：用的是个股"所属行业"去匹配"概念板块涨幅榜"，
   这是两套不同的分类体系，命中率可能偏低或者偏高，这项如果效果不理想，可以在 `ScreenerConfig.kt`
   里把 `MIN_CONDITIONS_MET` 调低，或者把这项干脆当参考就行，不用太较真。

6. **集合竞价快照（9:15/9:20/9:25）依赖 Android 后台闹钟，这个在国产手机上出问题的概率比接口字段还高**：
   - 很多手机（小米/华为/OPPO/vivo等）的电池优化会直接杀掉后台闹钟，设置里要把本App加入
     "无限制"/"允许自启动"名单，不同品牌路径不一样，自己在设置里搜"电池优化"或"自启动管理"
   - Android 12+ 首次打开App会弹出跳转系统设置的提示，需要手动去开启"闹钟和提醒"权限
   - 不放心的话，界面上有"9:15 / 9:20 / 9:25"三个手动按钮，自己在那几个时间点点一下更保险
   - 没有任何一个竞价快照数据时，这两项加分会被跳过（不计分也不扣分），不影响资金驱动主条件的展示

## 怎么编译成 APK

1. 下载安装 [Android Studio](https://developer.android.com/studio)（最新稳定版即可）
2. 打开 Android Studio → `Open` → 选择这个项目的根目录（`AStockScreener` 文件夹）
3. 等待它自动下载 Gradle、同步依赖（需要联网，第一次会比较慢）
4. 如果同步报错，通常是版本号不匹配，按报错提示把 `build.gradle` 里对应的版本号改成 Android Studio 建议的版本即可
5. 顶部菜单 `Build` → `Build Bundle(s) / APK(s)` → `Build APK(s)`
6. 编译完成后点提示里的 `locate` 就能找到生成的 `app-debug.apk`，传到手机上安装即可
   （手机需要开启"允许安装未知来源应用"）

如果你想要正式签名、可以长期稳定安装不被覆盖的版本，需要在 Android Studio 里
`Build` → `Generate Signed Bundle / APK`，自己生成一个签名证书（这个证书自己保管好，以后升级App都要用同一个）。

## 项目结构

```
AStockScreener/
├── build.gradle                    # 项目级构建配置
├── settings.gradle
├── gradle.properties
└── app/
    ├── build.gradle                 # 模块级构建配置、依赖
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/example/astockscreener/
        │   ├── StockItem.kt              # 数据模型（含加分项字段）
        │   ├── BoardFilter.kt            # 主板/创业板判断
        │   ├── SecidUtil.kt              # 代码->secid格式转换
        │   ├── ScreenerConfig.kt         # 全部阈值配置（改这里调参数）
        │   ├── EastmoneyApi.kt           # 资金流向+价量快照 网络请求
        │   ├── SectorApi.kt              # 热点题材匹配
        │   ├── NewsApi.kt                # 利好消息关键词匹配
        │   ├── HistoryApi.kt             # 近一年股性活跃度
        │   ├── AuctionSnapshotManager.kt # 竞价快照存取+定时调度
        │   ├── AuctionAlarmReceiver.kt   # 闹钟触发的后台接收器
        │   ├── EnrichmentService.kt      # 串联四项加分逻辑
        │   ├── ScreenerRepository.kt     # 资金驱动主条件筛选排序
        │   ├── ScreenerViewModel.kt      # 状态管理+轮询+编排整个流程
        │   ├── StockAdapter.kt           # 列表展示（含加分徽章）
        │   └── MainActivity.kt           # 主界面+权限请求+手动快照按钮
        └── res/
            ├── layout/               # 界面布局XML
            └── values/               # 字符串、主题
```

## 怎么调整参数

全部参数都在 `app/src/main/java/com/example/astockscreener/ScreenerConfig.kt` 一个文件里：

```kotlin
const val MAX_PRICE = 8.0                  // 现价上限(元)
const val MAX_CIRC_MARKET_CAP_YI = 120.0   // 流通市值上限(亿元)
const val MIN_NET_INFLOW_PCT = 3.0         // 主力净流入占比下限(%)
const val POLL_INTERVAL_MS = 15_000L       // 轮询间隔(毫秒)

const val MIN_VOLUME_SURGE_RATIO = 1.5     // 竞价放量倍数
const val TOP_N_HOT_SECTOR = 10            // 热点板块取前N名
val POSITIVE_KEYWORDS = listOf(...)        // 利好关键词，可自行增减
const val MIN_AVG_TURNOVER_RATE = 3.0      // 日均换手率下限(%)
const val MIN_AVG_AMPLITUDE = 4.0          // 日均振幅下限(%)
const val MIN_LIMIT_UP_TIMES = 3           // 近一年涨停次数下限
const val MIN_CONDITIONS_MET = 2           // 5项加分里至少命中几项
const val MAX_STOCKS_TO_ENRICH = 30        // 最多对前几只候选股做加分评估
```

改完重新 Build 就生效，不用动其他文件。

## 重要提醒

本工具基于免费公开数据整理，不构成任何投资建议。短线交易风险较高，
资金驱动信号也可能是诱多/出货的烟雾弹，最终决策和风险需要你自己判断承担。
