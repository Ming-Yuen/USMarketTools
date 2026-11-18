# Stock Data Analysis Backend

個人全市場美股分析、估值及選股後端，定期同步美股行情、拆股、分類、富途財報與 SEC 財務事實，並提供全市場股票篩選、個股分析報告（含 14 項財務原料、三組估值參考區間與技術交易計畫）及歷史估值曲線。技術基礎為 Java 21、Spring Boot、PostgreSQL、JPA 與 Quartz。

## 1. 資料來源

- **Alpaca**：提供自 2016 年起的未復權日線行情（`stock_quote`），日常增量回看最近 5 個交易日補齊漏日。
- **Massive**：提供證券主檔與 composite FIGI，並由正式端點 `/stocks/v1/splits` 提供日常拆股／合股增量同步。
- **SEC 官方批量檔**：
  - `company_tickers.json`：唯一解析發行人十位數 `cik`。
  - `submissions.zip`：同步 4 碼 `stock.sic`、每份 `accession` 的精確 `reportDate`、`acceptanceDateTime` 與近期定期申報日期（供平日財報增量篩選）。
  - `companyfacts.zip`：同步核心 `us-gaap` 與 `dei` 標籤，自 2014 年起按披露唯一鍵整份 CIK JSON 比對新增與修訂。
- **富途 OpenD**：提供產業、概念、三大財務報表（損益表、資產負債表、現金流量表）、EPS／營收預測、最新流通股數與原生估值快照，以及新股回補／週六全市場拆股對帳（`requestRehab`）。

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
| `stock_financial_period` | `(figi, period_end)` | 標準化財季分母表：保存財季起訖、14 項財務原料、TTM 分母、EV 非市值調整項、期末流通股數／EPS 拆股基準與 SEC 定期披露可用日欄位 |
| `stock_valuation_latest` | `figi` | 最新已收市交易日的五項估值倍數、市值與規模分層（`market_cap_tier`）、三項財務品質指標（`revenue_growth_yoy`、`roe`、`fcf_margin`）、歷史分位數（`historical_percentile`）與估值定性（`verdict`）快照，供選股與同業比較直接查詢 |
| `stock_custom_peer` | `(target_figi, peer_figi)` | 個股自訂對標同業清單與可選偏好比較指標（`preferred_metric`） |
| `stock_share_observation` | `(figi, observed_at)` | 富途流通在外股數、已發行股數、對應市值及原生 P/E、P/B 快照觀察（僅在股數變動或當日尚無收市快照時寫入） |
| `financial_report` | `(figi, statement_type, period_end, period_type)` | 富途原幣三大財報（損益表 `1`、資產負債表 `2`、現金流量表 `3`） |
| `sec_financial_fact` | `id`（披露唯一鍵 `NULLS NOT DISTINCT`） | SEC 標準 `us-gaap`／`dei` 財務與股數披露事實 |
| `stock_earnings_forecast` | `(figi, fiscal_period, period_type, observation_date)` | 富途 EPS／營收預測 |
| `treasury_par_yield_daily` | `observation_date` | 美國財政部國債殖利率資料 |
| `market_sentiment_daily` | `market_date` | 市場情緒指標、VIX 與觀察時間 |
| `market_event` | `event_key` | 個股財報日曆與宏觀事件 |

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
- **負值分母與外幣邊界**：負 EPS、負 FCF、負 EBITDA 或負普通股權益在 `stock_financial_period` 與前端財務面板保留真實負值（`AVAILABLE`），對應估值倍數標為 `NOT_APPLICABLE`（N.M.）且倍數為 `NULL`。財報原幣與股價幣別不同時維持 `REPORTING_CURRENCY_MISMATCH` 不自動匯兌。

### 3.3 財季分母表與即時歷史估值

- 同一報期以 `period_end` 相差 `<= 7` 天且同期間類型（單季或年度）配對富途與 SEC，正規化為 SEC 實際結算日（無 SEC 時採富途結算日），以富途為主、SEC 標準標籤補缺，絕不跨報期拼湊。
- 當單一股票有新財報、SEC 內容變更或拆股更新時，直接整股重建該股所有 `stock_financial_period` 列（每股約 40 列）並刷新 `stock_valuation_latest`，確保包含修訂季度的後續滾動 TTM 同步生效。
- 查詢歷史估值（含個股分析 5 年月末序列、指定歷史 `valuationDate` 及 `GET /valuation/{figi}/history?source=LOCAL`）時，優先配對 `period_start <= D <= period_end` 的財季分母列，若該期尚未入庫則回退 `period_end < D` 的最近一列，在記憶體中將未復權收盤價、拆股因子與財季分母即時相除。

## 4. API 契約

路徑均為應用程式相對路徑，JSON 欄位使用 camelCase。

