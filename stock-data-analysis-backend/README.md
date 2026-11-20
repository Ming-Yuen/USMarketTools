# Stock Data Analysis Backend

個人全市場美股分析、估值及選股後端，定期同步美股行情、拆股、分類、富途財報與 SEC 財務事實，並提供全市場股票篩選、個股分析報告（含 14 項財務原料、三組估值參考區間與技術交易計畫）及歷史估值曲線。技術基礎為 Java 21、Spring Boot、PostgreSQL、JPA 與 Quartz。

## 1. 資料來源

- **Alpaca**：提供自 2016 年起的未復權日線行情（`stock_quote`），日常增量回看最近 5 個交易日補齊漏日。
- **Massive**：提供證券主檔與 composite FIGI，並由正式端點 `/stocks/v1/splits` 提供日常拆股／合股增量同步。
- **SEC 官方批量檔**：
  - `company_tickers.json`：唯一解析發行人十位數 `cik`。
  - `submissions.zip`：同步 4 碼 `stock.sic`、每份 `accession` 的精確 `reportDate`、`acceptanceDateTime` 與近期定期申報日期（供平日財報增量篩選）。
  - `companyfacts.zip`：同步核心 `us-gaap` 與 `dei` 標籤，自 2014 年起按披露唯一鍵整份 CIK JSON 比對新增與修訂。
- **富途 OpenD**：提供產業、概念、三大財務報表（損益表、資產負債表、現金流量表）、單季 EPS／營收預測、最新流通股數與原生估值快照，以及新股回補／週六全市場拆股對帳（`requestRehab`）。
- **Alpha Vantage**：提供美股 USD 財報營運公司的年度共識預測（`EARNINGS_ESTIMATES`，含 FY1／FY2 EPS、營收與 30／90 日前 EPS 基準）；採查詢時按需同步、7 日 DB 快取與持久化每日配額熔斷保護（每日機會雷達等批次掃描嚴禁觸發外部 API，只能唯讀 PostgreSQL `stock_earnings_forecast` 既有快取；非 USD 財報 ADR 嚴禁寫入以防混幣污染）。
- **FRED H.10 官方日頻匯率與高收益債信用利差**：提供 16 種主要外幣對美元官方匯率（`fx_rate_daily`），以及美國高收益債信用利差 ICE BofA US High Yield Index OAS（`BAMLH0A0HYM2`，`macro_credit_spread_daily`），用於總經信用風險監控與宏觀防禦連動。
- **籌碼與事件免費／已訂閱擴充通道（零額外付費訂閱）**：
  - **Alpaca Options Contracts & Snapshots**：按需採集目標股與 ETF 之 ATM Straddle Implied Move、ATM IV／HV20、等距虛值偏度（Skew）、Put/Call Ratio 與主力到期日最大 Call／Put OI 牆（強制標示非方向性提示）。
  - **SEC EDGAR XML／XBRL（Form 4、13F-HR、8-K／6-K 與本地 `companyfacts.zip`）**：遵守官方 `User-Agent` 與每秒請求限流，解析 Form 4 內部人交易（精準區分公開市場買入 `Code P` 與薪酬／`10b5-1` 例行交易）、追蹤旗艦機構 13F-HR 季度持倉，以及按需擷取 8-K／6-K `EX-99.1` 財測摘要與一次性人工確認自訂標籤映射。
  - **Massive Starter FINRA Short Interest（`/stocks/v1/short-interest`）**：官方雙週結算做空股數與回補天數；嚴格驗證單一股類流通股數分母（多股類、未核實 ADR、ETF 安全攔截 `shortPct` 不硬除）。
  - **多源 REST 新聞（Alpaca 主／Finnhub／Massive 備援）＋三通道輕量 AI 隔離閘道（`enhancement.ai.*`）**：與高價 `industry.valuation.ai.*` 完全隔離，預設 `provider=NONE`（零成本零報錯，前端隨時支援一鍵複製 Prompt → 貼回 JSON 驗收入庫），可選低推理成本 `OPENAI_API` 或本機唯讀隔離 `CODEX_CLI`。

