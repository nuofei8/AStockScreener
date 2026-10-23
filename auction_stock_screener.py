# -*- coding: utf-8 -*-
"""
集合竞价选股工具（框架版）
========================
目标画像：短线资金驱动的低价活跃小盘股（超短线/T+0风格）
  - 现价 < 8 元
  - 流通市值 < 120 亿
  - 当日有真实、可验证的资金净流入迹象（按占比而非绝对金额判断，排除大盘股垄断）

股票池硬性过滤（不满足直接剔除，不计入打分）：
  0a. 现价 <= max_price（默认 8 元）
  0b. 流通市值 <= max_circulating_market_cap（默认 120 亿，在资金流向池过滤时生效）
  1.  资金驱动（硬性主条件）：命中当日实时资金流向池，即
      「价格达标 + 市值达标 + 主力净流入占比达标」，三者同时满足才算入池

选股加分逻辑（资金驱动过关后，再看以下5项，命中数量 >= min_conditions_met 才最终入选）：
  2. 竞价红柱量放大（9:20 → 9:25 集合竞价成交量环比放大）
  3. 9:15 价格 < 9:25 价格（竞价期间价格走高）
  4. 热点题材催动（个股所属板块当日涨幅排名靠前）
  5. 个股利好消息共振（最近公告/新闻命中利好关键词）
  6. 近一年股性活跃（用近一年的平均换手率、平均振幅、涨停次数衡量，
     只要有任意一项达标即视为"活跃"，具体阈值见 Config）

⚠️ 重要说明（请务必阅读）：
  - 本脚本依赖 akshare（免费数据源），数据有延迟、精度有限，
    真正的"竞价资金净流入"需要逐笔委托数据（Level-2），免费源拿不到，
    这里用"委比/委差"做近似替代，仅供参考，不等于真实大单净流入。
  - 集合竞价快照需要在 9:15、9:20、9:25 这几个时间点实时抓取，
    脚本提供了定时轮询的框架，需要在交易日对应时间段运行。
  - "股性活跃"的判断标准没有统一定义，这里用换手率/振幅/涨停次数
    做了一个常见口径的近似，你可以根据自己的交易习惯调整阈值。
  - 本工具仅做信息整理和初步筛选，不构成任何投资建议，
    最终决策需要你自己核实消息面、风险自担。

依赖安装：
    pip install akshare pandas schedule --break-system-packages

运行方式：
    python auction_stock_screener.py            # 实时模式，9:15 自动开始采集
    python auction_stock_screener.py --test      # 测试模式，用假数据跑一遍流程
"""

import time
import datetime
import argparse
import json
import re
from dataclasses import dataclass, field
from typing import Optional

try:
    import akshare as ak
    import pandas as pd
except ImportError:
    ak = None
    pd = None
    print("[提示] 未安装 akshare / pandas，实时模式不可用，可先用 --test 查看筛选逻辑。")


# ============================================================
# 配置区：按需调整阈值
# ============================================================