| 方法／路徑 | 用途 |
| --- | --- |
| `GET /stock/screen` | 全市場追蹤中營運股票篩選與排序（關聯 `stock_valuation_latest`）；支援 `symbol`、`industry`、`conceptCode`、`marketCapTier`、`verdict`、財務品質與歷史分位數門檻及 `sortBy`/`sortDir`/`page`/`size` |
| `PATCH /stock/{figi}/tracked` | 更新單一股票的人工追蹤開關 `{ "tracked": true \| false }` |
| `POST /stock/tracked/batch` | 批量更新多檔股票的人工追蹤開關 `{ "figis": [...], "tracked": true \| false }` |
| `POST /stock/classification` | 交易所、產業、概念、市值規模分層（`marketCapTiers`）、估值定性（`verdicts`）與支援排序欄位（`sortFields`）字典查詢 |
| `POST /analysis/report` | 個股分析報告（回傳 `valuation`、規模分層／自訂對標同業參考、前瞻估值與預期修正觀察、`financials` 14 項指標、5 年月頻 `history`、技術支撐阻力與執行摘要 `summary`） |
| `GET /valuation/{figi}/custom-peers`、`PUT`、`DELETE` | 查詢、儲存或清除指定 `figi` 的自訂對標同業清單與偏好比較指標，並即時同步最新 `verdict` 快照 |
| `GET /valuation/{figi}/history` | 個股歷史估值曲線；參數 `from`、`through`、`frequency`（`MONTH_END`\|`DAILY`）、`source`（預設 `LOCAL` 本地五倍數，可選 `FUTU` 原生三倍數） |
| `POST /valuation/recalculate` | 手動整股重建指定 `figi` 的 `stock_financial_period` 並刷新 `stock_valuation_latest`，回傳區間估值 |
| `GET /industry-valuation/{code}` | 查詢正式產業估值設定、支援模型及是否可產生 AI 草稿 |
| `POST /industry-valuation/{code}/proposals` | 同步產生待確認 AI 產業倍數草稿（不自動持久化） |
| `PUT /industry-valuation/{code}/config` | 人工確認並保存正式產業估值設定 |
| `POST /overview/dashboard` | 市場總覽儀表板 |
| `POST /job/enquiry`、`/job/update`、`/job/launch` | 查詢、調整或手動觸發排程任務 |
| `GET /upload/types`、`POST /upload`、`GET /upload/{id}` | CSV 股票產業匯入與任務狀態查詢 |

## 5. 排程與設定

預設排程時區為 `Asia/Hong_Kong`（既有 `job_config` 設定不會在重啟時被覆蓋）：

| `taskCode` | 預設時間（香港） | 主要職責 |
| --- | --- | --- |
| `TREASURY_DAILY_SYNC` | 每日 05:00 | 美國財政部國債殖利率同步 |
| `STOCK_DAILY` | 週二至六 05:15 | Massive 股票主檔與 SEC `company_tickers.json` CIK 同步 |
| `STOCK_SPLIT_DAILY` | 每日 05:45 | 平日 Massive `/stocks/v1/splits` 增量同步；新股與週六富途 `requestRehab` 全量對帳 |
| `STOCK_QUOTE_DAILY` | 週二至六 06:15 | Alpaca 日線增量同步（回看最近 5 個交易日補漏） |
| `FINANCIAL_REPORT_DAILY_SYNC` | 週二至六 06:30 | 富途三大財報同步（平日近 14 天新申報增量，週六全市場巡檢），變更時重建財季分母 |
| `STOCK_CLASSIFICATION_DAILY_SYNC` | 週二至六 07:00 | 富途產業與概念分類同步 |
| `SEC_FINANCIAL_FACTS_DAILY_SYNC` | 每日 08:00 | SEC `companyfacts.zip` 與 `submissions.zip` 整份 CIK 比對同步，變更時重建財季分母 |
| `MARKET_SENTIMENT_DAILY` | 每日 08:30 | 市場情緒與 VIX 同步 |
| `MARKET_EVENT_DAILY_SYNC` | 每日 08:40 | 市場與財報事件日曆同步 |
| `EARNINGS_FORECAST_DAILY_SYNC` | 每日 08:50 | 富途盈餘與營收預測同步 |
| `STOCK_VALUATION_DAILY` | 每日 10:00 | 富途最新股數／估值快照採集、補建缺失財季分母並刷新全市場 `stock_valuation_latest` |

### 舊版資料庫清理說明

DEV 環境由 Hibernate `ddl-auto=update` 自動建立 `stock_financial_period` 與 `stock_valuation_latest`。已退役的 `stock_valuation_daily` 與 `financial_report_item_mapping` 已從程式碼與 ORM 移除；確認新鏈路驗收無誤後，可在已確認的目標資料庫手動執行清理：

```sql
DROP TABLE IF EXISTS public.stock_valuation_daily;
DROP TABLE IF EXISTS public.financial_report_item_mapping;
```