## 2. 業務資料表

| 表 | 資料粒度／主要識別 | 用途 |
| --- | --- | --- |
| `stock` | `figi` | 股票主檔、`cik`、4 碼 `sic`、證券種類、幣別、有效狀態與人工追蹤開關 `tracked` |
| `stock_status` | `figi` | 行情、拆股、富途分類、富途財報、SEC 財務事實及估值的同步狀態與摘要 |
| `stock_quote` | `(figi, quote_date)` | Alpaca 未復權日線 OHLCV |
| `stock_split` | `source_event_id` | FIGI、拆股生效日、前後比例及觀察時間 |
| `stock_industry` | `code` | 產業名稱及來源板塊碼字典 |
| `stock_industry_history` | `id`（FIGI＋觀察起點） | 股票產業分類的有效觀察區間 |
| `stock_concept` | `code` | 概念名稱及來源板塊碼 |
| `stock_concept_mapping` | `(figi, concept_code)` | 股票目前所屬的多個概念 |
| `industry_valuation_config` | `(industry_code, effective_at)` | 人工確認的產業估值模型、主要指標及可選倍數假設 |
| `stock_financial_period` | `(figi, period_end)` | 標準化財季分母表：保存財季起訖、14 項核心財務原料、TTM 分母、EV 非市值調整項、期末流通股數／EPS 拆股基準、SEC 定期披露可用日，以及財務品質深化欄位（毛利、營業利益、稅前盈利、所得稅、利息費用、SEC SBC、現金股息、普通股淨發行、商譽與無形資產、短期有息債務） |
| `fx_rate_daily` | `(currency, rate_date)` | FRED H.10 官方日頻匯率表（`usd_per_unit`），供非美元財報公司與已核實 ADR 估值換算（超過 14 日標註 `FX_RATE_STALE`） |
| `adr_share_ratio` | `symbol` | ADR 換股比例核實表（`1 ADR = adr_ratio 原股`，啟動冪等種子內建 19 檔權值 ADR，未核實者標註 `ADR_RATIO_UNVERIFIED` 安全攔截） |
| `stock_valuation_latest` | `figi` | 最新已收市交易日的五項估值倍數、市值與規模分層（`market_cap_tier`）、三項財務品質指標（`revenue_growth_yoy`、`roe`、`fcf_margin`）、歷史分位數（`historical_percentile`）、客觀參考價帶（`fair_value_low`/`mid`/`high`）、基本面支撐（`fundamental_backing`）與估值定性（`verdict`）快照，供選股、雷達初篩與同業比較直接查詢 |
| `daily_opportunity_radar` | `(radar_date, track_type, figi)` | 每日收市兩階段雙軌機會雷達快照（低估支撐低接 `VALUE_SUPPORT` 與強勢股趨勢回踩試單 `TREND_PULLBACK_STARTER`），保存收盤基準之第一目標價、成本後淨賺賠比、止損價、財報天數與板塊資金流狀態 |
| `daily_opportunity_radar_run` | `radar_date` | 每日機會雷達發布紀錄（含零入選日、大盤市況與候選／發布檔數），確保當日首次完整發布後凍結快照，且零入選日不回退復活前一日名單 |
| `sector_money_flow_daily` | `(trade_date, industry_code)` | 全市場各產業板塊之成交額加權量價資金流（5D／20D CMF）、市場成交額佔比變化、拆股對齊均線廣度（SMA50／SMA200）與 20D／60D 完整視窗報酬中位數 |
| `signal_tracking_log` | `id`（唯一鍵 `(figi, signal_type, signal_date)`） | 歷史雷達訊號（`ACCUMULATE_AT_SUPPORT`、`TREND_PULLBACK_STARTER`）不可變帳本與事後跨拆股結算表，條件式回填 `+5D/+20D/+60D` 報酬、完整 60D 無缺口 MFE／MAE 與止盈止損首次觸發狀態 |
| `portfolio_item` | `figi` | 個人持倉（`HOLDING`）與重點自選（`WATCHLIST`）清單，保存股數、均價、美東拆股基準日（`cost_basis_date`）與備註 |
| `in_app_notification` | `id`（唯一去重鍵 `dedup_key`） | 站內主動提醒持久化表，按嚴重度（`CRITICAL`／`WARNING`／`INFO`）保存持倉異常、自選買點、雷達精選、大盤切換與簡報通知及已讀狀態 |
| `daily_closing_briefing` | `trade_date` | 每日收盤簡報持久化快照（每交易日首次完整發布即凍結），彙整大盤市況、強弱前 2 板塊、持倉體檢摘要、自選買點與雷達雙軌精選前 5 名 |
| `stock_custom_peer` | `(target_figi, peer_figi)` | 個股自訂對標同業清單與可選偏好比較指標（`preferred_metric`） |
| `stock_share_observation` | `(figi, observed_at)` | 富途流通在外股數、已發行股數、對應市值及原生 P/E、P/B 快照觀察（僅在股數變動或當日尚無收市快照時寫入） |
| `financial_report` | `(figi, statement_type, period_end, period_type)` | 富途原幣三大財報（損益表 `1`、資產負債表 `2`、現金流量表 `3`） |
| `sec_financial_fact` | `id`（披露唯一鍵 `NULLS NOT DISTINCT`） | SEC 標準 `us-gaap`／`dei` 財務與股數披露事實 |
| `stock_earnings_forecast` | `(figi, fiscal_period, observation_date, period_type, source)` | 多來源分析師 EPS／營收預測（富途單季預測＋Alpha Vantage 按需年度共識與 `eps30d_ago`／`eps90d_ago` 基準） |
| `external_api_daily_quota` | `(provider, quota_date)` | 低配額外部來源的持久化每日請求配額與熔斷旗標 |
| `treasury_par_yield_daily` | `observation_date` | 美國財政部國債殖利率資料 |
| `market_sentiment_daily` | `market_date` | 市場情緒指標、VIX 與觀察時間 |
| `market_event` | `event_key` | 個股財報日曆與宏觀事件 |
| `macro_credit_spread_daily` | `observation_date` | FRED 美國高收益債信用利差（`BAMLH0A0HYM2`）日頻原始觀測值 |
| `stock_options_summary` | `(figi, snapshot_date)` | 目標股／ETF 輕量期權快照（ATM Straddle Implied Move、ATM IV／HV20、偏度 Skew、Put/Call Ratio 與主力到期日最大 Call／Put OI 牆） |
| `sec_form4_transaction` | `(accession, transaction_index)` | SEC Form 4 內部人交易明細（精準標記公開市場買進 `Code P` 與薪酬／`10b5-1` 例行交易） |
| `sec_13f_holding` | `(manager_cik, report_period, cusip, share_type, put_call)` | 旗艦機構 SEC 13F-HR 季度聚合持倉與 FIGI 解析對應 |
| `stock_short_interest` | `(figi, settlement_date)` | FINRA 雙週結算做空股數、已核實單一股類做空佔比與回補天數（DTC） |
| `stock_news_event_cache` | `(figi, source_type, external_id)` | 按需同步之多源新聞與 SEC 8-K／6-K（含 `EX-99.1` 財測節錄）快取 |
| `stock_event_ai_summary` | `(figi, input_fingerprint)` | 結構化 3 點事件摘要與官方白紙黑字財測區間快取（支援 `OPENAI_API`／`CODEX_CLI`／手動 JSON 匯入，原子認領去重） |
| `sec_custom_tag_mapping` | `(cik, taxonomy, custom_tag, unit)` | 經前端人工點選確認生效之 SEC 自訂標籤顯式映射規則（確認後日常 0 AI 消耗） |
| `sec_custom_financial_fact` | 披露唯一鍵（獨立隔離表） | 依已確認映射規則確定性回填之自訂 XBRL 事實（與標準 `sec_financial_fact` 隔離，僅在重建財季分母時補標準標籤缺口） |