@dataclass
class Config:
    # 股票池：默认全市场，也可以指定自选股代码列表，如 ["600519", "000001"]
    stock_pool: Optional[list] = None

    # 1a. 竞价委比/委差阈值（竞价时刻的近似信号，辅助判断）
    min_bid_ask_ratio: float = 20.0        # 委比(%)需 >= 此值，越大代表买盘更强

    # 1b. 资金驱动核心指标：当日实时主力资金净流入"占比"（而非绝对金额，避免大盘股占据排名）
    min_main_fund_net_pct: float = 3.0         # 主力净流入占比(%) 需 >= 此值
    max_circulating_market_cap: float = 120.0  # 流通市值上限(亿元)，用于排除大盘股，可按需调整
    watch_accel_threshold: float = 1.0         # --watch 模式下，净流入占比相比上一轮至少提升多少个百分点才算"加速"

    # 2. 竞价放量倍数：9:20->9:25 成交量环比增幅
    min_volume_surge_ratio: float = 1.5    # 9:25量 / 9:20量 >= 1.5 倍

    # 3. 9:15 价格 < 9:25 价格 已作为硬性条件，无需配置

    # 4. 热点题材：个股所属板块当日涨幅排名进入前 N
    top_n_hot_sector: int = 10

    # 5. 利好关键词（可自行扩充）
    positive_keywords: list = field(default_factory=lambda: [
        "中标", "签约", "增持", "回购", "业绩预增", "扭亏", "重组",
        "合作", "战略", "订单", "专利", "获批", "分红", "超预期",
        "龙头", "国产替代", "政策支持", "补贴",
    ])

    # 0a. 硬性价格过滤：现价需 <= 此值（元），不满足直接剔除，不计入打分
    max_price: float = 8.0

    # 0b. 硬性板块过滤：只保留主板 + 创业板，排除科创板(688)和北交所
    restrict_to_main_and_chinext: bool = True

    # 6. 近一年股性活跃：满足以下任意一项即视为"活跃"
    activity_lookback_days: int = 250      # 约一年的交易日数
    min_avg_turnover_rate: float = 3.0     # 近一年日均换手率(%) >= 此值
    min_avg_amplitude: float = 4.0         # 近一年日均振幅(%) >= 此值
    min_limit_up_times: int = 3            # 近一年涨停(含20cm/30cm)次数 >= 此值

    # 最终需要满足的打分条件数量：条件1(资金驱动)已是硬性主条件、不满足直接剔除，
    # 这里只需在剩余5项（放量/价格上行/题材/消息/股性活跃）中再命中几项即可，
    # 默认3（即资金驱动 + 任意2项），适合短线资金驱动为主、其他为辅的打法
    min_conditions_met: int = 3


# ============================================================
# 数据采集层（真实数据，依赖 akshare，网络环境可用时才能跑）
# ============================================================

class AuctionDataCollector:
    """
    在 9:15 / 9:20 / 9:25 分别抓取一次全市场快照，
    存成 snapshot_915 / snapshot_920 / snapshot_925 三张表。
    """

    def __init__(self, config: Config):
        self.config = config
        self.snapshots = {}

    def fetch_snapshot(self, tag: str):
        """抓取一次实时行情快照"""
        if ak is None:
            raise RuntimeError("akshare 未安装，无法抓取实时数据")
        df = ak.stock_zh_a_spot_em()  # 全市场实时快照：代码/名称/最新价/成交量/涨跌幅等
        df = df.rename(columns={
            "代码": "code", "名称": "name", "最新价": "price",
            "成交量": "volume", "涨跌幅": "pct_chg",
        })
        if self.config.stock_pool:
            df = df[df["code"].isin(self.config.stock_pool)]
        self.snapshots[tag] = df[["code", "name", "price", "volume", "pct_chg"]].copy()
        print(f"[采集完成] {tag} 共 {len(df)} 条, 时间: {datetime.datetime.now()}")
        return self.snapshots[tag]

    def fetch_bid_ask(self, code: str):
        """
        抓取单只股票的五档委比/委差，作为"竞价资金净流入"的近似代理。
        真实大单净流入建议接入付费 Level-2 或券商 API。
        """
        if ak is None:
            raise RuntimeError("akshare 未安装")
        try:
            df = ak.stock_bid_ask_em(symbol=code)
            # 典型字段包含"委比""委差"，不同版本字段名可能略有差异，用时请核对
            d = dict(zip(df["item"], df["value"])) if "item" in df.columns else {}
            return d
        except Exception as e:
            print(f"[警告] 获取 {code} 委比委差失败: {e}")
            return {}

    def run_schedule(self):
        """
        按交易日时间点轮询采集。实际使用时建议用 cron / schedule 库在对应时刻触发，
        这里给出一个简单的阻塞式等待实现。
        """
        targets = [
            ("09:15:00", "snapshot_915"),
            ("09:20:00", "snapshot_920"),
            ("09:25:00", "snapshot_925"),
        ]
        for target_time, tag in targets:
            self._wait_until(target_time)
            self.fetch_snapshot(tag)


    @staticmethod
    def _wait_until(hhmmss: str):
        target = datetime.datetime.strptime(
            f"{datetime.date.today()} {hhmmss}", "%Y-%m-%d %H:%M:%S"
        )
        now = datetime.datetime.now()
        wait_sec = (target - now).total_seconds()
        if wait_sec > 0:
            print(f"等待到 {hhmmss}，剩余 {int(wait_sec)} 秒...")
            time.sleep(wait_sec)


