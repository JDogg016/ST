const SERIES_ID = 'APU0000708111';
const SHEET_NAME = 'PriceHistory';
const LOG_SHEET_NAME = 'Sheet1';

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

function doPost(e) {
  const reject = (status, msg) => ContentService
    .createTextOutput(JSON.stringify({ ok: false, error: msg }))
    .setMimeType(ContentService.MimeType.JSON);

  let data;
  try { data = JSON.parse(e.postData.contents); }
  catch (err) { return reject(400, 'invalid json'); }

  const expected = PropertiesService.getScriptProperties().getProperty('WEBHOOK_TOKEN');
  if (!expected || data.token !== expected) return reject(401, 'unauthorized');

  const eggs = Number(data.eggs);
  if (!Number.isFinite(eggs) || eggs < 0) return reject(400, 'bad eggs');

  const date = data.date ? new Date(data.date) : new Date();
  if (isNaN(date.getTime())) return reject(400, 'bad date');

  const sheet = SpreadsheetApp.getActive().getSheetByName(LOG_SHEET_NAME);
  const lastDataInA = sheet.getRange(sheet.getMaxRows(), 1)
    .getNextDataCell(SpreadsheetApp.Direction.UP).getRow();
  const targetRow = Math.max(lastDataInA + 1, 22);
  sheet.getRange(targetRow, 1, 1, 2).setValues([[eggs, date]]);

  return ContentService
    .createTextOutput(JSON.stringify({ ok: true, eggs, date: date.toISOString(), row: targetRow }))
    .setMimeType(ContentService.MimeType.JSON);
}