`job_config`、Quartz 表、`menu`、`search_criteria_config`、`upload_job` 等為排程、選單與匯入基礎設施表。

### 股票追蹤開關（`stock.tracked`）與證券範圍

- `stock.tracked` 預設為 `true`（全市場自動納入），主檔同步任務不會覆寫既有股票的人工設定。
- 當使用者透過 API 將個別標的設為 `tracked = false` 時，該股會跳過富途財報同步、SEC 財務同步、估值計算、分類同步、盈餘預測與全市場選股。
- **關聯不斷鏈保護**：若某停追股與任一 `tracked = true` 股票共用非空 `cik`（如雙股類兄弟股），或屬於大盤基準 ETF，行情（`StockQuoteDailyTask`）與拆股（`StockSplitDailyTask`）仍繼續同步；同發行人多股類判定排除 `ADR` 干擾。

## 3. 財報提取、全市場會計規則與財季分母架構

### 3.1 富途多模板靜態映射與排程減負

- `FINANCIAL_REPORT_DAILY_SYNC` 僅同步損益表（`1`）、資產負債表（`2`）與現金流量表（`3`），不再抓取 `MainIndex`（`4`）。平日僅針對尚無財報水位的新股、或近 14 天在財報日曆／SEC `submissions.zip` 有新申報的股票增量同步；每週六執行全市場巡檢。
- `FinancialReportValuationCalculator` 內建多模板靜態映射規則（涵蓋一般工商 `8xxx`、銀行 `9xxx`、保險 `10xxx`），優先以 `(statement_type, fieldId)` 複合鍵匹配（避免跨表重號衝突），缺 `fieldId` 時退回標準英文項目名稱，未知細項直接忽略不報警，無須維護資料庫審核映射表。