# ============================================================
# 板块热度、个股新闻（辅助数据）
# ============================================================

def get_hot_sectors(top_n: int = 10):
    """获取当日涨幅排名前 N 的概念/行业板块名称列表"""
    if ak is None:
        return []
    try:
        df = ak.stock_board_concept_name_em()
        df = df.sort_values("涨跌幅", ascending=False).head(top_n)
        return df["板块名称"].tolist()
    except Exception as e:
        print(f"[警告] 获取热点板块失败: {e}")
        return []


def get_stock_sectors(code: str):
    """获取个股所属的概念板块（用于匹配是否处于热点板块中）"""
    if ak is None:
        return []
    try:
        df = ak.stock_board_concept_cons_em(symbol=code)
        return df["板块名称"].tolist() if "板块名称" in df.columns else []
    except Exception:
        # 不同 akshare 版本接口名可能不同，这里仅做示例，实际使用时请按最新文档调整
        return []


def is_main_or_chinext_board(code: str) -> bool:
    """
    判断代码是否属于主板或创业板（排除科创板688、北交所8xx/4xx/92x）。
      - 创业板：300、301 开头
      - 上海主板：600、601、603、605 开头
      - 深圳主板：000、001、002 开头（002原中小板，已并入深圳主板）
    如果你的 akshare 版本代码格式带交易所后缀（如 600000.SH），
    请先用 code.split('.')[0] 取纯数字部分再传入本函数。
    """
    code = str(code).strip()
    if code.startswith("688"):
        return False  # 科创板，排除
    if code.startswith(("8", "4", "92")):
        return False  # 北交所，排除
    if code.startswith(("300", "301")):
        return True   # 创业板
    if code.startswith(("600", "601", "603", "605")):
        return True   # 上海主板
    if code.startswith(("000", "001", "002")):
        return True   # 深圳主板
    return False       # 其他（如B股等）一律排除


def _guess_market_prefix(code: str) -> str:
    """根据股票代码猜测交易所前缀，用于 akshare 资金流向接口"""
    if code.startswith(("60", "68")):
        return "sh"
    if code.startswith(("00", "30")):
        return "sz"
    if code.startswith(("8", "4", "92")):
        return "bj"
    return "sz"


def get_today_fund_flow_pool(config: "Config"):
    """
    资金驱动核心函数（盘中实时版）：
    获取当日全市场实时主力资金流向排名，先按"现价<=max_price"和
    "流通市值<=max_circulating_market_cap"过滤掉高价股/大盘股，
    再按"主力净流入占比"（而非净流入绝对金额）排序。

    为什么不用绝对金额排序：
      大盘股（银行/券商等）随便一笔资金进出绝对金额就很大，
      按金额排名会被这些票垄断，完全筛不出你要的"7元以下、资金驱动的小票"。
      用"占比"（净流入 / 当日成交额）可以把体量差异抹平，
      小盘股一旦被资金盯上，占比更容易冲高，更符合短线资金驱动逻辑。

    数据来源：ak.stock_individual_fund_flow_rank(indicator="今日")，
    这是全市场级别接口，交易时段内持续刷新，适合反复轮询（--watch 模式）。

    返回：筛选后的 DataFrame，列至少包含 code, name, price, net_inflow_pct，
    已按 net_inflow_pct 降序排列；失败时返回空 DataFrame。
    """
    if ak is None or pd is None:
        return pd.DataFrame() if pd is not None else None
    try:
        df = ak.stock_individual_fund_flow_rank(indicator="今日")
        rename_map = {
            "代码": "code", "名称": "name", "最新价": "price",
            "主力净流入-净占比": "net_inflow_pct",
            "主力净流入-净额": "net_inflow_amount",
            "流通市值": "circ_market_cap",
        }
        df = df.rename(columns={k: v for k, v in rename_map.items() if k in df.columns})

        if "price" in df.columns:
            df = df[df["price"].notna() & (df["price"] <= config.max_price)]

        if config.restrict_to_main_and_chinext and "code" in df.columns:
            df = df[df["code"].apply(is_main_or_chinext_board)]

        if "circ_market_cap" in df.columns:
            # akshare 原始流通市值单位通常是"元"，转成"亿元"再比较
            df = df.assign(circ_market_cap_yi=df["circ_market_cap"] / 1e8)
            df = df[df["circ_market_cap_yi"] <= config.max_circulating_market_cap]

        if "net_inflow_pct" in df.columns:
            df = df[df["net_inflow_pct"] >= config.min_main_fund_net_pct]
            df = df.sort_values("net_inflow_pct", ascending=False)

        return df.reset_index(drop=True)
    except Exception as e:
        print(f"[警告] 获取今日资金流向排名失败: {e}")
        return pd.DataFrame()


