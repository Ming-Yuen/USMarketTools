# Stock Data Analysis Backend

個人美股分析後端，定期同步美股行情、分類、富途財報及估值，並提供股票篩選、分析報告及圖表。技術基礎為 Java 21、Spring Boot、PostgreSQL、JPA 與 Quartz。

## 1. 資料來源

- Alpaca 提供日行情，Futu 提供拆股復權資料；Massive 提供股票主檔，SEC ticker 對照補充 CIK。
- 富途提供產業、概念、財報、EPS／營收預測和市場估值倍數。

## 2. 業務資料表

| 表 | 資料粒度／主要識別 | 用途 |
| --- | --- | --- |
| `stock` | 每個 FIGI | 股票主檔、CIK、股票種類、幣別與有效狀態 |
| `stock_status` | 每個 FIGI | 行情、拆股及富途分類的同步狀態 |
| `stock_quote` | FIGI＋交易日 | Alpaca 日 OHLCV |
| `stock_split` | 來源拆股事件識別碼 | FIGI、拆股生效日、前後比例及觀察時間 |
| `stock_industry` | 產業代碼 | 產業名稱及 Futu 板塊碼；只作字典 |
| `stock_industry_history` | FIGI＋觀察起點 | 股票產業分類的有效區間 |
| `stock_concept` | 概念代碼 | 概念名稱及 Futu 板塊碼 |
| `stock_concept_mapping` | FIGI＋概念代碼 | 股票目前所屬的多個概念 |
| `industry_valuation_config` | 產業代碼＋生效時間 | 人工確認的模型、主要指標及可選的倍數假設 |
| `stock_valuation_daily` | FIGI＋估值交易日 | 每日一筆，含收盤價、來源取得時間及逐項缺值原因 |
| `stock_share_observation` | FIGI＋取得時間 | 富途總股數觀察，供取得後的交易日按當時已知資料計算市值 |
| `financial_report` | FIGI＋報表類型＋期末日＋期間類型 | Futu 原幣財報及各報表的原生欄位 |
| `financial_report_item_mapping` | 報表類型＋原樣項目名稱 | 共用來源概念及 MAPPED／SKIPPED／PENDING 審核狀態 |
| `stock_earnings_forecast` | FIGI＋財務期間標籤＋期間類型＋觀察日 | Futu EPS／營收預測 |
| `treasury_par_yield_daily` | 利率觀察日 | 美國財政部利率資料 |
| `market_sentiment_daily` | 市場日期 | 情緒指標、VIX 與各自的觀察時間 |
| `market_event` | 事件識別鍵 | 個股／宏觀事件、事件日期、已知時點及來源 |

`job_config`、Quartz 表、`menu`、`search_criteria_config`、`upload_job`、業務碼序列表等是排程／畫面／匯入基礎設施，不計入這 17 張業務表。


### 股票主檔與 CIK 來源

- Massive 繼續提供證券主檔及 composite FIGI；`stock.cik` 只由 SEC 官方 `company_tickers.json` 的 ticker 對照唯一解析，每輪主檔更新只下載一次，不逐股查詢。CIK 統一為十位數字；多個股類可以共用同一發行人 CIK。
- ticker 去除首尾空白並忽略大小寫；點號與連字號只在整份對照中唯一指向同一 CIK 時視為等價。任何正規化碰撞涉及不同 CIK 時均視為未知，不依名稱猜測，也不回退使用 Massive 的 CIK。
- 成功取得並驗證整份來源後，找不到或有歧義的股票明確寫入 NULL，清除舊的未確認 CIK。HTTP 非 200、空回應、JSON／必要欄位錯誤等會在主檔交易前中止，不清除既有 CIK，也不因該輪失敗停用股票。完整傳輸與結構驗證不能證明 SEC 清單在業務語意上沒有遺漏。
- 這是目前 ticker 對應目前發行人的識別，不是歷史發行人沿革。單一 `stock.cik` 不能表達同一證券歷史上的發行人承繼，不將歷史申報重新標記為另一發行人，也不據此推定歷史財報連續性。

## 3. 富途財報