### 3.2 全市場估值會計與工程準則

- **營業收入**：富途優先取 `REVENUE_AS_REPORTED`（缺時退回 `OPERATING_REVENUE`）；SEC 端對非金融營運企業（依 `stock.sic` 排除銀行 `60xx-61xx`、券商 `62xx`、保險 `63xx-64xx`、`6712` 及列示利息淨收入者）缺少 `Revenues`／`SalesRevenueNet` 時，直接採用完整期間的 `RevenueFromContractWithCustomerExcludingAssessedTax`。
- **普通股權益與優先股**：未披露優先股或優先股為 `0` 時直接採用母公司股東權益（`StockholdersEquity`，缺時採 `TotalEquity - MinorityInterest`）；優先股帳面值 `> 0` 時，P/B 扣除優先股帳面值，EV 加上優先股帳面值作標準帳面近似。
- **營運口徑 EBITDA**：全市場非金融營運企業統一採用 `Operating Profit + DD&A`，支援 `US_GAAP` 與 `IFRS`（含富途標記之 `NonUS_GAAP`，同一 TTM 期間準則須一致）。損益表缺折舊攤銷時，直接讀取富途現金流量表 DD&A 或 SEC 標準折舊攤銷總額標籤（含礦業 `Depletion` 與公用事業 `Accretion`），並在計算口徑中標註。
- **EV 債務與租賃分流**：`IFRS` 因採單一承租人模型，EV 債務直接納入全部租賃負債；`US_GAAP` 優先排除已披露營運租賃負債，若缺租賃拆分明細但已有不含租賃之純借款（`Current Debt` + `Long-Term Debt`），直接採純借款計算 EV。
- **金融機構模型對齊**：統一依 `industry_valuation_config`（`BANK`／`INSURANCE`）與 `stock.sic` 判定金融機構，正常計算 `P/E`、`P/B`，並將 `P/FCF`、`EV/EBITDA` 標為 `NOT_APPLICABLE`。
- **多股類市值工程準則**：單一普通股採用扣除庫藏股後的流通在外股數（`outstandingShares`）；多股類公司最新估值採富途快照對應等值總市值，歷史市值倍數絕不將兩類等值市值重複相加，僅在最新等值總股數與同日拆股調整後之 SEC 申報總股數吻合（誤差 `<= 2%`，代表符合 `META`、`GOOGL` 等標準 1:1 經濟權結構）時沿用申報總股數計算，否則（如 `BRK.B` 1:1500）標記 `SHARE_CLASS_ECONOMIC_BASIS_UNVERIFIED`。
- **拆股跨越期的 EPS 驗證**：跨越拆股日的歷史單季 EPS，僅在採拆股後提交之直接申報 EPS、拆股前原申報（期末至申報日間無拆股）按已知拆股因子對齊一致，或經同報期盈利與 SEC 加權平均稀釋股數（`WeightedAverageNumberOfDilutedSharesOutstanding`）驗證一致時放行，絕不用期末股本做數量級猜測。
- **負值分母、外幣換算、ADR 與 ETF 邊界**：
  - 負 EPS、負 FCF、負 EBITDA 或負普通股權益在 `stock_financial_period` 與前端財務面板保留真實負值（`AVAILABLE`），對應估值倍數標為 `NOT_APPLICABLE`（N.M.）且倍數為 `NULL`；針對負權益藍籌補齊 **ROIC（`NOPAT / Invested Capital`）**、SBC 實質自由現金流（`Adj. FCF = FCF - SBC`、`P/Adj. FCF`）、Total Shareholder Yield、毛利／營益率變動與連兩季壓縮警戒、EV/Sales、EV/Gross Profit、Rule of 40、P/TBV、Interest Coverage 與短期流動性覆蓋率。
  - **獨立兩階段 DCF 與 Reverse DCF**：採股權現金流（FCF 近似 FCFE，折現率 = 10Y 美債 + 5.0% ERP，永續成長 2.5%）提供標準 FCF 與扣 SBC 實質 FCF 雙軌三情境估價及市場隱含成長反解，嚴格獨立於市場相對估值帶 `[L, M, H]` 與 `verdict` 之外；負 FCF、銀行／保險、REITs 與 ETF 優雅回傳 `NOT_APPLICABLE`。
  - **非美元報表與 ADR**：依 `fx_rate_daily` 官方匯率折算至 USD 並於回應 `fxConversion` 標註匯率基準；已核實 ADR 依 `adr_share_ratio` 將原股數折算為 ADR 等值股數並採公司層級 P/E（`MarketCap_USD / NetIncome_USD`）消除每股與原股口徑錯位，未核實 ADR 攔截為 `ADR_RATIO_UNVERIFIED`（僅保留不依賴股份單位之原幣比率）；非 USD 財報 ADR 維持禁止寫入 Alpha Vantage 年度預測以防混幣污染。註：現行 `adr_share_ratio` 採單一現行有效比例，若個別 ADR 歷史曾變更過換股比例（如 `TM` 2021 年前後由 1:2 改為 1:10），舊期歷史估值仍以現行比例換算，後續批次可視需要擴充生效區間版本表。
  - **ETF／ETP 路由**：`POST /analysis/report` 與 `GET /stock/screen?assetType=ETF` 支援 ETF／ETP，財報估值與 DCF 標示 `NOT_APPLICABLE_FOR_ETF`，跳過單一公司財報門鎖同時保留宏觀事件與大盤市況控管，完整輸出 K 線結構、支撐阻力、Volume Profile、AVWAP、資金流與技術交易計畫。

