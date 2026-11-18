# Getting Started with Create React App

This project was bootstrapped with [Create React App](https://github.com/facebook/create-react-app).

## 股票分析與選股 API

分析頁面透過 `POST /analysis/report` 取得後端分析結果，請求使用 `figi`、`horizon` 與可選的歷史 `valuationDate`；未提供日期時由後端採用最新已收市交易日。前端請求與回應型別位於 `src/services/types/dto/analysis.ts` 與 `src/services/types/dto/valuation.ts`，須與後端正式 DTO 同步。

估值區塊分開顯示自身歷史參考（`valuation.historicalReference`）、同業規模分層／自訂對標比較（`valuation.peerReference`，可透過 `GET/PUT/DELETE /valuation/{figi}/custom-peers` 管理自訂對標清單，並同步提供全產業中位數與同業財務品質中位數對照）及產業假設情境（`valuation.industryAssumption`）。富途觀察與市場共識前瞻區（`futuObservations.forward`）與已公布 TTM 實績分軌呈現，僅格式化後端回傳數值與原因碼，前端不自行推算倍數、持倉決策或將證據完整度標為勝率。

全市場選股頁透過 `POST /stock/classification` 載入交易所、產業、概念、市值規模分層（`marketCapTiers`）與估值定性（`verdicts`）字典，並以 `GET /stock/screen` 執行多維度篩選、排序與收盤日（`valuationDate`）核對。

## Available Scripts

In the project directory, you can run:

### `npm start`

Runs the app in the development mode.\
Open [http://localhost:3000](http://localhost:3000) to view it in the browser.

The page will reload if you make edits.\
You will also see any lint errors in the console.

### `npm test`

Launches the test runner in the interactive watch mode.\
See the section about [running tests](https://facebook.github.io/create-react-app/docs/running-tests) for more information.

### `npm run build`

Builds the app for production to the `build` folder.\
It correctly bundles React in production mode and optimizes the build for the best performance.

The build is minified and the filenames include the hashes.\
Your app is ready to be deployed!

See the section about [deployment](https://facebook.github.io/create-react-app/docs/deployment) for more information.

### `npm run eject`

**Note: this is a one-way operation. Once you `eject`, you can’t go back!**

If you aren’t satisfied with the build tool and configuration choices, you can `eject` at any time. This command will remove the single build dependency from your project.

Instead, it will copy all the configuration files and the transitive dependencies (webpack, Babel, ESLint, etc) right into your project so you have full control over them. All of the commands except `eject` will still work, but they will point to the copied scripts so you can tweak them. At this point you’re on your own.

You don’t have to ever use `eject`. The curated feature set is suitable for small and middle deployments, and you shouldn’t feel obligated to use this feature. However we understand that this tool wouldn’t be useful if you couldn’t customize it when you are ready for it.

## Learn More

You can learn more in the [Create React App documentation](https://facebook.github.io/create-react-app/docs/getting-started).

To learn React, check out the [React documentation](https://reactjs.org/).
