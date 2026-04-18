const SERIES_ID = 'APU0000708111';
const SHEET_NAME = 'PriceHistory';

function updateEggPrices() {
  const key = PropertiesService.getScriptProperties().getProperty('BLS_API_KEY');
  const url = 'https://api.bls.gov/publicAPI/v2/timeseries/data/';
  const endYear = new Date().getFullYear();
  const payload = { seriesid: [SERIES_ID] };
  if (key) {
    payload.registrationkey = key;
    payload.startyear = String(endYear - 19);
    payload.endyear = String(endYear);
  }

  const resp = UrlFetchApp.fetch(url, {
    method: 'post',
    contentType: 'application/json',
    payload: JSON.stringify(payload),
    muteHttpExceptions: true,
  });
  const json = JSON.parse(resp.getContentText());
  if (json.status !== 'REQUEST_SUCCEEDED') {
    throw new Error('BLS error: ' + JSON.stringify(json));
  }

  const rows = json.Results.series[0].data
    .map(d => {
      const month = Number(d.period.slice(1));
      const first = new Date(Number(d.year), month - 1, 1);
      const dozen = Number(d.value);
      return [first, dozen, dozen / 12, 'BLS ' + SERIES_ID];
    })
    .sort((a, b) => a[0] - b[0]);

  const sheet = SpreadsheetApp.getActive().getSheetByName(SHEET_NAME);
  sheet.getRange(2, 1, Math.max(sheet.getLastRow() - 1, 1), 4).clearContent();
  if (rows.length) sheet.getRange(2, 1, rows.length, 4).setValues(rows);
  sheet.getRange('A:A').setNumberFormat('yyyy-mm-dd');
  sheet.getRange('B:C').setNumberFormat('$0.000');
}