### 3.3 財季分母表與即時歷史估值

- 同一報期以 `period_end` 相差 `<= 7` 天且同期間類型（單季或年度）配對富途與 SEC，正規化為 SEC 實際結算日（無 SEC 時採富途結算日），以富途為主、SEC 標準標籤補缺，絕不跨報期拼湊。
- 當單一股票有新財報、SEC 內容變更或拆股更新時，直接整股重建該股所有 `stock_financial_period` 列（每股約 40 列）並刷新 `stock_valuation_latest`，確保包含修訂季度的後續滾動 TTM 同步生效。
- 查詢歷史估值（含個股分析 5 年月末序列、指定歷史 `valuationDate` 及 `GET /valuation/{figi}/history?source=LOCAL`）時，優先配對 `period_start <= D <= period_end` 的財季分母列，若該期尚未入庫則回退 `period_end < D` 的最近一列，在記憶體中將未復權收盤價、拆股因子與財季分母即時相除。

## 4. API 契約

路徑均為應用程式相對路徑，JSON 欄位使用 camelCase。

| 方法／路徑 | 用途 |
| --- | --- |
| `GET /stock/screen` | 全市場追蹤中營運股票與 ETF 篩選與排序（關聯 `stock_valuation_latest`）；支援 `assetType`（`STOCK`\|`ETF`\|`ALL`）、`symbol`、`industry`、`conceptCode`、`marketCapTier`、`verdict`、財務品質與歷史分位數門檻及 `sortBy`/`sortDir`/`page`/`size` |
| `PATCH /stock/{figi}/tracked` | 更新單一股票的人工追蹤開關 `{ "tracked": true \| false }` |
| `POST /stock/tracked/batch` | 批量更新多檔股票的人工追蹤開關 `{ "figis": [...], "tracked": true \| false }` |
| `POST /stock/classification` | 交易所、產業、概念、資產類型（`assetTypes`）、市值規模分層（`marketCapTiers`）、估值定性（`verdicts`）與支援排序欄位（`sortFields`）字典查詢 |
| `POST /analysis/report` | 個股／ETF 分析報告（個股回傳 `valuation` 含 `fxConversion`、財務品質深化 `fundamentals`、獨立兩階段 `dcf`／Reverse DCF、同業參考與強弱、前瞻預期、14 項財務原料、5 年 `history`、技術結構與交易計畫及執行摘要 `summary`；ETF 回傳 `NOT_APPLICABLE_FOR_ETF` 與完整技術／資金分析及免財報交易計畫） |
| `GET /valuation/adr-ratios`、`PUT /valuation/adr-ratios` | 查詢、人工核實／更新 ADR 換股比例對照表（`symbol` 由 JSON body 提供，設 `verified=false` 可撤銷核實），寫入後自動觸發該 ADR 財季分母重建與最新估值刷新 |
| `GET /portfolio`、`PUT /portfolio/{figi}`、`DELETE /portfolio/{figi}` | 個人持倉／自選清單（含拆股對齊損益與 USD 板塊曝險彙總）查詢、新增更新與移除 |
| `GET /portfolio/config`、`PUT /portfolio/config` | 讀寫 `sys_config` 內之總資金（USD）、單筆風險比例、單檔上限與單一板塊上限風控參數 |
| `GET /portfolio/health` | 查詢持倉與自選股體檢警示卡（預設讀最新已發布收盤簡報快照，`live=true` 時按目前持倉純本地唯讀即時重算，不觸發外部 API 或寫通知） |
| `GET /notifications`、`GET /notifications/unread-count`、`POST /notifications/{id}/read`、`POST /notifications/read-all`、`GET /notifications/briefing/latest` | 站內主動提醒中心查詢、未讀紅點計數、單筆／全部已讀管理與最新每日收盤簡報查詢 |
| `GET /valuation/{figi}/custom-peers`、`PUT`、`DELETE` | 查詢、儲存或清除指定 `figi` 的自訂對標同業清單與偏好比較指標，並即時同步最新 `verdict` 快照 |
| `GET /valuation/{figi}/history` | 個股歷史估值曲線；參數 `from`、`through`、`frequency`（`MONTH_END`\|`DAILY`）、`source`（預設 `LOCAL` 本地五倍數，可選 `FUTU` 原生三倍數） |
| `POST /valuation/recalculate` | 手動整股重建指定 `figi` 的 `stock_financial_period` 並刷新 `stock_valuation_latest`，回傳區間估值 |
| `GET /industry-valuation` | 唯讀查詢全平台產業估值設定總覽（含活躍個股數、模型／指標／合理倍數完整度與支援模型字典，零 AI 消耗） |
| `GET /industry-valuation/{code}` | 查詢正式產業估值設定、支援模型及是否可產生 AI 草稿 |
| `POST /industry-valuation/{code}/proposals` | 同步產生待確認 AI 產業倍數草稿（不自動持久化） |
| `PUT /industry-valuation/{code}/config` | 人工確認並保存正式產業估值設定 |
| `POST /overview/dashboard` | 市場總覽儀表板（整合指數與 ETF 觀察、大盤 `marketRegime` 橫幅與高收益債信用利差 HY OAS 警戒、11 大板塊代表 ETF 對照、板塊量價資金流與均線廣度、最新發布日雙軌機會雷達快照（含高管逢低買進確認徽章）及歷史訊號勝率統計） |
| `GET /reports/buy-ranking/options`、`POST /reports/buy-ranking`、`POST /reports/buy-ranking/export` | 產業、個人清單與自訂代碼「建議買入排行榜」決策報表（0 AI 消耗、0 外部 HTTP、上限 300 檔批量預載、四梯隊防價值陷阱排序、ETF 雙軌評分與 1% 風控倉位試算）；`export?format=XLSX\|CSV&reportId=...` 共用同一次不可變快照輸出雙分頁 `.xlsx` 與 UTF-8 BOM 防公式注入 `.csv` |
| `GET /enhancements/macro/credit-spread`、`POST /enhancements/macro/credit-spread/refresh` | 查詢或按需同步 FRED 高收益債信用利差（`BAMLH0A0HYM2`）與 20 期變動狀態 |
| `GET /enhancements/stocks/{figi}` | 查詢單一標的完整籌碼與事件擴充總覽（AI 通道狀態、期權、Form 4、13F、做空、快取新聞／摘要與已確認 SEC 自訂標籤映射） |
| `POST /enhancements/stocks/{figi}/options/refresh`、`insiders/refresh`、`short-interest/refresh`、`news/refresh` | 單一標的按需即時同步期權、Form 4、做空比例或新聞／8-K 快取 |
| `POST /enhancements/stocks/{figi}/news/summarize`、`GET .../news/prompt`、`POST .../news/import` | 事件／8-K 財測三通道摘要（自動輕量 AI、零成本精簡 Prompt 匯出、手動 JSON 驗證入庫） |
| `GET /enhancements/stocks/{figi}/custom-tags/candidates`、`GET .../prompt`、`POST .../propose`、`POST .../import`、`POST .../confirm`、`DELETE .../{mappingId}` | SEC 自訂標籤抽樣、三通道預覽提議（確認前嚴禁寫庫）、人工確認落庫確定性重算與停用 |
| `POST /enhancements/13f/refresh` | 手動按需同步旗艦機構 13F-HR 季度持倉（支援 `maxManagers` 參數） |
| `POST /job/enquiry`、`/job/update`、`/job/launch` | 查詢、調整或手動觸發排程任務 |
| `GET /upload/types`、`POST /upload`、`GET /upload/{id}` | CSV 股票產業匯入與任務狀態查詢 |