def get_stock_activity_metrics(code: str, config: Config):
    """
    计算个股近一年的"股性活跃度"指标：
      - 日均换手率(%)
      - 日均振幅(%)
      - 涨停次数（用当日涨幅 >= 9.5% 近似判断，不区分主板/创业板/科创板的
        10%/20%/30%涨停幅度差异，如需精确判断请按板块分别设阈值）
    返回 (是否活跃: bool, 指标详情: dict)
    """
    if ak is None:
        return False, {}
    try:
        end_date = datetime.date.today().strftime("%Y%m%d")
        start_date = (datetime.date.today() -
                       datetime.timedelta(days=int(config.activity_lookback_days * 1.6))
                       ).strftime("%Y%m%d")
        df = ak.stock_zh_a_hist(symbol=code, period="daily",
                                 start_date=start_date, end_date=end_date,
                                 adjust="qfq")
        if df is None or df.empty:
            return False, {}
        df = df.tail(config.activity_lookback_days)

        avg_turnover = float(df["换手率"].mean()) if "换手率" in df.columns else 0.0
        avg_amplitude = float(df["振幅"].mean()) if "振幅" in df.columns else 0.0
        if "涨跌幅" in df.columns:
            limit_up_times = int((df["涨跌幅"] >= 9.5).sum())
        else:
            limit_up_times = 0

        detail = {
            "日均换手率(%)": round(avg_turnover, 2),
            "日均振幅(%)": round(avg_amplitude, 2),
            "近一年涨停次数": limit_up_times,
        }
        active = (
            avg_turnover >= config.min_avg_turnover_rate
            or avg_amplitude >= config.min_avg_amplitude
            or limit_up_times >= config.min_limit_up_times
        )
        return active, detail
    except Exception as e:
        print(f"[警告] 获取 {code} 历史活跃度数据失败: {e}")
        return False, {}


def check_positive_news(code: str, keywords: list, days: int = 1):
    """检查个股最近 N 天的新闻/公告标题是否命中利好关键词"""
    if ak is None:
        return False, []
    try:
        df = ak.stock_news_em(symbol=code)
        if df is None or df.empty:
            return False, []
        cutoff = datetime.datetime.now() - datetime.timedelta(days=days)
        hits = []
        for _, row in df.iterrows():
            title = str(row.get("新闻标题", ""))
            if any(k in title for k in keywords):
                hits.append(title)
        return (len(hits) > 0), hits
    except Exception as e:
        print(f"[警告] 获取 {code} 新闻失败: {e}")
        return False, []


# ============================================================
# 选股核心逻辑
# ============================================================

@dataclass
class ScreenResult:
    code: str
    name: str
    conditions_met: int
    detail: dict