- `FINANCIAL_REPORT_DAILY_SYNC` 透過 OpenD 同步富途支援的有效 CS／OS／ADRC 美股，抓取利潤表、資產負債表、現金流量表及關鍵指標的季報和年報。 [富途財務報表 API](https://openapi.futunn.com/futu-api-doc/hk/quote/get-financials-statements.html)
- `stock_status.financial_report_date` 保存已成功入庫的最新財報期末日。NULL 時從 2014-01-01 回補，有值時從水位當日同步到最新一期。富途由新至舊分頁，程式翻到起點為止，同一期資料可更新，第四季和年報各自保存。
- 每股四表下載完成後，在同一交易更新財報及水位。同一報表內容有變時，按原主鍵刪除再插入，沿用原有的首次取得時間 `observed_at`。內容相同時保留原列。已回傳且已入庫的舊報期也檢查修訂。
- `items` 是轉換後的富途欄位，不是逐字原始回應。來源資產負債表 ID 8092／9077 缺名稱時，依已核實的歷史結構及 JPM／LII／SSNC 同期申報補為 `Less: Treasury Stock`；已有來源名稱不覆寫，其他缺名及所有數值／NULL 保留。`Other Composite Income` 為來源的累計其他綜合損益名稱，已包含於權益合計，不在 PB 重複加減。

財報項目語意對照保存在 `financial_report_item_mapping`，主鍵為報表類型與 `items.name` 原樣名稱，保留大小寫及前置連字號。`MAPPED` 表示已核實來源財務概念；`SKIPPED` 表示已確認不作目前 PE／PB／PS／P/FCF／EV/EBITDA 的原始輸入；`PENDING` 表示待審核，不可當成跳過。映射不代表期間、幣別、股數、公開時間或公式條件已驗證，映射不改寫既有 JSON。

映射表由 JPA 的 `FinancialReportItemMapping` 建立。富途估值依賴表內已審核的映射資料。新資料庫須另行匯入已審核資料，來源新增名稱按審核流程管理。

本輪五種市場倍數取報告合計及明確的原料概念。已含於淨利、權益或 CFO 的明細不重複加減；`SKIPPED` 的用途限定在這些公式，不代表該項沒有其他分析價值。損益歸屬、營運/非營運利息、折耗與純折舊攤銷、PPE 淨買賣與 capex、比率與金額分開。EBITDA 的淨利及調整範圍參照 [SEC C&DI 103.01/103.02](https://www.sec.gov/rules-regulations/staff-guidance/corporation-finance-interpretations/non-gaap-financial-measures)，現金流分類參照 [IAS 7](https://www.ifrs.org/issued-standards/list-of-standards/ias-7-statement-of-cash-flows/)；映射不會自行啟用加回、金額反推或估值公式。

已核實的權益法收益、融資費用、應計費用、準備金、員工福利、應付款、其他負債及投資保留各自來源概念；來源概念已知，不等於已決定 EV 採用，也不應只因公式政策未定就標成 `PENDING`。不得把這些義務一律當借款，不將收益與已包含它的淨利再加總，亦不將融資費用合計一律當成純利息。MET 2025/FY 的其他負債已識別為來源剩餘負債，但其中按揭貸款融資的分拆及借款涵蓋仍未確認，`MAPPED` 不代表此缺口已解決。父層負債合計的 `SKIPPED` 僅表示不重複加總，不構成債務完整性聲明。其他資產合計不直接取代現金，貸款投資現金流不當作 P/FCF 的 capex；必要輸入缺值不能補零。

來源名稱可能有實際標籤差異：已核實的 `Securities Lent` 樣本為證券借入交易資產，`Trading Liabilities` 為非衍生交易工具負債。`Derivative liabilities` 同一模板在 GS／MS 對上證券借出負債，在 WNEB 則對上待結算證券負債，故保留 `PENDING`，不按字面或單一樣本套用。衍生工具概念按來源認列口徑保存，GS 抵銷後金額與 VOYA 未抵銷金額不視為共同淨額基準。代表樣本按財務年度及報期核對，不代表所有股票或歷史版本均已驗證；本地期末日偏一天的問題未由映射修正。

`PENDING` 的影響限於依賴該未解項目的計算。例如 EV 的融資成分不足，不應因此停止不依賴該項目的 PE、PB、PS 或 P/FCF；各指標仍須分別滿足自身輸入條件。既有 `PENDING` 名稱與全新 `(statement_type, item_name)` 分開：後者保留警告，僅在所需映射輸入不足時影響該指標，不阻擋已具備輸入的其他指標；前者按實際依賴判斷。排程按股票彙整本輪所選四類報表的新名稱，已有 PENDING 不當成全新名稱。

`FinancialReportValuationCalculator` 提供映射取數、連續單季 TTM 與五種市場倍數；同概念別名不加總，有相異值時保留衝突。最新財期同日有年報時優先直接申報年度值，其他財期用四個連續單季，不能回退較舊年度。TTM 稀釋 EPS 採四季相加的市場慣例；各季加權股數與稀釋性可能不同，這不是重新計算的 GAAP TTM EPS，差異不只四捨五入，亦與富途原生的歸母淨利／總股數 PE 口徑分開。[TTM EPS 定義](https://www.gurufocus.com/term/pettm)、[富途倍數定義](https://support.futunn.com/topic497)

市場倍數本身不產生合理價或買賣訊號。`/analysis/report` 同時返回本地財報倍數及技術交易計畫；目前尚未讀取 `industry_valuation_config` 產生基本面合理價格區間。該功能仍需生效且已確認的產業倍數假設，以及同口徑的每股財務原料；沒有可信輸入時不填入區間。現有 `PriceStructureService`、`SupportResistanceService` 及 `TradePlanService` 使用行情結構建立支撐／壓力、進場區間、止損及阻力止盈目標，與基本面合理價分開。財報倍數缺失不停止獨立合格的技術計畫；沒有有效阻力則不虛構止盈或可執行訊號，也不以較低倍數替代技術支撐或直接宣稱應買入。

## 4. 財報估值排程

`StockValuationDailyTask.execute()` 選取有效 CS／OS。日常執行只處理估值水位之後尚未保存結果的交易日，算到最近已入庫且已收市的行情日。水位為 NULL 時從 `valuation.history-start-date`（預設 2016-01-01）起回補，並受上市日及行情起點限制。`valuationDate` 可指定已收市交易日重算，`figis` 可限定股票。每批 100 股鎖定既有狀態，批次讀行情、每股每類最多八份候選財報、拆股、已知股數及當日結果。

**已確認的設計：按財季配對，以最新資料重算歷史估值。** 股價交易日決定目標財年／季度，財報採該報期於本輪計算／重算時已公開且已取得的最新資料及修訂；目標季度尚無財報時，才暫用最近可用的較早報期。後續公布財報後可重算原交易日，不限於原報期的正式修訂；Futu 與 SEC 均適用。目標季度的實際期末可以晚於季度內的股價日，因此不能單以 `period_end <= valuation_date` 排除。財報分母依選定報期計算，TTM 結束於該財報期末；股價仍使用原交易日收盤價。首次取得時間 `observed_at`、申報日期及來源可用日期保留真實紀錄，不為重算倒填。

以下月份示例以曆年制公司為例；非曆年制公司沿用已核實的實際財年、季度及起訖日期配對，不把所有公司的 Q1 固定成 1 至 3 月。

| 情境 | 預期行為 |
|---|---|
| 重算 2024 年歷史股價，對應季度財報已齊備 | 1 至 3 月用 2024 Q1、4 至 6 月用 Q2、7 至 9 月用 Q3、10 至 12 月用 Q4，各取本輪已取得的最新版本；不套用 2026 年財報。 |
| 2026 年 11 月 12 日收市後，Q4 財報尚未公布 | 暫用最近可取得的 Q2 或 Q3 財報，使用 11 月 12 日收盤價。 |
| 11 月 13 日取得更新的適用財報後，使用者將 `stock_status.valuation_date` 設為 NULL 重建 | 11 月 12 日的估值改用該新財報；若 Q4 尚未有資料而新增的是 Q3，則更新為 Q3。不能因公開／取得時間晚於 11 月 12 日收市而排除。 |
| 之後 Q4 財報到齊，再重算 11 月 12 日 | 改用其所屬 Q4 的財務資料，股價仍為 11 月 12 日收盤價；Q4 單季與同日全年保持不同口徑。 |

報期尚未可用與個別指標缺項分開處理：已選定報期但 EPS 或其他必要項目缺失時，仍依既有規則保留該指標缺值，不為取得數值而退回較舊分母。

這是歷史價格搭配所屬報期最新財務資料的估值，不能宣稱結果只含歷史收市當時已知資訊。使用者可按既有重建入口更新結果；這項要求不自動授權 AI 修改水位或新增定時清空水位的排程。

查詢以本輪取得時間篩選資料，先取股價日後 100 日以內的候選期末，再依實際單季起訖選取包含股價日的季度；100 日只是限制取數量，不是報期配對容差。SEC 的原申報當期期末保留 `report_fiscal_year`／`report_fiscal_period`，比較期不沿用該申報的財年標記。Futu 以同財年／季度對齊 SEC 實際期末；例如 EXEL FY2026 Q3 的 Futu 9/30 與 SEC 10/2 可配對。不能證明同一財季時不以日期接近猜測。全年及前三季累計可用來確認 Q4 的起訖，但不以全年 EPS 減九個月 EPS 推算 Q4。

既有資料庫升級須受控新增上述兩個可空欄位，移除舊 `ck_valuation_sec_availability` 約束並同步欄位註解；Entity 為 schema 定義來源。舊 SEC facts 需經正式匯入補上財季資料，正常同步會更新其實際讀取範圍內的標記，完整歷史回補仍使用既有重建流程，不自動清空全市場水位。來源缺少標準 EPS 或四個可用單季時仍保留缺值原因。SEC 原始 ZIP／文件只讀，企業自訂 taxonomy 不作補值來源。

估值交易日以正式行情的未復權收盤價決定。最新已收市日可使用計算前取得的富途股數，收市後取得、最後成交時間落在同日正常交易時段且價格與收盤價相同的快照優先使用來源總市值。其他股數依拆股覆蓋轉換，歷史重算只用當日收市前已取得或原估值已採用的股數。股數觀察保存實際取得時間。

每日以 FIGI＋交易日更新一筆估值，股票分析與篩選共用同一結果。原雙基準資料須先受控執行估值 schema 遷移。重複日保留原有倍數皆涵蓋的收市估算，否則保留原收市列。

損益表、資產負債表及現金流量表各自依上述計算／重算時間規則選取最新適用報期。SEC 定期申報有可確認報期的標準欄位，即使缺少個別分母也能確定當期；重算時該指標撤下舊報期的倍數，保存新報期和缺項原因。同報期優先採 Futu、缺項補同幣別 SEC。Futu／SEC 先逐份對齊期末及年度／單季口徑，再形成 TTM；同一日期的 Q4 與全年不得互補，2024 財報不得拼接 2026 組成項。來源標記為 `FUTU`、`SEC` 或 `FUTU_AND_SEC`。收入及稀釋 EPS 可逐期補缺，保留同期可用的 Futu 項目。SEC EV 可採同期期末的流動債務與非流動債務及融資租賃合計，已含的短期借款及融資租賃不再相加。FCF 缺值可由 Futu／SEC 的 CFO 與資本支出合成；SEC 資本支出優先採 productive-assets 總額，否則採 PP&E 現金支出加另行披露的軟件支出，不限定公司或 accession。SEC EV 同樣排除營運租賃、只扣現金，不要求無關的投資明細。

Futu 財報 timestamp 實測有以香港零時編碼日期、但日期字串按美東顯示為前一天的情況。匯入在這種編碼下取原財報日期，其餘回應沿用來源日期。同一財報的日期編碼修正沿用原首次取得時間。開休市、交易日區間及歷史截止時間統一由 `MarketStatusService` 提供。
五種倍數按各自輸入獨立計算：PE＝收盤價／稀釋 EPS TTM，共用既有非正 EPS 留 NULL 及四位小數政策，非正 EPS 顯示虧損或零盈利、不適用（N.M.）；PS 使用 `REVENUE_AS_REPORTED` TTM，與 Operating Revenue 分開；P/FCF 使用來源 `FREE_CASH_FLOW` TTM，保留來源口徑，不將 PPE 淨買賣當 capex；FCF 非正時原始值保留，倍數為 NULL，狀態 `NOT_APPLICABLE`、原因 `NON_POSITIVE_FCF`（N.M.），重跑撤銷舊負倍數，不影響其他指標。SSNC 2025/FY 的 FCF 1,442.1m USD 已扣設備及軟件；8044 是軟件資本化、8046 是 PPE 淨額，不會再扣一次，也不以此樣本推定所有公司口徑。PB 由 Stockholders Equity 扣除明示優先股帳面資本後計算；未單列優先股時，只有普通股股本、留存盈餘及其他綜合權益三項齊備，且加總與母公司權益完全相符，才採用該權益。已列出但為 NULL／衝突仍保留不足，不將缺項補零。EV 需同幣別市值、債務含租賃、現金及可確認的優先股／非控制權益；非控權益採來源帳面值估算，與精確市值不同（[TradingView EV 公式](https://www.tradingview.com/support/solutions/43000597077-enterprise-value/)）；非零優先股仍須可確認的資本價值，不能將未知當零。EV 目前僅扣獨立現金及約當現金；可扣減投資的流動性、用途及限制未識別，不能盲扣投資或混合現金合計，亦不宣稱已涵蓋全部合格可交易證券。依既定 US GAAP 排除營運租賃口徑，只有報表明示 `US_GAAP` 才評估 EV／EBITDA；會計準則缺失或其他準則只令該倍數未核實。富途 Capital Lease 標籤已在 MYRG／LII 2025/FY 對上融資加營運租賃，不能將整額當融資債務，也不能只取 Debt 漏計融資租賃。缺少拆分時，使用同期 SEC 營運租賃從 Futu 債務租賃合計扣除，或將 SEC 融資租賃加到獨立 Debt；兩類租賃明示為零時直接採債務合計。仍無可用拆分才保留 `EV_LEASE_SCOPE_UNRESOLVED`，不重複計入租賃或補零；已知其他負債或金融融資範圍未解則 EV 留空，不阻擋其他倍數。PENDING 的 `Convertible preferred securities` 已有權益外可贖回 NCI 樣本，非零或未知金額時 EV 留空，明示零則不因此阻擋；不直接將其帳面金額當市值。

EBITDA 統一採淨利＋所得稅＋非營業利息費用＋折舊攤銷，年度或 TTM 口徑須一致；缺少的組成可取同期 SEC。SEC 折舊攤銷合計若另披露耗損，先扣除同期間耗損，不以營業利潤或 EBITDA margin 代替整個公式。非正 EBITDA 標示 `NOT_APPLICABLE`／`NON_POSITIVE_EBITDA`。缺少任一可信輸入只留該倍數 NULL 及原因。財報／行情幣別須相同，不自動匯兌。EPS 期間到估值日有已知拆股或相關同步失敗時，不猜 EPS 已否重新標準化；股數可按已知拆股轉換，仍須可信的同步覆蓋。全新項目名稱記錄警告，只令無法識別必要輸入的相關指標留空，既有 PENDING 按指標實際依賴判斷。市場倍數不是合理價，銀行等產業仍應依已確認的產業模型挑選適用指標。

估值結果與狀態在同一批交易保存，寫入失敗整批回滾。`stock_status.valuation_date` 是最近已保存至少一項可用倍數的交易日。缺值結果按原交易日保存，舊紀錄由使用者指定 `valuationDate` 重算，或將個股水位設為 NULL 重建。撤銷最新日全部指標時，以設定範圍內尚有可用倍數的最大日期作水位。手動處理設定起點以前的資料不改這個水位。

輸入暫缺時保留仍可信的舊整列，不混拼不同計算批次；已確認新價格／新有效值／修訂內容則重新計算。舊 EPS 及股數基準各自重驗已知拆股錯誤，確認失效的 PE 或市值倍數會撤銷；已確認 EV 融資或優先股資格失效亦不保留。股數下載故障不妨礙使用既存可信輸入，但本輪最後會明確報部分失敗。

`stock_status.valuation_message` 保留按交易日最新的估值摘要，較新交易日即使全缺值也會更新摘要。每個歷史交易日的倍數及原因存於 `stock_valuation_daily`，重算舊日可更新該日明細。Job 開始時估值水位為 NULL 的股票，本輪處理到的同 FIGI、同交易日舊列會在同一交易刪除重插，只保存本輪計算的倍數與原因。重建範圍由日常回補起點或手動指定日期決定。

DEV 新庫由 Hibernate `ddl-auto=update` 依 JPA 映射建立估值與股數觀察表。仍使用 `observation_date` 或 `valuation_basis` 的舊庫須先核對歷史資料及遷移主鍵，再啟動新版。UAT／PROD 的 schema 設定為 `none`。

篩選及報告讀本地計算結果，各倍數獨立標記可用、缺值、待核實或不適用。有至少一項有效倍數時整體可用。普通股帳面權益小於或等於零時，P/B 為不適用。銀行與承保公司不使用 P/FCF 和 EV/EBITDA。富途原生個股歷史 API 仍獨立回傳 `source=FUTU` 的 P/E、P/B、P/S，不混拼。Futu earnings history 雖有公告時間，但沒有對應財報內容版本；Massive Details 的 SEC 日期依報告期末比較，不能直接補成歷史可知股數。[富途歷史倍數](https://openapi.futunn.com/futu-api-doc/en/quote/get-valuation-detail.html)、[富途公告歷史](https://openapi.futunn.com/futu-api-doc/en/quote/get-financials-earnings-price-history.html)、[Massive 日期契約](https://massive.com/docs/rest/stocks/tickers/ticker-overview)
## 5. API 契約

路徑均為應用程式的相對路徑；JSON 欄位名稱使用 camelCase。

| 方法／路徑 | 用途 |
| --- | --- |
| `GET /stock/screen` | 全市場股票篩選；參數 `symbol/industry/conceptCode/page/size` |
| `POST /stock/classification` | 產業與概念字典 |
| `POST /analysis/report` | 指定 FIGI 的分析報告 |
| `GET /valuation/{figi}/history` | 富途 P/E、P/B、P/S 歷史。參數 `from`、`through`、`frequency` |
| `GET /industry-valuation/{code}` | 正式產業設定、支援模型及是否可取得 AI 建議 |
| `POST /industry-valuation/{code}/proposals` | 同步產生待確認草稿，不持久化 |
| `PUT /industry-valuation/{code}/config` | 人工確認並保存正式設定 |
| `POST /overview/dashboard` | 市場儀表板 |
| `POST /job/enquiry`、`/job/update`、`/job/launch` | 查詢／調整／手動啟動排程 |
| `GET /upload/types`、`POST /upload`、`GET /upload/{id}` | 可用匯入種類、提交檔案及查詢結果 |
| `POST /menu/enquiry` | 畫面選單 |
| `POST /searchCriteria/enquiry`、`/searchCriteria/update` | 查詢條件偏好 |

### 5.1 篩選與報告

`/stock/screen` 回傳 `{items,total,page,size}`，預設 page=0、size=50，上限 200。有效普通股（CS／OS）可按 symbol、名稱、產業及概念篩選。行情日期為 `quoteDate`，本地倍數的價格交易日為 `valuationDate`。沒有倍數時保留 NULL 和原因。

最新報告請求不指定日期：

```json
{"figi":"實際存在的FIGI"}
```

歷史報告指定日期，可選 `horizon` 為 `SWING` 或 `POSITION`：

```json
{"figi":"實際存在的FIGI","valuationDate":"2026-11-04","horizon":"POSITION"}
```

- 預設報告採最新已結束交易日的技術分析及符合該交易日的本地估值。財報倍數讀取已保存的估值結果，重算時間規則見第 4 節；預測在歷史報告限定當日收市，預設報告則可讀目前已取得的預測。
- `valuation` 提供 `source=FINANCIAL_REPORT_AS_OBSERVED`、估值交易日、收市截止時間、當時已知產業及 `metrics:{pe,pb,ps,pfcf,evEbitda}`。`history` 是已存本地估值的月頻摘要；富途預測獨立保留在 `futuObservations`，不把本地計算值放進原生觀察欄位。

### 5.2 個股估值歷史

- `GET /valuation/{figi}/history?from=2025-01-01&through=2025-12-31&frequency=MONTH_END`。`DAILY` 回傳區間內的實際資料點，`MONTH_END` 回傳每月最後一個實際資料點。個別倍數缺值時仍可取得同日其他倍數。
- 富途連線或查詢失敗時回傳 HTTP 502。

### 5.3 手動產業設定與 AI 草稿

`GET /industry-valuation/{code}` 回傳：

- `industryCode/industry/current/supportedMetrics/canPropose`。
- `current` 可為 NULL，或含 effectiveAt、modelCode、primaryMetric、assumedMultipleLow／High、rationale、sourceUrl。
- 前端使用後端 supportedMetrics，不自行硬編尚未實作的模型選項。目前一般企業支援 PE／PS／PFCF／EV_EBITDA；銀行及保險支援 PE／PB，沒有假造 REIT FFO 支援。

可以先只設定模型與主要指標，不需要捏造倍數：

```json
{
  "baseEffectiveAt":null,
  "modelCode":"NON_FINANCIAL_OPERATING",
  "primaryMetric":"PE",
  "assumedMultipleLow":null,
  "assumedMultipleHigh":null,
  "rationale":null,
  "sourceUrl":null
}
```

這是 `PUT /industry-valuation/{code}/config` 的首次設定例子；已有設定時 baseEffectiveAt 必須傳目前 effectiveAt，過期則回 409，避免覆寫別人較新的設定。

若提供倍數，兩端必須一起提供、為正數且下限不高於上限，並填寫 rationale 與有效 HTTP(S) sourceUrl。正式設定以目前時間生效，適用整個產業，不回填成歷史當時已知，也不自動替整個市場重算。

AI 建議須先有可用模型／指標及 API 設定，再由使用者主動 POST proposals。請求同步回傳 `{modelCode,primaryMetric,assumedMultipleLow,assumedMultipleHigh,asOf,rationale,sources}`；沒有草稿 ID、持久化草稿、背景輪詢或自動批准。使用者確認後才 PUT config。

## 6. 股票、分類與 CSV 匯入

- `StockDailyTask` 完整取得 Massive 主檔與 SEC ticker 對照並驗證後批次更新；不逐股呼叫 Details。主檔缺席股票的 inactive 判斷必須等兩個來源均成功取得及驗證。
- `StockQuoteDailyTask` 按 `stock_status.quote_date` 增量下載 Alpaca 日線至最新已結束交易日；NULL 由 `market-history.start-date`（未設定則 `history.start-date`）開始，非 NULL 由下一日開始且不早於此起點。多年歷史以每批 10 隻股票回補，只有確實入庫的行情才推進 quote_date，無成交資料不偽造 K 線。
- 行情與拆股涵蓋 ACTIVE CS／OS／ADRC／ETF，不要求 CIK；ADR／ETF 不因此套用普通股估值。個股無效行情或 symbol 歧義只記錄受影響股票的 quote_message 並保留水位，其他股票繼續；來源服務故障仍中止整輪。
- 產業一個、概念可多個；產業字典不混入估值設定。分類歷史記錄實際觀察區間，不能把今天觀察的分類倒填成多年前已知。
- 通用上傳入口 GET types 取得可用種類，再以 multipart 的 id、type、file 提交 POST upload（HTTP 202），由 GET upload/{id} 查結果。
- 股票產業匯入接受 UTF-8 CSV（可帶 BOM），上限 10 MB，必要欄位為 Symbol、Name、Industry；其他欄位不影響處理。
- 先驗證整份檔案，再以交易套用。重複 Symbol／CSV 格式錯誤使整份失敗；空白、`-`、`--` 產業不寫入分類。
- 匯入只配對既有且唯一的股票 symbol，不建立股票、不用 CSV Name 覆蓋主檔名稱。產業名稱去除首尾空白並忽略大小寫配對；同分類不重複新增歷史。
- `upload_job` 是使用者匯入工作的持久化狀態，保留統計及有限的錯誤列表。

## 7. 排程與設定

### 拆股同步

`STOCK_SPLIT_DAILY` 每日逐股讀取 Futu `get_rehab`（Java `requestRehab`），以目前主檔 symbol 查詢，保存 `split.history-start-date`（預設 2014-01-01，涵蓋 2016 首批估值使用的較早財報期末）至最新已收市交易日的股份調整。主流程直接放在 `execute()`：範圍、選股、連線、逐股下載、轉換、提交及錯誤處理。[Futu API](https://openapi.futunn.com/futu-api-doc/en/quote/get-rehab.html)

- 拆合股讀取 `split_base/ert`、`join_base/ert`；送股與轉增按 `base : base + ert`。現金股息不轉成拆股，不使用價格復權因子。未確認基準的同日多種股份行動或重複日期會報錯，不猜測累乘。
- 使用 `FUTU:FIGI:生效日` 作為穩定識別碼；每隻股票下載成功後，在獨立交易內更新差異與 `stock_status`。同 FIGI 同日的舊來源事件由 Futu 單筆結果取代，避免重複計算。重跑不改寫相同事件；來源缺少已存事件則保留並標示 ERROR，不當作撤回。範圍外的舊資料不清除，但不聲稱仍有覆蓋。
- 無有效回應或轉換失敗時保留該股舊資料與日期，將原因寫入 `split_message`；其他股票繼續。斷線、逾時或請求回覆錯配則中止，不反覆使用失效連線。每輪每股一個請求，跨 session 以每秒 1.8 次節流，低於官方每 30 秒 60 次上限。
- 不再呼叫 Alpaca 公司行動或 Massive 歷史 Ticker Details，也不維護 `stock_symbol_history`；既有實體表不會自動刪除。當前 symbol 對應多個有效 FIGI 時報錯。內部仍用主檔 FIGI，歷史歸屬依賴 Futu 的證券序列，不能保證解決所有代號重用或公司重組。
- 日期表示本輪成功檢查範圍，不保證供應商完整性。實測 Futu 可取得 MAGN、MAXNQ 改名前事件及 MASK 合股；但 BAM 混入舊公司 2020 年事件、BINI 缺少 2023 年事件。拆股同步範圍包含這些已知樣本的期間，不能以水位宣稱來源身份或事件完整。主檔缺少 FIGI 的證券仍不會因此新增，舊代號不自動猜測為新代號。

Quartz 的 Cron 與手動任務共用 worker 池。Backend 啟動時從下一個 Cron 時段開始，停機期間錯過的排程不補跑。服務運行時，若 worker 全忙，到期的 Cron 仍會排隊。

預設排程在 Asia/Hong_Kong 時區；下表為初始化值，既有 `job_config` 不會每次啟動被覆蓋，實際設定以資料庫為準。

| taskCode | 預設時間（香港） | 主要資料 |
| --- | --- | --- |
| `TREASURY_DAILY_SYNC` | 每日 05:00 | 財政部利率 |
| `STOCK_DAILY` | 週二至六 05:15 | 股票主檔 |
| `STOCK_SPLIT_DAILY` | 每日 05:45 | 拆股 |
| `STOCK_QUOTE_DAILY` | 週二至六 06:15 | 按股票水位補齊至最新已收盤日的行情 |
| `FINANCIAL_REPORT_DAILY_SYNC` | 週二至六 06:30 | 富途實績財報 |
| `STOCK_CLASSIFICATION_DAILY_SYNC` | 週二至六 07:00 | 產業／概念 |
| `MARKET_SENTIMENT_DAILY` | 每日 08:30 | 市場情緒 |
| `MARKET_EVENT_DAILY_SYNC` | 每日 08:40 | 市場／個股事件 |
| `EARNINGS_FORECAST_DAILY_SYNC` | 每日 08:50 | EPS／營收預測 |
| `stock_valuation_daily` | FIGI＋估值交易日 | 每日一筆，含收盤價、來源取得時間及逐項缺值原因 |

| 設定 | 意義 |
| --- | --- |
| `history.start-date` | DEV 設為 `2019-01-01`，作為未另設起點的行情設定預設值 |
| `split.history-start-date` | 拆股獨立起點，預設 `2014-01-01`，不受 Massive 訂閱限制 |
| `valuation.history-start-date` | 估值獨立起點，預設 `2016-01-01`；水位為 NULL 時由此開始，不受 Futu 入庫日期截短 |
| `market-history.start-date` | DEV 設為 `2022-01-01`，用於行情回補，未設定沿用 `history.start-date`。不刪既有資料，不手動改水位；來源準備範圍不變 |
| `app.schedules.enabled=false` | 受控啟動時跳過排程註冊與啟動 |
| `spring.jpa.hibernate.ddl-auto` | DEV 為 update；UAT／PROD 為 none |

`spring.quartz.auto-startup=false` 只是交由應用程式初始化後啟動；不能單靠這個設定認定所有排程已停用。停用應用排程使用 app.schedules.enabled=false。

SEC User-Agent 必須包含有效應用識別與真實聯絡電郵，來源 API／Futu OpenD 連線設定依各環境配置；缺少來源或權限時應回報，不假設已可用。設定值不得以 README 範例取代實際帳戶配置。

## 8. 日期、時間與歷史可用性

- 交易日、財務期間、申報日期、事件日期使用 Java LocalDate／DB DATE；日期本身不因使用者時區轉換。
- acceptedAt、availableAt、observedAt、effectiveAt、analysisAsOf 等真實時間點使用 Java Instant／DB TIMESTAMP，統一以 UTC 寫入及解讀；API 用 ISO 8601 的 Z 時間。
- Hibernate JDBC time zone 設為 UTC，Instant JDBC 型別明確採 TIMESTAMP。資料庫欄位不帶時區時，也不得將本地牆鐘時間當成 UTC。
- Quartz cron 與其 startTime／endTime 依 Asia/Hong_Kong 解讀；這是排程設定，不是財務公開時間。
- 事件只有日期時保留 eventDate，occurrenceTime 為 NULL 並標示 timePrecision；不填中午或午夜冒充已知事件時間。
- 分析截止時間與補充資料的讀取時間分開；來源值、觀察日、公開日、財務期間不是同一概念。

## 9. 來源與資料遷移

SEC 的獨立來源任務為 `SEC_FINANCIAL_FACTS_DAILY_SYNC`／`SecFinancialFactsDailySyncJob`，每日香港 17:00（美東冬令 04:00／夏令 05:00）檢查 companyfacts ZIP。來源約美東 03:00 更新，不保證準時；以有效 User-Agent 及 ETag／Last-Modified 確認可重用版本。同一紐約日已有成功下載的有效 ZIP 時直接重用，新下載先寫 `.part` 並驗證再發布。全局版本及下載時間留在快取 metadata，不逐 facts 或股票列重複。沿用 `data/sec-shares` 快取路徑，舊 `data/companyfacts.zip`／`sec-bulk-metadata.json` 只在遠端版本相符時唯讀重用，不覆寫原 HardLink 證據。遠端檢查或 ZIP 失敗不假稱已同步。

DEV 新庫由 Hibernate 依 JPA 映射建立 `sec_financial_fact` 及 `stock_status.sec_financial_facts_date`。披露唯一約束使用 PostgreSQL 15 起支援的 `NULLS NOT DISTINCT`，同一存量項目即使沒有起日也只保存一次。仍使用 `sec_share_fact` 或 `sec_shares_date` 的資料庫須先遷移既有資料，舊股數水位只代表舊同步範圍。

入庫採用已核實來源定義的 DEI／US-GAAP tag，清單在 `SecFinancialFactConverter.US_GAAP_TAGS` 及 DEI 股數入口，涵蓋股數、基本／稀釋 EPS、收入、權益／優先股／NCI、CFO、設備與軟件支出、現金、債務／租賃、淨利、稅、利息及折舊攤銷原料。IFRS 對應及未核實 tag 不猜測納入；未提供的概念不補零。這是來源取數清單，不是估值公式別名：

- `NetIncomeLoss` 為歸母，`ProfitLoss` 含 NCI；不同收入及 CFO tag 也不視為同義。
- `PreferredStockValue` 為 par／stated value 且含庫藏股，不能當完整優先股帳面值或市值；NCI 及權益外可贖回權益另存。
- `LongTermDebtAndCapitalLeaseObligations` 為非流動部分，`…IncludingCurrentMaturities` 才含流動部分；`DebtCurrent` 已包含 capital lease。總額與明細、融資與營運租賃不盲加。
- `PaymentsToAcquireProductiveAssets` 已含設備、軟件與其他無形資產，不能再疊明細；DDA 包含 depletion，不當成純 D&A。

每列保留 CIK、taxonomy、tag、原始 unit、start／end、value、filed、accession、form 及首次取得時間。單位或季度／YTD 不混合；instant 的 start 保留 NULL，自然唯一約束亦將這個 NULL 當作相同期間，確保重跑不重複。負 EPS、CFO 等合法負值保留；負股數另警告並保留為來源異常，不能當可用股數，也不妨礙其他財務原料入庫。CIK 從精確官方 ZIP entry 識別；根 cik 若存在仍須一致，省略時不依公司名稱猜測。

`sec_financial_facts_date` 是該 CIK 已成功入庫的最新 SEC 財報期末日。封面股數觀察日與 ZIP Last-Modified 各有自己的用途。水位為 NULL 時回補 2014-01-01 起的財報期末，本輪同披露鍵的既有列在同一交易刪除重插。已有水位時從該期末開始，包含當天，新 accession 各自保存，更早的期末留待全歷史回補。完全一致的披露保留首次取得時間與仍適用的核對資料。舊 ZIP 水位晚於已存最新報期時，下次成功同步會從已存報期接續並轉換水位。沒有財報原料的新發行人保留空水位。單一 CIK 的資料與水位同交易保存，失敗時保留原水位。

沒有接受時刻證據時，`available_from_date` 為 filed 後下一交易日，僅表示通過披露時間門。companyfacts 為發行人層級，唯一 ACTIVE ticker 不能證明股類／ADR 範圍；後續申報可能已重列拆股比較數，亦可能有尺度或誤標錯誤。[SEC API 與 ZIP 契約](https://www.sec.gov/search-filings/edgar-application-programming-interfaces)、[SEC 股數品質提醒](https://www.sec.gov/newsroom/whats-new/osd-announcement-2210-dqreminder-entitycommonstocksharesoutstanding)

`StockValuationDailyTask` 批次讀取同 CIK、原幣且已通過披露日期門的 SEC facts，通用路徑補充 PS 及 P/FCF：總收入使用完整期間的 `Revenues`／`SalesRevenueNet`；合約收入和金融淨收入不自動當作總收入。FCF 使用同期間 CFO 減資本支出。同期有 CFO 總額時優先使用，較新的報告期只披露持續營運現金流時採該口徑。`PaymentsToAcquireProductiveAssets` 已含設備及軟件，不重加明細。TTM 可採完整年度、連續四季，或上年度＋本年累計－上年同期累計；不把九個月累計當一季，也不跨 tag 或幣別拼接。SEC 期間不得早於本輪富途對應報表，富途缺值或 SEC 期末較新時才採用；負 FCF 原值保留，倍數為 NULL／N.M.。

SEC 查詢按股票已識別的 CIK 執行，重算優先沿用該估值日記錄的 CIK；共用 CIK 的股票各自按 FIGI／股價保存結果。此對照沒有完整的發行人沿革，重組或 CIK 變更的歷史歸屬仍須核對，不能宣稱已完整支援所有歷史證券。財務配對使用 facts 的實際 start／end 與單季／全年口徑，不以 10-K、10-Q、8-K 等表單名稱直接連接 Futu。SEC 合約收入須與毛利加成本相符；普通股權益按可核對的權益組成扣除優先股帳面值。EV 與 EBITDA 沿用前述統一口徑，且資產負債表與 EBITDA 的期末須一致。當前接入為 companyfacts 的 us-gaap facts，並非所有 SEC 文件附件或 IFRS 財報解析器。

EV/EBITDA 的分母使用 TTM 營業利益加純折舊攤銷。同報期先採富途，缺項再採已入庫的 Company Facts。同一申報的標準分項可精確對平時直接使用。若仍無法確認 D&A 口徑，`STOCK_VALUATION_DAILY` 才讀 [SEC EDGAR Feed](https://www.sec.gov/search-filings/edgar-search-assistance/accessing-edgar-data) 的每日 `.nc.tar.gz`。從合併現金流量表核對純 D&A 列名及標準 US-GAAP tag，並逐一配對同 CIK、accession、form、實際起訖日、幣別及金額。現行程式仍按原估值日檢查發布時間，須與第 4 節的重算設計一併調整。

每批先以已入庫的富途及 SEC 資料計算，在交易外核對尚未確認的 D&A，再一次提交該批估值與水位。數 GB 的日包處理後只保存小型核對結果於 `data/sec-shares/edgar-feed`，同一輪各批共用。歷史股類、產業和已能由季度檔確認的 D&A 沿用現有批量資料，最新估值可在季度發布前由 Feed 核對。Feed 預設自 `2026-10-01` 開始自動下載，避免歷史重建時自動抓取多年日包。需要追溯更早申報時可設定 `sec.edgar-feed.automatic-from` 或提供對應日包到 `sec.edgar-feed.directory`。下載失敗時 Job 回報失敗日期，其餘可算的指標仍可保存。

歷史市值可使用同一 CIK 的 Company Facts 標準期末普通股數（CommonStockSharesOutstanding）。以主檔的單一普通股證券作發行人總股數的使用範圍；共用 CIK 或主檔明示股類須有股類專屬股數。日常 SEC Job 只讀 companyfacts ZIP 的 CIK JSON 入庫。歷史股數及折舊攤銷的既有確認資料繼續保留，重建同鍵時隨相同披露帶回。

### 按水位回補歷史

估值重算前先同步 SEC 財務 facts，可取得現行及歷史申報的庫藏股組成；新標準欄位沿用現有權益回補，不必重設 SEC 水位。普通股權益以同份申報的組成核對，庫藏股從股本、留存盈餘及其他綜合權益的合計扣除一次。已披露的純借款加融資租賃總額可直接計入 EV。多股類公司須先取得同一申報的各股類股數並對上公司總股數，再按各類收盤價加總計算公司級 EV；每股倍數仍按各指標的股類資料計算。

依序執行 `SEC_FINANCIAL_FACTS_DAILY_SYNC` 與 `STOCK_VALUATION_DAILY`。兩個 Job 各讀自己的 `stock_status` 水位。SEC 空水位回補 2014 年起的財報原料，包含 2016 年首個估值交易日可用的上一期股數。估值空水位從 2016 年首個適用交易日開始，依已入庫行情及逐日公開資料計算。重建只替換本輪處理的同鍵列。其他水位落後的股票仍依日常增量規則同步。

單一股類估值優先用富途股數或同收盤價快照。使用 SEC 時，同時查看財報期末及申報封面已公開的標準普通股數，按股數基準日選最新一筆。同一基準日優先用期末普通股數。保留申報編號及股數基準日，並按拆股轉成估值日的股份基準。股數基準日至申報日之間有拆股時，須先核實原股數的調整基準。多股類仍按實際證券判斷市值範圍，例如 [MBLY 2023 年報的 A、B 股](https://www.sec.gov/Archives/edgar/data/1910139/000110465924026792/0001104659-24-026792-index.htm)。

年度＋本年累計－上年同期累計只用於已核實可加減的 SEC 總額流量，不套用 EPS。PE 採同幣別稀釋 EPS：年度直接申報值優先，四季單季 EPS 加總是近似，受加權股數、稀釋性及四捨五入影響，不宣稱是重新計算的 GAAP TTM EPS；拆股基準不明仍保留缺值。[FASB 260-10-55-49–50 範例](https://storage.fasb.org/ASU%202015-01.pdf)

啟動新版時，會清除既有的 `SEC_FINANCIAL_FACT_DAILY` 和已合併的 `FUTU_VALUATION_DAILY_SYNC` 排程。富途同步使用 `STOCK_VALUATION_DAILY` 的現有設定。`sec_filing` 從 ORM 移除，既有資料庫的實體表須在指定環境另行刪除。

DEV 的 Hibernate update 不會自動安全地刪除全部舊表或修正所有舊資料。破壞性重建必須在已確認的環境另行執行，先處理備份與目標確認；啟動程式本身不是「清空重來」命令。UAT／PROD 不由應用程式自動改 schema。

已有財報及分類資料的環境，先停止舊版 backend，再於啟動新版前執行以下 PostgreSQL 改名。這會保留原有資料、水位及約束。首次建庫直接使用新名稱。分類與盈餘預測的舊排程代碼由新版啟動時轉換，沿用原排程設定。

```sql
BEGIN;
ALTER TABLE public.futu_financial_report RENAME TO financial_report;
ALTER TABLE public.financial_report RENAME CONSTRAINT futu_financial_report_pkey TO financial_report_pkey;
ALTER TABLE public.financial_report RENAME CONSTRAINT fk_futu_financial_report_stock TO fk_financial_report_stock;
ALTER TABLE public.stock_status RENAME COLUMN futu_classification_synced_symbol TO classification_synced_symbol;
ALTER TABLE public.stock_industry RENAME COLUMN futu_plate_code TO source_plate_code;
ALTER TABLE public.stock_industry RENAME CONSTRAINT ukpa84axy629bws7lui5gq8pkrq TO uk_stock_industry_source_plate;
ALTER TABLE public.stock_concept RENAME COLUMN futu_plate_code TO source_plate_code;
ALTER TABLE public.stock_concept RENAME CONSTRAINT ukajrqb59hyu50rau0rxdo40il1 TO uk_stock_concept_source_plate;
COMMIT;
```

當值為 NULL 或狀態不足時，先檢查具體 reasonCode、財務期間／幣別／股類及來源覆蓋範圍，而不是補 0、套用其他期間數字或自動產生假設來讓畫面看起來完整。