## 5. 排程與設定

預設排程時區為 `Asia/Hong_Kong`（既有 `job_config` 設定不會在重啟時被覆蓋）：

| `taskCode` | 預設時間（香港） | 主要職責 |
| --- | --- | --- |
| `TREASURY_DAILY_SYNC` | 每日 05:00 | 美國財政部國債殖利率同步 |
| `FX_RATE_DAILY_SYNC` | 每日 05:05 | FRED H.10 16 種主要外幣官方日頻匯率增量同步（寫入 `fx_rate_daily`） |
| `CREDIT_SPREAD_DAILY_SYNC` | 每日 05:08 | FRED 美國高收益債信用利差（`BAMLH0A0HYM2`）增量同步（遇假日沿用最近有效觀測日不中斷估值排程） |
| `STOCK_DAILY` | 週二至六 05:15 | Massive 股票主檔與 SEC `company_tickers.json` CIK 同步 |
| `STOCK_SPLIT_DAILY` | 每日 05:45 | 平日 Massive `/stocks/v1/splits` 增量同步；新股與週六富途 `requestRehab` 全量對帳 |
| `STOCK_QUOTE_DAILY` | 週二至六 06:15 | Alpaca 日線增量同步（回看最近 5 個交易日補漏） |
| `FINANCIAL_REPORT_DAILY_SYNC` | 週二至六 06:30 | 富途三大財報同步（平日近 14 天新申報增量，週六全市場巡檢），變更時重建財季分母 |
| `STOCK_CLASSIFICATION_DAILY_SYNC` | 週二至六 07:00 | 富途產業與概念分類同步 |
| `SEC_FINANCIAL_FACTS_DAILY_SYNC` | 每日 08:00 | SEC `companyfacts.zip` 與 `submissions.zip` 整份 CIK 比對同步（並確定性同步已確認自訂標籤映射公司之 `sec_custom_financial_fact`，0 AI 消耗），變更時重建財季分母 |
| `SHORT_INTEREST_SYNC` | 週二、五 08:20 | FINRA 雙週做空比例增量同步（透過 Massive `/stocks/v1/short-interest` 批量更新） |
| `MARKET_SENTIMENT_DAILY` | 每日 08:30 | 市場情緒與 VIX 同步 |
| `MARKET_EVENT_DAILY_SYNC` | 每日 08:40 | 市場與財報事件日曆同步 |
| `EARNINGS_FORECAST_DAILY_SYNC` | 每日 08:50 | 富途盈餘與營收預測同步 |
| `STOCK_VALUATION_DAILY` | 每日 10:00 | 富途最新股數／估值快照採集、補建缺失財季分母、刷新全市場 `stock_valuation_latest`；日常全市場執行且當日行情覆蓋 `>= 95%`、大盤就緒後，依序刷新 `sector_money_flow_daily`、執行兩階段雷達掃描並同交易原子發布 `daily_opportunity_radar`/`daily_opportunity_radar_run` 與當日新訊號（已發布日凍結不覆寫）、獨立結算歷史到期訊號（550 天追溯視窗、`REPEATABLE READ` 跨拆股讀取），最後以獨立 `REQUIRES_NEW` 交易與例外隔離執行持倉自動體檢（含財報跳空止損風險）及站內收盤簡報／提醒寫入（含高收益債信用利差警戒，異常僅記警告日誌，絕不回滾上游估值、資金流與雷達快照） |
| `CHIP_EVENT_DAILY_SYNC` | 週二至六 10:25 | 精準範圍籌碼與事件同步（僅針對持倉股、重點自選股、當日雷達入選股及單日漲跌 `±5%` 異動股同步期權、Form 4 與新聞／8-K，嚴禁掃描全市場 5,000 檔，預設 0 AI 消耗） |
| `INSTITUTIONAL_13F_SYNC` | 每週日 09:00 | 旗艦機構（約 15 家）SEC 13F-HR 季度持倉同步與 OpenFIGI／Massive CUSIP→FIGI 解析 |

### 舊版資料庫清理與枚舉約束說明

DEV 環境由 Hibernate `ddl-auto=update` 自動建立 `stock_financial_period`、`stock_valuation_latest`、`fx_rate_daily` 與 `adr_share_ratio`，並於 `DataInitializer` 啟動時自動清除既有 `stock_financial_period`／`stock_valuation_latest` 上因 Hibernate 舊版枚舉產生的 `*_reason*_check` 約束（避免新增 `ValuationReason` 如 `ADR_RATIO_UNVERIFIED`、`FX_RATE_UNAVAILABLE`、`FX_RATE_STALE` 時觸發 PostgreSQL `23514` 約束違規）。於 `ddl-auto=none` 的 UAT／PROD 升級前，須同步套用新表、擴充欄位並移除該兩表舊有 `*_reason*_check` 約束。

已退役的 `stock_valuation_daily` 與 `financial_report_item_mapping` 已從程式碼與 ORM 移除；確認新鏈路驗收無誤後，可在已確認的目標資料庫手動執行清理：

```sql
DROP TABLE IF EXISTS public.stock_valuation_daily;
DROP TABLE IF EXISTS public.financial_report_item_mapping;
```
