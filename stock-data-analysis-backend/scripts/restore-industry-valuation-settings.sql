-- PostgreSQL：恢復 2026-11-04 已核對的產業估值設定（145 個產業）。
-- 手動設定只有 valuation_model_code、primary_valuation_metric；本檔不設定數字倍數。
-- 122 個產業模型已指定；109 個主要指標已指定；其餘保留 UNKNOWN／NULL。
--
-- 使用順序：
-- 1. 由 Entity／正式同步流程建立股票及產業字典，執行 FUTU_CLASSIFICATION_DAILY_SYNC。
-- 2. 在已確認的目標資料庫完整執行本檔；空表須先同步，UPDATE 不會新增產業。
-- 3. 由修正後程式執行 STOCK_VALUATION_SNAPSHOT_DAILY 重算估值，再查 Analysis report。
--
-- 以 futu_plate_code 配對並核對產業名稱；重建後 I-code 可能改變，故不以舊 I-code 更新。
-- 任何來源碼缺失或名稱改變都會令本次交易失敗，須先核對來源，不猜測替代產業。
-- 本檔是明確的資料設定快照：重跑會將這 145 個產業的兩欄恢復成以下值，
-- 包含 UNKNOWN／NULL；後來新增、不在清單內的產業保持原樣。
-- 不更新 code、industry、futu_plate_code、stock.industry_code、fair_* 或 market_*。
-- 這不是自動啟動 seed 或 schema migration；變更業務設定後應同步維護本快照。

BEGIN;

DO $restore_industry_valuation$
DECLARE
    saved record;
    actual_industry text;
    matched_count integer := 0;
    changed_count integer := 0;
    affected_count integer;