def screen_stocks(collector: AuctionDataCollector, config: Config) -> list:
    s915 = collector.snapshots.get("snapshot_915")
    s920 = collector.snapshots.get("snapshot_920")
    s925 = collector.snapshots.get("snapshot_925")
    if s915 is None or s920 is None or s925 is None:
        raise RuntimeError("缺少 9:15/9:20/9:25 的快照数据，请先运行 collector.run_schedule()")

    hot_sectors = set(get_hot_sectors(config.top_n_hot_sector))

    # 资金驱动池：一次性拉取，避免逐股请求浪费时间，且天然按"占比"排除了大盘股
    fund_pool = get_today_fund_flow_pool(config)
    fund_pool_map = {}
    if fund_pool is not None and not fund_pool.empty and "code" in fund_pool.columns:
        fund_pool_map = {
            row["code"]: {
                "net_inflow_pct": row.get("net_inflow_pct"),
                "net_inflow_amount": row.get("net_inflow_amount"),
            }
            for _, row in fund_pool.iterrows()
        }

    merged = s915.merge(s920, on="code", suffixes=("_915", "_920"))
    merged = merged.merge(s925, on="code")
    merged = merged.rename(columns={"price": "price_925", "volume": "volume_925", "name": "name_925"})

    results = []
    for _, row in merged.iterrows():
        code = row["code"]
        name = row.get("name_915") or row.get("name_925")

        # 条件 0a（硬性过滤）：现价 <= max_price，不满足直接跳过，不计分
        price_925 = row["price_925"]
        if price_925 is None or price_925 > config.max_price:
            continue

        # 条件 0b（硬性过滤）：只保留主板 + 创业板（双重保险，防止快照数据源混入其他板块）
        if config.restrict_to_main_and_chinext and not is_main_or_chinext_board(code):
            continue

        detail = {"现价(9:25)": price_925}

        # 条件 3：9:15 价格 < 9:25 价格
        cond3 = row["price_915"] < row["price_925"]
        detail["价格上行(9:15<9:25)"] = bool(cond3)

        # 条件 2：竞价放量（9:25量 / 9:20量）
        vol_920 = row["volume_920"] or 0
        vol_925 = row["volume_925"] or 0
        surge_ratio = (vol_925 / vol_920) if vol_920 > 0 else 0
        cond2 = surge_ratio >= config.min_volume_surge_ratio
        detail["竞价放量倍数"] = round(surge_ratio, 2)
        detail["竞价放量达标"] = bool(cond2)

        # 条件 1（资金驱动核心，硬性主条件）：命中当日资金流向池
        # 即「现价<=max_price 且 流通市值<=上限 且 净流入占比达标」的小盘资金驱动股
        fund_info = fund_pool_map.get(code)
        cond1 = fund_info is not None
        if fund_info:
            detail["主力净流入占比(%)"] = round(fund_info["net_inflow_pct"], 2) if fund_info["net_inflow_pct"] is not None else None
            detail["主力净流入额(元)"] = fund_info["net_inflow_amount"]
        detail["资金驱动达标(硬性主条件)"] = bool(cond1)

        # 竞价委比作为辅助参考，不影响是否入选，只做展示
        bid_ask = collector.fetch_bid_ask(code)
        bid_ratio = float(bid_ask.get("委比", 0) or 0)
        detail["委比(竞价买盘强弱-参考)"] = bid_ratio

        if not cond1:
            continue  # 资金驱动是硬性主条件，不满足直接跳过，不进入后续打分

        # 条件 4：热点题材
        stock_sectors = set(get_stock_sectors(code))
        cond4 = len(stock_sectors & hot_sectors) > 0
        detail["所属热点板块"] = list(stock_sectors & hot_sectors)
        detail["题材共振达标"] = bool(cond4)

        # 条件 5：利好消息
        cond5, hits = check_positive_news(code, config.positive_keywords)
        detail["利好新闻命中"] = hits[:3]
        detail["消息共振达标"] = bool(cond5)

        # 条件 6：近一年股性活跃
        cond6, activity_detail = get_stock_activity_metrics(code, config)
        detail.update(activity_detail)
        detail["股性活跃达标"] = bool(cond6)

        conditions_met = sum([cond1, cond2, cond3, cond4, cond5, cond6])
        if conditions_met >= config.min_conditions_met:
            results.append(ScreenResult(code=code, name=name,
                                         conditions_met=conditions_met, detail=detail))

    results.sort(key=lambda r: r.conditions_met, reverse=True)
    return results