BEGIN
    -- 與正式產業同步／CSV 匯入使用相同交易鎖。
    PERFORM pg_advisory_xact_lock(7310914269032101);

    FOR saved IN
        SELECT * FROM (VALUES
            ('US.LIST2030', 'Advertising Agencies', 'NON_FINANCIAL_OPERATING', 'PE'),
            ('US.LIST2089', 'Aerospace & Defense', 'NON_FINANCIAL_OPERATING', 'PE'),
            ('US.LIST2010', 'Agricultural Inputs', 'NON_FINANCIAL_OPERATING', 'EV_EBITDA'),
            ('US.LIST2090', 'Airlines', 'NON_FINANCIAL_OPERATING', 'EV_EBITDA'),
            ('US.LIST2234', 'Airports & Air Services', 'NON_FINANCIAL_OPERATING', 'EV_EBITDA'),
            ('US.LIST2211', 'Aluminum', 'NON_FINANCIAL_OPERATING', 'EV_EBITDA'),
            ('US.LIST2049', 'Apparel Manufacturing', 'NON_FINANCIAL_OPERATING', 'PE'),
            ('US.LIST2494', 'Apparel Retail', 'NON_FINANCIAL_OPERATING', 'PE'),
            ('US.LIST2249', 'Asset Management', 'UNKNOWN', NULL),
            ('US.LIST2256', 'Auto & Truck Dealerships', 'NON_FINANCIAL_OPERATING', 'PE'),
            ('US.LIST2468', 'Auto Manufacturers', 'NON_FINANCIAL_OPERATING', 'PE'),
            ('US.LIST2055', 'Auto Parts', 'NON_FINANCIAL_OPERATING', 'PE'),
            ('US.LIST2481', 'Banks - Diversified', 'BANK', 'PB'),
            ('US.LIST2456', 'Banks - Regional', 'UNKNOWN', NULL),
            ('US.LIST2427', 'Beverages - Brewers', 'NON_FINANCIAL_OPERATING', 'PE'),
            ('US.LIST2459', 'Beverages - Non-Alcoholic', 'NON_FINANCIAL_OPERATING', 'PE'),
            ('US.LIST2464', 'Beverages - Wineries & Distilleries', 'NON_FINANCIAL_OPERATING', 'PE'),
            ('US.LIST2069', 'Biotechnology', 'UNKNOWN', NULL),
            ('US.LIST2497', 'Broadcasting', 'NON_FINANCIAL_OPERATING', 'EV_EBITDA'),
            ('US.LIST2034', 'Building Materials', 'NON_FINANCIAL_OPERATING', 'EV_EBITDA'),
            ('US.LIST2483', 'Building Products & Equipment', 'NON_FINANCIAL_OPERATING', 'PE'),
            ('US.LIST2509', 'Business Equipment & Supplies', 'NON_FINANCIAL_OPERATING', 'EV_EBITDA'),
            ('US.LIST2260', 'Capital Markets', 'UNKNOWN', NULL),
            ('US.LIST2020', 'Chemicals', 'NON_FINANCIAL_OPERATING', 'EV_EBITDA'),
            ('US.LIST2505', 'Coking Coal', 'NON_FINANCIAL_OPERATING', 'EV_EBITDA'),
            ('US.LIST2098', 'Communication Equipment', 'NON_FINANCIAL_OPERATING', 'PE'),
            ('US.LIST2492', 'Computer Hardware', 'NON_FINANCIAL_OPERATING', 'PE'),
            ('US.LIST2473', 'Confectioners', 'NON_FINANCIAL_OPERATING', 'PE'),
            ('US.LIST2216', 'Conglomerates', 'NON_FINANCIAL_OPERATING', NULL),
            ('US.LIST2476', 'Consulting Services', 'NON_FINANCIAL_OPERATING', 'PE'),
            ('US.LIST2075', 'Consumer Electronics', 'NON_FINANCIAL_OPERATING', 'PE'),
            ('US.LIST2510', 'Copper', 'NON_FINANCIAL_OPERATING', 'EV_EBITDA'),
            ('US.LIST2261', 'Credit Services', 'UNKNOWN', NULL),
            ('US.LIST2080', 'Department Stores', 'NON_FINANCIAL_OPERATING', 'PE'),
            ('US.LIST2011', 'Diagnostics & Research', 'NON_FINANCIAL_OPERATING', 'PE'),
            ('US.LIST2240', 'Discount Stores', 'NON_FINANCIAL_OPERATING', 'PE'),
            ('US.LIST2428', 'Drug Manufacturers - General', 'NON_FINANCIAL_OPERATING', 'PE'),
            ('US.LIST2513', 'Drug Manufacturers - Specialty & Generic', 'NON_FINANCIAL_OPERATING', 'PE'),
            ('US.LIST2044', 'Education & Training Services', 'UNKNOWN', NULL),
            ('US.LIST2493', 'Electrical Equipment & Parts', 'NON_FINANCIAL_OPERATING', 'PE'),
            ('US.LIST2072', 'Electronic Components', 'NON_FINANCIAL_OPERATING', 'PE'),
            ('US.LIST2253', 'Electronic Gaming & Multimedia', 'NON_FINANCIAL_OPERATING', 'PE'),
            ('US.LIST2506', 'Electronics & Computer Distribution', 'NON_FINANCIAL_OPERATING', 'PE'),
            ('US.LIST2033', 'Engineering & Construction', 'NON_FINANCIAL_OPERATING', 'PE'),
            ('US.LIST2491', 'Entertainment', 'NON_FINANCIAL_OPERATING', 'EV_EBITDA'),
            ('US.LIST2471', 'Farm & Heavy Construction Machinery', 'NON_FINANCIAL_OPERATING', 'PE'),
            ('US.LIST2007', 'Farm Products', 'NON_FINANCIAL_OPERATING', 'EV_EBITDA'),
            ('US.LIST2495', 'Financial Conglomerates', 'UNKNOWN', NULL),
            ('US.LIST2490', 'Financial Data & Stock Exchanges', 'NON_FINANCIAL_OPERATING', NULL),
            ('US.LIST2478', 'Food Distribution', 'NON_FINANCIAL_OPERATING', 'PE'),
            ('US.LIST2106', 'Footwear & Accessories', 'NON_FINANCIAL_OPERATING', 'PE'),
            ('US.LIST2426', 'Furnishings, Fixtures & Appliances', 'NON_FINANCIAL_OPERATING', 'PE'),
            ('US.LIST2094', 'Gambling', 'NON_FINANCIAL_OPERATING', 'EV_EBITDA'),
            ('US.LIST2110', 'Gold', 'NON_FINANCIAL_OPERATING', 'EV_EBITDA'),
            ('US.LIST2095', 'Grocery Stores', 'NON_FINANCIAL_OPERATING', 'PE'),
            ('US.LIST2262', 'Health Information Services', 'NON_FINANCIAL_OPERATING', 'PE'),
            ('US.LIST2246', 'Healthcare Plans', 'UNKNOWN', NULL),
            ('US.LIST2460', 'Home Improvement Retail', 'NON_FINANCIAL_OPERATING', 'PE'),
            ('US.LIST2003', 'Household & Personal Products', 'NON_FINANCIAL_OPERATING', 'PE'),
            ('US.LIST2245', 'Industrial Distribution', 'NON_FINANCIAL_OPERATING', 'PE'),
            ('US.LIST2252', 'Information Technology Services', 'UNKNOWN', NULL),
            ('US.LIST2477', 'Infrastructure Operations', 'NON_FINANCIAL_OPERATING', 'EV_EBITDA'),
            ('US.LIST2429', 'Insurance - Diversified', 'UNKNOWN', NULL),
            ('US.LIST2512', 'Insurance - Life', 'UNKNOWN', NULL),
            ('US.LIST2484', 'Insurance - Property & Casualty', 'UNKNOWN', NULL),
            ('US.LIST2467', 'Insurance - Reinsurance', 'INSURANCE', 'PB'),
            ('US.LIST2465', 'Insurance - Specialty', 'INSURANCE', 'PB'),
            ('US.LIST2230', 'Insurance Brokers', 'NON_FINANCIAL_OPERATING', 'PE'),
            ('US.LIST2500', 'Integrated Freight & Logistics', 'NON_FINANCIAL_OPERATING', 'EV_EBITDA'),
            ('US.LIST2004', 'Internet Content & Information', 'UNKNOWN', NULL),
            ('US.LIST2431', 'Internet Retail', 'NON_FINANCIAL_OPERATING', 'PE'),
            ('US.LIST2063', 'Leisure', 'UNKNOWN', NULL),
            ('US.LIST2263', 'Lodging', 'NON_FINANCIAL_OPERATING', 'EV_EBITDA'),
            ('US.LIST2052', 'Lumber & Wood Production', 'NON_FINANCIAL_OPERATING', 'EV_EBITDA'),
            ('US.LIST2276', 'Luxury Goods', 'NON_FINANCIAL_OPERATING', 'PE'),
            ('US.LIST2485', 'Marine Shipping', 'NON_FINANCIAL_OPERATING', 'EV_EBITDA'),
            ('US.LIST2486', 'Medical Care Facilities', 'NON_FINANCIAL_OPERATING', 'EV_EBITDA'),
            ('US.LIST2280', 'Medical Devices', 'NON_FINANCIAL_OPERATING', 'PE'),
            ('US.LIST2220', 'Medical Distribution', 'NON_FINANCIAL_OPERATING', 'PE'),
            ('US.LIST2014', 'Medical Instruments & Supplies', 'NON_FINANCIAL_OPERATING', 'PE'),
            ('US.LIST2270', 'Metal Fabrication', 'NON_FINANCIAL_OPERATING', 'EV_EBITDA'),
            ('US.LIST2479', 'Mortgage Finance', 'UNKNOWN', NULL),
            ('US.LIST2274', 'Oil & Gas Drilling', 'NON_FINANCIAL_OPERATING', 'EV_EBITDA'),
            ('US.LIST2058', 'Oil & Gas E&P', 'NON_FINANCIAL_OPERATING', 'EV_EBITDA'),
            ('US.LIST2257', 'Oil & Gas Equipment & Services', 'NON_FINANCIAL_OPERATING', 'EV_EBITDA'),
            ('US.LIST2224', 'Oil & Gas Integrated', 'NON_FINANCIAL_OPERATING', 'EV_EBITDA'),
            ('US.LIST2226', 'Oil & Gas Midstream', 'NON_FINANCIAL_OPERATING', 'EV_EBITDA'),
            ('US.LIST2060', 'Oil & Gas Refining & Marketing', 'NON_FINANCIAL_OPERATING', 'EV_EBITDA'),
            ('US.LIST2501', 'Other Industrial Metals & Mining', 'NON_FINANCIAL_OPERATING', 'EV_EBITDA'),
            ('US.LIST2507', 'Other Precious Metals & Mining', 'NON_FINANCIAL_OPERATING', 'EV_EBITDA'),
            ('US.LIST2108', 'Packaged Foods', 'NON_FINANCIAL_OPERATING', 'PE'),
            ('US.LIST2237', 'Packaging & Containers', 'UNKNOWN', NULL),
            ('US.LIST2083', 'Paper & Paper Products', 'NON_FINANCIAL_OPERATING', 'EV_EBITDA'),
            ('US.LIST2203', 'Personal Services', 'NON_FINANCIAL_OPERATING', 'PE'),
            ('US.LIST2092', 'Pharmaceutical Retailers', 'NON_FINANCIAL_OPERATING', 'PE'),
            ('US.LIST2227', 'Pollution & Treatment Controls', 'NON_FINANCIAL_OPERATING', 'PE'),
            ('US.LIST2008', 'Publishing', 'NON_FINANCIAL_OPERATING', 'PE'),
            ('US.LIST2102', 'Railroads', 'NON_FINANCIAL_OPERATING', 'EV_EBITDA'),
            ('US.LIST2511', 'Real Estate - Development', 'UNKNOWN', NULL),
            ('US.LIST2480', 'Real Estate - Diversified', 'NON_FINANCIAL_OPERATING', NULL),
            ('US.LIST2038', 'Real Estate Services', 'NON_FINANCIAL_OPERATING', NULL),
            ('US.LIST2214', 'Recreational Vehicles', 'UNKNOWN', NULL),
            ('US.LIST2145', 'REIT - Diversified', 'REIT', NULL),
            ('US.LIST2503', 'REIT - Healthcare Facilities', 'REIT', NULL),
            ('US.LIST2475', 'REIT - Hotel & Motel', 'REIT', NULL),
            ('US.LIST2466', 'REIT - Industrial', 'REIT', NULL),
            ('US.LIST2469', 'REIT - Mortgage', 'REIT', NULL),
            ('US.LIST2457', 'REIT - Office', 'REIT', NULL),
            ('US.LIST2140', 'REIT - Residential', 'REIT', NULL),
            ('US.LIST2141', 'REIT - Retail', 'REIT', NULL),
            ('US.LIST2482', 'REIT - Specialty', 'UNKNOWN', NULL),
            ('US.LIST2273', 'Rental & Leasing Services', 'NON_FINANCIAL_OPERATING', 'EV_EBITDA'),
            ('US.LIST2005', 'Residential Construction', 'NON_FINANCIAL_OPERATING', 'PE'),
            ('US.LIST2046', 'Resorts & Casinos', 'NON_FINANCIAL_OPERATING', 'EV_EBITDA'),
            ('US.LIST2225', 'Restaurants', 'NON_FINANCIAL_OPERATING', 'PE'),
            ('US.LIST2275', 'Scientific & Technical Instruments', 'UNKNOWN', NULL),
            ('US.LIST2268', 'Security & Protection Services', 'NON_FINANCIAL_OPERATING', 'PE'),
            ('US.LIST2016', 'Semiconductor Equipment & Materials', 'NON_FINANCIAL_OPERATING', 'PE'),
            ('US.LIST2015', 'Semiconductors', 'NON_FINANCIAL_OPERATING', 'PE'),
            ('US.LIST2504', 'Shell Companies', 'UNKNOWN', NULL),
            ('US.LIST2093', 'Silver', 'NON_FINANCIAL_OPERATING', 'EV_EBITDA'),
            ('US.LIST2470', 'Software - Application', 'UNKNOWN', NULL),
            ('US.LIST2508', 'Software - Infrastructure', 'UNKNOWN', NULL),
            ('US.LIST2047', 'Solar', 'NON_FINANCIAL_OPERATING', 'PE'),
            ('US.LIST2474', 'Specialty Business Services', 'NON_FINANCIAL_OPERATING', 'PE'),
            ('US.LIST2068', 'Specialty Chemicals', 'NON_FINANCIAL_OPERATING', 'EV_EBITDA'),
            ('US.LIST2463', 'Specialty Industrial Machinery', 'NON_FINANCIAL_OPERATING', 'PE'),
            ('US.LIST2502', 'Specialty Retail', 'NON_FINANCIAL_OPERATING', 'PE'),
            ('US.LIST2496', 'Staffing & Employment Services', 'NON_FINANCIAL_OPERATING', 'PE'),
            ('US.LIST2101', 'Steel', 'NON_FINANCIAL_OPERATING', 'EV_EBITDA'),
            ('US.LIST2088', 'Telecom Services', 'NON_FINANCIAL_OPERATING', 'EV_EBITDA'),
            ('US.LIST2499', 'Textile Manufacturing', 'NON_FINANCIAL_OPERATING', 'PE'),
            ('US.LIST2487', 'Thermal Coal', 'NON_FINANCIAL_OPERATING', 'EV_EBITDA'),
            ('US.LIST2264', 'Tobacco', 'NON_FINANCIAL_OPERATING', 'PE'),
            ('US.LIST2243', 'Tools & Accessories', 'NON_FINANCIAL_OPERATING', 'PE'),
            ('US.LIST2498', 'Travel Services', 'NON_FINANCIAL_OPERATING', 'PE'),
            ('US.LIST2267', 'Trucking', 'NON_FINANCIAL_OPERATING', 'EV_EBITDA'),
            ('US.LIST2430', 'Uranium', 'NON_FINANCIAL_OPERATING', NULL),
            ('US.LIST2488', 'Utilities - Diversified', 'NON_FINANCIAL_OPERATING', 'PE'),
            ('US.LIST2462', 'Utilities - Independent Power Producers', 'NON_FINANCIAL_OPERATING', 'EV_EBITDA'),
            ('US.LIST2472', 'Utilities - Regulated Electric', 'NON_FINANCIAL_OPERATING', 'PE'),
            ('US.LIST2489', 'Utilities - Regulated Gas', 'NON_FINANCIAL_OPERATING', 'PE'),
            ('US.LIST2458', 'Utilities - Regulated Water', 'NON_FINANCIAL_OPERATING', 'PE'),
            ('US.LIST2461', 'Utilities - Renewable', 'NON_FINANCIAL_OPERATING', 'EV_EBITDA'),
            ('US.LIST2219', 'Waste Management', 'NON_FINANCIAL_OPERATING', 'EV_EBITDA')
        ) AS settings(futu_plate_code, industry, valuation_model_code, primary_valuation_metric)
    LOOP
        SELECT target.industry INTO actual_industry
        FROM public.stock_industry AS target
        WHERE target.futu_plate_code = saved.futu_plate_code
        FOR UPDATE;

        IF NOT FOUND THEN
            RAISE EXCEPTION 'Missing industry % (%). Run FUTU_CLASSIFICATION_DAILY_SYNC before restoring settings.',
                saved.futu_plate_code, saved.industry;
        END IF;
        IF actual_industry IS DISTINCT FROM saved.industry THEN
            RAISE EXCEPTION 'Industry name mismatch for %: expected %, found %. Review the source mapping before restoring.',
                saved.futu_plate_code, saved.industry, actual_industry;
        END IF;

        UPDATE public.stock_industry AS target
        SET valuation_model_code = saved.valuation_model_code,
            primary_valuation_metric = saved.primary_valuation_metric
        WHERE target.futu_plate_code = saved.futu_plate_code
          AND (target.valuation_model_code, target.primary_valuation_metric)
              IS DISTINCT FROM (saved.valuation_model_code, saved.primary_valuation_metric);

        GET DIAGNOSTICS affected_count = ROW_COUNT;
        matched_count := matched_count + 1;
        changed_count := changed_count + affected_count;
    END LOOP;

    RAISE NOTICE 'Industry valuation settings restored: matched=%, changed=%', matched_count, changed_count;
END;
$restore_industry_valuation$;

COMMIT;

-- 核對結果；若另有新產業，這份統計也會包含它們。
SELECT valuation_model_code, primary_valuation_metric, count(*) AS industry_count
FROM public.stock_industry
GROUP BY valuation_model_code, primary_valuation_metric
ORDER BY valuation_model_code, primary_valuation_metric NULLS LAST;