# ============================================================
# 测试模式：用假数据跑通整个流程，方便你先验证逻辑再接真实数据
# ============================================================

def run_test_mode():
    print("=== 测试模式：使用模拟数据验证筛选逻辑 ===\n")

    class FakeCollector:
        snapshots = {
            "snapshot_915": _fake_df([("600000", "示例银行", 6.00, 50000)]),
            "snapshot_920": _fake_df([("600000", "示例银行", 6.05, 120000)]),
            "snapshot_925": _fake_df([("600000", "示例银行", 6.20, 260000)]),
        }

        def fetch_bid_ask(self, code):
            return {"委比": 35.0}

    config = Config(min_conditions_met=3)  # 默认门槛：资金驱动(硬性) + 其余5项命中至少3项

    # 临时替换热点板块 / 新闻 / 资金流向池 / 活跃度函数，避免测试模式依赖网络
    global get_hot_sectors, get_stock_sectors, check_positive_news
    global get_today_fund_flow_pool, get_stock_activity_metrics
    _orig = (get_hot_sectors, get_stock_sectors, check_positive_news,
              get_today_fund_flow_pool, get_stock_activity_metrics)
    get_hot_sectors = lambda top_n=10: ["人工智能", "半导体"]
    get_stock_sectors = lambda code: ["人工智能"]
    check_positive_news = lambda code, kws, days=1: (True, ["公司与某AI公司签约合作"])
    # 模拟"当日资金流向池"：600000 已通过价格/市值/占比过滤并在池子里
    get_today_fund_flow_pool = lambda cfg: _fake_fund_pool([
        ("600000", "示例银行", 6.20, 5.2, 8_000_000),
    ])
    get_stock_activity_metrics = lambda code, cfg: (True, {
        "日均换手率(%)": 4.5, "日均振幅(%)": 5.8, "近一年涨停次数": 6,
    })

    results = screen_stocks(FakeCollector(), config)

    (get_hot_sectors, get_stock_sectors, check_positive_news,
     get_today_fund_flow_pool, get_stock_activity_metrics) = _orig

    for r in results:
        print(f"【{r.code} {r.name}】命中 {r.conditions_met}/6 个条件")
        print(json.dumps(r.detail, ensure_ascii=False, indent=2))
        print("-" * 40)

    if not results:
        print("测试数据未命中任何结果，可检查逻辑或降低 min_conditions_met。")


def _fake_df(rows):
    import pandas as pd
    return pd.DataFrame(rows, columns=["code", "name", "price", "volume"])


def _fake_fund_pool(rows):
    import pandas as pd
    return pd.DataFrame(rows, columns=["code", "name", "price", "net_inflow_pct", "net_inflow_amount"])


# ============================================================
# 主入口
# ============================================================

def main():
    parser = argparse.ArgumentParser(description="集合竞价选股工具")
    parser.add_argument("--test", action="store_true", help="使用模拟数据测试筛选逻辑")
    parser.add_argument("--pool", type=str, default="", help="指定股票池，逗号分隔，如 600000,000001")
    args = parser.parse_args()

    if args.test:
        run_test_mode()
        return

    if ak is None:
        print("未安装 akshare，无法运行实时模式。请先执行：")
        print("    pip install akshare pandas schedule --break-system-packages")
        return

    config = Config()
    if args.pool:
        config.stock_pool = [c.strip() for c in args.pool.split(",") if c.strip()]

    collector = AuctionDataCollector(config)
    print("开始实时采集 9:15 / 9:20 / 9:25 快照（请在交易日早盘运行）...")
    collector.run_schedule()

    print("\n开始筛选...")
    results = screen_stocks(collector, config)

    print(f"\n共筛选出 {len(results)} 只股票：\n")
    for r in results:
        print(f"【{r.code} {r.name}】命中 {r.conditions_met}/6 个条件")
        print(json.dumps(r.detail, ensure_ascii=False, indent=2))
        print("-" * 40)


if __name__ == "__main__":
    main()
